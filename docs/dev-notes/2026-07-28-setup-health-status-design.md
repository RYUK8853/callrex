# Setup health in the status card — design

**Date:** 2026-07-28
**Branch:** `feat/roadmap-v1`
**Replaces:** the "Test my setup" backlog item (`docs/dev-notes/backlog.md`), which proposed a button

---

## The problem

CallVault fails silently, and the failure is discovered after the call that mattered. Every recovery
mechanism shipped so far — the screen-lock USB fix, resilient recording, fast daemon recovery — reduces
the chance of failure without ever telling the user whether their setup works **right now**.

The backlog answered this with one action that runs the whole pipeline on demand. That is the wrong
shape. A button is a thing the user has to remember to press, and the people who need it most are the
ones who will never press it. The status card is already on screen; it should be the answer.

The card today (`HomeViewModel.computeStatus()`) checks five conditions cheaply and synchronously,
never touching the daemon or the mic. It reports whether the setup *looks* right. It cannot report
whether recording *works*, and it never claims to — which is honest, and insufficient.

## The approach

**The card reports what real calls proved.** No synthetic test, no mic access outside a real call, no
button. Every call CallVault handles writes an outcome; the card reflects the most recent one. When a
call happens that CallVault never even saw, a call-log sweep notices the gap.

Three approaches were considered:

- **Event-driven only** — record the outcome at each call end. Simple, respects auto-record settings
  for free, and misses the failure that matters most: when the daemon is dead or the service never
  woke, no event fires at all and the card keeps showing the last good state.
- **Call-log reconciliation** — ask, for each call, whether a recording file exists. Catches the case
  above, but answers "was it recorded?" by looking for a file, and files get deleted. The 114 empty
  recordings removed on 2026-07-28 would each have read as a silent failure.
- **Event-driven outcomes + a call-log sweep for gaps** — *chosen*. The outcome is recorded when it
  happens and survives the recording being deleted later. The call log is consulted for one narrow
  question only: are there calls with no outcome record at all? The join is on *did we observe this
  call*, never on *does a file exist*, so deleting recordings cannot produce a false alarm.

## What "verified" means

A call counts as verified when it produced a non-empty recording and, where observable, that recording
contained audio.

**Silence detection is not uniformly available**, and the design does not pretend otherwise:

| Capture path | PCM visible? | Silence signal |
|---|---|---|
| `VoipCaptureSession` | yes, in the daemon | already implemented (`farPartyHeard`, peak vs `FAR_SILENCE_THRESHOLD`) |
| `DirectAudioRecorderSession` | yes — `AudioRecord` → `MediaCodec` | add a peak check, symmetric with the above |
| scrcpy fallback | no — encoded frames only | **not observable**; report as unknown |

`heardAudio` is therefore a tri-state: heard, silent, or not observable on this path. The card never
claims more than was actually checked. This follows the honesty pattern `voipFarPartyHeard` set.

Cloud upload is **not** part of verified. A Drive outage must not read as "your setup is broken", and a
failed copy already raises its own notification (`recording_error_drive_copy_failed`, shipped in 1.5.2).

## Data

### Outcome recording

Three existing call-end sites already compute everything needed; each gains one call:

| Site | Verified when | Failure recorded |
|---|---|---|
| `RecordingForegroundService.routeFinalRecording` | `sizeBytes > 0` and `heardAudio != false` | `EMPTY_FILE` on the 0-byte branch; `DAEMON_DIED` when `daemonLossNotified`; `SILENT` when `heardAudio == false` |
| `VoipRecordingCoordinator.onCallEnded` | `size > 0` and far party heard | `EMPTY_FILE`, `ONE_SIDED` |
| `DirectAudioRecorderSession` | — | contributes `heardAudio: Boolean?` upward (null on the scrcpy path) |

`heardAudio == null` (not observable) does not block verification — it is the scrcpy path saying it
cannot tell, and refusing to verify on that basis would leave those users permanently unverified.

Every call CallVault handles appends its end time to `observedCallEnds`, **whatever the outcome**. The
ring answers "did we see this call", not "did it work"; a call that failed loudly is still a call the
sweep must not report as unseen.

### Persisted state

`AppPreferences` keys. No Room migration.

- `lastVerifiedAt` — epoch millis of the most recent verified call
- `verifiedFingerprint` — the setup fingerprint as it was at that moment
- `lastFailureAt`, `lastFailureReason`, `lastFailureLabel`
- `sweepWatermark` — newest call-log entry already examined
- `observedCallEnds` — bounded ring of the last 20 call end times, what the sweep matches against

### The setup fingerprint

A hash of **user-owned** setup only: recording folder URI, cloud folder URI, storage target, ADB
pairing identity, app versionCode.

Deliberately excluded: Wireless debugging and USB debugging state. CallVault toggles those itself as
normal behaviour, so including them would invalidate verification constantly through the app's own
actions.

### Derived states

Computed on each Home refresh, never stored:

`Unverified` · `Verified(at)` · `StaleAfterChange(lastVerifiedAt)` · `LastCallFailed(at, reason)` ·
`CallNotRecorded(at, label)`

A failure outranks a setup change — it is the more actionable thing to say.

## The sweep

Runs on Home refresh. Reads call-log entries newer than `sweepWatermark`:

1. Keep only answered incoming/outgoing calls. Missed, rejected and voicemail entries were never
   candidates for recording.
2. Drop directions whose auto-record setting is off. A call the user told CallVault to ignore is not a
   failure.
3. Drop calls shorter than 5 seconds. An accidental answer that ends before the pipeline finishes is
   not the failure this feature exists to catch, and it is the largest false-alarm source.
4. Match the remainder against `observedCallEnds` within ±90 s of (call start + duration).
5. No match → `CallNotRecorded`. Advance the watermark regardless of outcome.

Two refusals matter more than the matching itself:

- **Never sweep past the oldest entry in the ring.** With 20 remembered calls, anything older cannot be
  matched, and "I cannot remember" must never render as "it failed". The sweep floors itself at the
  oldest observed end time.
- **VoIP calls have no call-log entry** and are structurally invisible to the sweep. This is correct,
  not a gap: they are covered by the event path, and sweeping them would mark every WhatsApp call as
  unrecorded.

## The card

Priority order. Existing blocking problems stay first — they explain a failure rather than merely
reporting it.

```
NO_FOLDER / NOT_PAIRED / DEV_OPTIONS_OFF / UPDATE_REGRANT_NEEDED   (unchanged)
⚠ A call was not recorded — Yesterday 18:44, דנוש        CallNotRecorded
⚠ <failure line, text per reason>                        LastCallFailed
⚠ Setup changed since your last call                     StaleAfterChange
● Ready to record · last verified 11:09                  Verified
● Ready to record · your next call will confirm it works  Unverified
```

One failure line per reason, so the user is told what actually happened:

| Reason | Line |
|---|---|
| `EMPTY_FILE` | Last call produced an empty recording (0 bytes) — *the only place a byte count appears* |
| `DAEMON_DIED` | The recorder stopped during your last call |
| `SILENT` | Your last recording had no sound in it |
| `ONE_SIDED` | Only your side was recorded on your last app call |

Copy rules:

- The happy path stays plain. No "audio present", no byte counts, no step-by-step detail.
- A size appears only when it is zero, and only as a warning.
- A failure clears when a later call proves things work again. It is not dismissible; dismissing a
  silent-failure warning would defeat the feature.

## Error handling

- A call-log read that throws or lacks `READ_CALL_LOG` skips the sweep entirely and claims nothing. The
  card falls back to event-driven state. A sweep that cannot run must never invent a failure.
- Silence detection on the scrcpy path reports "not observable" rather than assuming either way.
- Writing an outcome is best-effort: a failure to persist leaves the previous state rather than
  corrupting it, and is logged.

## Testing

The sweep is a pure function over `(entries, observedEnds, settings, watermark) → gaps`, so it is
unit-testable with no Android dependency:

- ring exhaustion floors the sweep instead of reporting false gaps
- the 5-second duration floor
- direction filtering against auto-record settings
- the ±90 s tolerance, at and beyond the boundary
- missed/rejected/voicemail entries are never candidates

Plus: fingerprint stability (same settings → same hash; a changed folder → a different one; toggling
Wireless debugging → unchanged), state-derivation priority (failure outranks change; blocking problems
outrank both), and Robolectric coverage for the preference store.

## Out of scope

- Any synthetic pipeline run, on demand or scheduled. Rejected in favour of learning from real calls.
- Cloud upload as part of verified state.
- Silence detection on the scrcpy capture path — not observable there; reported as unknown.
- Why 0-byte captures happen in the first place. Separate investigation; this feature reports them.
