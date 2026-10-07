> # ⚠️ SUPERSEDED — read `2026-09-05-stuck-mic-case-file.md` instead
>
> This file grew by accretion over a week and its sections are in the order they were written, not
> the order they make sense in. Several early conclusions in it were later disproven **within this
> same file**, so reading it top-down will mislead you.
>
> It is kept for its working — the AOSP tracing, the app-op analysis, the timestamp arithmetic — and
> everything still true in it has been carried into the case file above.

# Shell mic open: the bug is real, our evidence was not

**Status: 🎯 ROOT CAUSE CONFIRMED 2026-09-05 — see "Report (30)" below, which supersedes the
uncertainty in the rest of this document.**

`CBLK_INVALID` is no longer an inference. The tester's report (30), taken on `2.3.0-rearm1`, contains
AudioFlinger tearing the record track down 1.28 s into a call, in our own log, in words. Every
question this document was written to answer is answered there.

Two things changed with it:

- **The mic-leak half looks fixed.** Every capture in report (30) opens and releases cleanly — eight
  of eight, each ending "no capture left open in this process". Needs a *system* report (app-ops) to
  confirm the green dot itself, since a debug report cannot see app-ops.
- **The re-arm fix does not work, and cannot**, as written. It has a lost-rendezvous bug of our own
  making. Details below.

Date: 2026-09-03. Supersedes the first version of this document, which concluded the opposite.

Reports: `~/Downloads/callvault_system_report (4).txt`, `callvault_debug_report (27).txt`.
Device: OnePlus **CPH2653** = **OnePlus 13 (EEA)**, `OP5D55L1` / `CPH2653EEA`, Android 16 (API 36),
i.e. **OxygenOS 16**. CallVault **2.2.1-rc3 (20205)**, STANDALONE, VoIP recording on.

> ⚠️ **Diagnostic gap, still worth fixing.** The report captures manufacturer / model / device /
> product and the API level but **not the ROM build fingerprint** — no `ro.build.display.id`, no
> OxygenOS version. "OxygenOS 16" is inferred from model + API level. Add `ro.build.display.id` and
> `ro.build.version.incremental` to the report header.

---

> **✅ VERIFIED 2026-09-03 — share-debug fix (`4d380cc`).** The maintainer confirmed on the OP12 that
> the "Preparing debug report…" modal now appears on tap. The silent-tap failure that blocked every
> diagnosis is fixed. Not yet confirmed: share completion on an unhealthy transport, and the 45 s
> degrade-to-app-report-alone branch.

## 🎯 CAUGHT IT — report (29)/(6), 2026-09-04, build `2.3.0-micfix2`

The new diagnostics did their job. First direct capture of the bug:

```
--- Microphone app-op (what the green dot actually follows) ---
MICROPHONE HELD: 1 running mic app-op(s), 1 of them as uid 2000 (shell)
    uid=2000 pack=com.android.shell op=RECORD_AUDIO running since +22m7s384ms
```

ROM now captured too: **`CPH2653_16.0.10.501(EX01)`**, fingerprint
`OnePlus/CPH2653EEA/OP5D55L1:16/BP2A.250605.015/…`. And the rewritten record-activity section behaved
exactly as designed — *"No capture was left open"* plus 9 correctly bucketed unpairable entries (1 ours,
8 Android Auto's). **The old report would have screamed "9 STILL OPEN".**

### ✅ The fix's call is ACCEPTED — the source analysis was right

```
CV:HandoffRecv: handoff track stop() before release: accepted=true (finishes the mic app-op)
```

Four times, on four calls. **There is no uid gate on `IAudioRecord::stop()` — confirmed on a real
device.** That question is closed.

### ❌ …and the op is still stranded. So it is the OTHER mechanism.

`accepted=true` at 11:27:23, and at 11:37:38 the op was still running. A `stop()` that is accepted and
still does not finish the op is the `if (!client->silenced)` guard in `stopInput()`. **`a5efcd8` cures
release-without-stop, and this phone has the silenced-stop bug.**

⚠️ **The absence of `releasing active client portId` is NOT evidence.** The surviving logcat in this
report spans **11:37:38 → 11:37:40 — two seconds**, all from report generation. The incident at
11:15–11:27 had rotated out of the 256 KiB ring long before. The detector shipped in `7250ada` can
only work if the report is taken within minutes of the event.

### 🚨 THE HEADLINE: the stuck mic and a destroyed recording are the SAME EVENT

```
11:15:33.682  HandoffEncoder started
11:15:34.755  HandoffEncoder finished: 50880 frames (1.06s)   <-- died 1 second in
   …call continues 12 more minutes…
11:27:23.461  Published recording → …_in_[C:2c9c].ogg (2511 bytes)
```

**A 710-second call produced 2511 bytes.** The mic op that is stuck started at ~11:15:31 — that same
call. One event explains both symptoms, and the `silenced` theory predicts both:

- a silenced client captures nothing → the native drain sees the track stop → the pipe hits EOF →
  the encoder "finishes" cleanly at 1.06 s with no error;
- and at stop, `if (!client->silenced)` skips `finishRecording()` → the op is stranded → the dot
  sticks.

**The green dot is not the bug. It is the visible residue of a lost recording.** That reorders the
priority entirely: a user losing a 12-minute call matters more than an indicator.

### It is intermittent — 3 of 4 calls were fine

| Call | Duration | Encoder ran | |
|---|---|---|---|
| 09-03 17:40:45 | 29.5 s | 28.3 s | ✅ |
| 09-03 18:58:50 | 282 s | 281.3 s | ✅ |
| 09-04 10:57:35 | 15.2 s | 14.8 s | ✅ |
| **09-04 11:15:32** | **710 s** | **1.06 s** | ❌ |

Only the failed call left a stuck op. Related known class: [[post-update-daemon-race]] (0.02 s into a
157-byte file) and the older false-ready/0-byte reports — **this is very likely the same defect, now
with a clean instance and a mechanism.**

### What to build next

1. **Detect truncation when it happens.** `handoff encode DONE` arriving while the call is still up is
   the signal — today nothing notices, the user gets a 2.5 KB file and no warning. Log it loudly and
   capture the audio state at that moment, while the logcat ring still holds the cause.
2. **Then try to recover** — re-arm a capture mid-call. "Resilient recording" silently failed here.
3. Only then worry about the op. It is downstream.

---
## The conclusion

Two separate things are true, and both matter:

1. **The bug is real.** The green dot has been confirmed on. A microphone app-op is being started and
   never finished.
2. **`RecordActivityReport` cannot see it.** It manufactures an unrelated finding for every
   `REMOTE_SUBMIX` capture that has ever run — ours and other apps' — so the three "still open" shell
   captures in the tester's report were never the leak. They were noise that looked like signal.

**The net position is worse than before, not better: we had a lead, it was bogus, and we now have
none.** Worse still, the noise is *actively destructive* — the unpairable entries accumulate in a
50-entry ring, so they evict the very `rec start` that would reveal the real capture. Our diagnostic
was not merely unhelpful; it was crowding out the evidence.

The chain, verified from AOSP source on `android16-release` by two independent passes:

1. **`REMOTE_SUBMIX` is a "system-only" audio source.** `MediaRecorder.isSystemOnlyAudioSource()` has
   the REMOTE_SUBMIX case *deliberately commented out* of the `return false` list, so it falls to
   `default: return true`.
2. **Its config therefore never enters the state table.**
   `RecordingActivityMonitor.onRecordingConfigurationChanged()` logs the event and **returns early**,
   never calling `updateSnapshot()`. The `RecordingState` for that riid keeps `mConfig == null` for
   life. The comment in AOSP is explicit: *"still want to log event, it just won't appear in
   recording configurations"*.
3. **So the client-side start/stop can never be logged.** `updateSnapshot()` gates its log line on
   `configChanged`, and `RecordingState.setActive()` returns `mConfig != null` → always `false`.
4. **And the server-side start/stop is suppressed too**, for any client with a valid riid —
   `AudioInputDescriptor::updateClientRecordingConfiguration()` returns early when
   `!client->isLowLevel() && (event == START || event == STOP)`.
5. **What survives is `rec update`** — updates are not suppressed, so they reach step 2's `enqueue`
   and appear in the ring log.

**A `REMOTE_SUBMIX` capture can therefore only ever appear as an `update` with no `stop`. Not
sometimes. Structurally, always.**

And our parser is documented to treat exactly that as a leak:

> `update` counts as an open the same way `start` does: the events log is a ring, so a recording that
> began before the window shows only its later `update`, and treating that as "not open" would hide
> exactly the long-lived capture this exists to find.

That reasoning is sound for ordinary sources and guaranteed wrong for system-only ones.

### The Android Auto control was right all along

Six unmatched `REMOTE_SUBMIX` entries from `com.google.android.projection.gearhead` in the same dump.
Android Auto is not leaking six microphones. It is the same artefact. **This was flagged as the
control that cut against the hypothesis, and it should have been weighted far more heavily than it
was.**

### 🔬 Proof on the maintainer's own OP12 — same ROM generation, no symptom

`CPH2581`, **OxygenOS `CPH2581_16.0.9.400(EX01)`, Android 16** — the same OxygenOS 16 generation as
the tester's OP13. This phone has never shown a stuck-microphone problem.

Running `RecordActivityReport`'s exact parsing logic over its `dumpsys audio`:

```
total rec events parsed: 50            <-- the ring is EXACTLY saturated
STILL OPEN: 5 capture(s), 5 of them uid 2000 (shell)
  [update] riid=3151 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=4679 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=4743 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=6351 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=6415 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
```

**Five phantom "still open" captures on a healthy phone — worse than the tester's three.** Every one
is an `update`, every one is `REMOTE_SUBMIX`. Meanwhile every `MIC` and `VOICE_CALL` capture in the
same dump pairs cleanly:

```
rec update riid:6359 src:MIC        → rec stop riid:6359    ✅
rec update riid:6375 src:MIC        → rec stop riid:6375    ✅
rec start  riid:6383 src:VOICE_CALL → rec stop riid:6383    ✅
rec update riid:6423 src:MIC        → rec stop riid:6423    ✅
rec update riid:4743 src:REMOTE_SUBMIX → (never)            ❌ structurally impossible
```

**And the actual indicator evidence says the mic is clean.** `dumpsys appops`, uid 2000,
`com.android.shell`:

```
RECORD_AUDIO (allow):
  Access: [cch-s] 2026-09-03 13:55:50.695 (-34m58s592ms) duration=+19s261ms
```

A **completed** access with a closed duration, not a running op. No stranded `OP_RECORD_AUDIO`, no
green dot. And it reconciles exactly: that op opened at 13:55:50.695, the matching
`rec update riid:6423 src:MIC` is at 13:55:50.721 and its `rec stop` at 13:56:09.960 — **19.2 s,
matching `duration=+19s261ms` to the millisecond.**

The ring being exactly saturated at 50 also confirms the eviction concern below is live rather than
theoretical.

**This settles what the submix entries are worth — nothing — on a device with no symptom. It does not
settle the tester's problem, which remains real and unexplained.**

### On-device corroboration (OP9 Pro, `daabf34f`, OxygenOS 14)

```
rec update riid:183 … src:VOICE_COMMUNICATION … pack:com.whatsapp
rec stop   riid:183 … src:VOICE_COMMUNICATION … pack:com.whatsapp
→ 8 events: 4 rec update, 4 rec stop. 0 unmatched. No REMOTE_SUBMIX present.
```

Two things this pins down: ordinary (non-system-only) sources **do** pair correctly on
OnePlus/OxygenOS — so the ROM is not generally broken — and the normal event kind on this OEM is
`update`, not `start`, which is consistent with the tester's lines being updates.

---

## `REMOTE_SUBMIX` cannot light the green dot either

Independent of the bookkeeping, from `frameworks/av`:

- `ServiceUtilities.cpp::getOpForSource()` maps `AUDIO_SOURCE_REMOTE_SUBMIX` to
  **`OP_RECORD_AUDIO_OUTPUT` (106)**, and `isRecordOpRequired()` returns **false** for it — so
  `OP_RECORD_AUDIO` (27) is never started at all.
- SystemUI's `AppOpsControllerImpl.OPS_MIC` watches `OP_RECORD_AUDIO`, `OP_PHONE_CALL_MICROPHONE` and
  the ambient/sandbox trigger ops. **`OP_RECORD_AUDIO_OUTPUT` is not in the list.**
- `AudioPolicyService::isVirtualSource()` lists REMOTE_SUBMIX, exempting it from the silencing/UID
  machinery, and `AudioRecordClient::isAppOpSource()` returns false for it, so no op monitor is even
  created.

**Verified on a live device:** `RECORD_AUDIO`, `RECORD_AUDIO_HOTWORD` and `RECORD_AUDIO_OUTPUT` are
three distinct appops on the OP9 Pro. The op split is real, not a source-reading artefact.

So even had the submix captures genuinely been open, they could not have produced the symptom.

---

## Unregistering the policy would not have helped

`AudioManager.unregisterAudioPolicyAsyncStatic()` — the variant we call — never calls
`invalidateCaptorsAndInjectors()`. Even the synchronous variant only calls `record.stop()` on its
captors, never `release()`, and explicitly tolerates them already being released. Nothing in policy
unregistration reaches `RecordingActivityMonitor`; only `releaseRecorder(riid)` (driven by our
`AudioRecord.release()`) or binder death removes a record state.

**`stop()` + `release()` is the correct and sufficient teardown, and we already do it.** Leaving the
policy armed leaks the `AudioMix` routing resource — worth tidying for hygiene, invisible here, and
not the dot. Note `scrcpy`, whose recipe ours derives from, never unregisters either; it gets away
with it because its `app_process` exits per session, which a long-lived daemon does not.

**The "arming is free" comment in `VoipCaptureController` stands.** It was doubted in the first
version of this doc; that doubt is withdrawn.

---

## ⚠️ The VoIP disarm test is VOID — do not draw anything from it

The tester **cleared the stuck microphone first, and only then turned VoIP off** and exported. So
reports (28)/(5) are a clean baseline taken from an already-healthy phone, not a disarm test. They
neither implicate nor exonerate VoIP. (His words: *"Yes just after the first one log"*.)

## 🚨 The trigger was a CARRIER call, not VoIP

From the tester, unprompted: *"the call just before the bug was neither VoIP nor Android Auto… Yes
[cell call]"*, with *"Android auto just before, and VoIP call in the morning"*.

**Report (27) confirms it exactly.** The last call before the 12:33 report was carrier, on the
**handoff** path:

```
11:46:58.221  capture#5 opened: handoff held record (source=voice-call, ch=2, rate=48000)
12:07:33.480  RecorderServer: stopHandoff requested
12:07:33.483  capture#5 released … (still live: 0)
12:07:33.522  HandoffRecv: handoff capture input released (forced collection of the IAudioRecord ref)
```

This kills the VoIP-centric framing this document started with. It also means the *near MIC* suspicion
from the previous section, while sound in principle, was aimed at the wrong call.

### That call itself tore down cleanly

`HandoffSource.releaseHeld()` does `rec.stop()` then `rec.release()` and audits the outcome, and the
log shows it ran. The daemon was pid 29689 throughout the call and still 29689 at 12:33 — **it did not
die during that call**. So this is not a simple missing-`stop()`.

### 🚨 But the handoff path has a documented hole, in our own comment

`HandoffReceiver.forceReleaseCaptureInput()` says it plainly:

> In the normal flow the daemon's `stopHandoff` stops its own track and frees the input; **but when the
> daemon has DIED — the case this whole feature exists for — nothing else can release it.** So force a
> collection here.

When the daemon dies mid-call, **nobody ever calls `stop()`**. The app drops its binder ref, the
RecordTrack is destroyed, and destruction reaches `AudioPolicyService::releaseInput()` — which clears
`client->active` **without** `finishRecording()`. Only `stopInput()` finishes the op. The result is a
**stranded `OP_RECORD_AUDIO` under uid 2000**: a green dot attributed to "Shell" that no longer belongs
to any living process and can never clear itself.

This phone demonstrably kills daemons — report (27), 10:26:57:
`Clearing 3 other recorder process(es): [29687, 29724, 29729] (I am 29689)`.

**This is the leading hypothesis and it is a real latent bug regardless of whether it caused this
instance.** It is untested.

### Why nothing we collect can see it

A **stranded app-op is invisible to `dumpsys audio`'s record-activity log entirely** — that log tracks
recording *configurations*, not ops. So a phone with a permanently lit dot can honestly report "No
capture was left open", which is exactly what report (5) says. Only `dumpsys appops` shows it, as
`Running start at:` with no closed duration. rc3 does not collect it; `2.3.0-micdiag2` does.

## Earlier reading of the disarm test — superseded by the above

## Disarm test result — reports (28) + (5), 2026-09-03 13:53

The tester turned **VoIP recording off** and exported again. `VoIP recording: off` confirmed in the
config header.

**Record-activity section now reads:** *"No capture was left open. Every recording that started also
stopped."* The three `REMOTE_SUBMIX` orphans are gone — consistent with the policy being disarmed so
no new submix captures are created, plus normal ring turnover. **This tells us nothing new**: we
already know those entries were never evidence, and their absence is not evidence either.

**What the test could NOT answer: whether the green dot is still lit.** rc3's report has no way to
see the microphone app-op, which is the only thing that reflects the indicator. So the single fact
the experiment was run for is missing from its own output. That is the diagnostic gap, restated.

Other facts from this pair:

- One recorder process (**pid 26465**, was 29689 on 2026-09-03 12:33) — **the daemon restarted between
  the two reports.**
- No calls, no `CaptureAudit` lines, `Recorder host lines: none` — a 28-minute idle window.
- `WRITE_SECURE_SETTINGS: false`, with `Self-grant … failed: Stream closed.` filling most of a 52-line
  report. Still a live, separate problem on this phone.

### Why the daemon restart matters

If the dot **is** still lit after that restart, the held op **outlived the process that started it**.
That is only possible if the op was never finished — which is exactly the
`releaseInput()`-without-`finishRecording()` path below, reached via binder death rather than a clean
teardown. It would also explain why it never clears on its own.

### And the suspect inside the VoIP path is the near capture, not the far one

A VoIP session opens **two** captures: far (`REMOTE_SUBMIX`, cannot light the dot) and **near
(`MIC`, absolutely can)**. Turning the feature off stops both, so this test cannot separate them — but
only the near one is a candidate. If the dot cleared, the near MIC capture is where to look, not the
policy or the submix.

---

## Clearing a stranded op without a reboot — measured on the OP9, 2026-09-03

If an op really is stranded, the user's only known recovery is a reboot. Tested what else works, by
starting a real `RECORD_AUDIO` op on the OP9 test device and trying to clear it:

| Attempt | Result |
|---|---|
| `cmd appops reset com.android.shell` | ❌ **does not clear it** — resets modes only, `Running start at:` survives |
| `cmd appops set com.android.shell RECORD_AUDIO ignore` | ❌ **does not clear it** — the running op is unaffected by a mode flip |
| `cmd appops stop com.android.shell RECORD_AUDIO` | ✅ **clears it** — `Running start at:` gone, duration closed |

**Why this matters:** the app already holds an ADB shell as uid 2000, so if `stop` works on a genuinely
stranded op it is a one-command recovery — and a candidate in-app "release the microphone" action,
instead of telling users to reboot.

❌ **CONFIRMED DEAD for our case — do not build on it.** The caution above was right. From source:
`AppOpsService` passes `shell.mToken` (= `AppOpsManager.getClientId()`, *system_server's* client id),
while `AttributedOp.finishOrPause()` looks the op up by `mInProgressEvents.indexOfKey(clientId)`. An op
started by audioserver is keyed on **audioserver's** token, so the lookup misses and the command is a
**silent no-op**. It only worked in the table above because `cmd appops start` and `cmd appops stop`
share system_server's token. `AppOpsManager.finishOp` is token-scoped by construction, so no third
party can do it either.

**So there is no supported user-space recovery.** What remains:
- **Restarting audioserver** clears it (its static token dies → `onClientDeath` → `finished`) but
  destroys every track on the device.
- **A uid-mode flip** *pauses* the op — `setUidMode` → `attrOp.pause()` →
  `scheduleOpActiveChangedIfNeededLocked(false)` clears the indicator — but does not finish it, and
  restoring the mode calls `resume()` and the dot returns. Diagnostic probe, not a fix. Untested.
- **Reboot.**

This is why the fix matters rather than a recovery button.

The device was left as found: mode `allow`, no running op.

---

## ✅ AOSP source verdict on the fix — it can work (2026-09-03)

Read on `GrapheneOS/platform_frameworks_av` `16-qpr2` + `17`, cross-checked against LineageOS
`lineage-21.0`/`22.2`/`23.0` and `aosp-mirror/platform_frameworks_base` `main`.

**1. There is NO uid, pid or permission check on `IAudioRecord::stop()`.** `RecordHandle::stop()` →
`RecordTrack::stop()` → `RecordThread::stop()` → `AudioSystem::stopInput()` →
`AudioPolicyService::stopInput()` — not one of them checks the caller. The proof it would be visible:
`RecordTrack::shareAudioHistory()` *is* uid-gated, explicitly —

```cpp
if (callingUid != mUid || callingPid != mCreatorPid) return PERMISSION_DENIED;
```

— so AOSP knows how to gate this interface and gates only that one method. `AudioPolicyInterfaceImpl`
uses `CHECK_PERM(MODIFY_AUDIO_SETTINGS, …)` at ~25 entry points; `startInput`/`stopInput`/`releaseInput`
are not among them. **Our transaction code and descriptor are also confirmed correct**: the AIDL
declares `start` then `stop`, so `stop` = `FIRST_CALL_TRANSACTION + 1`, descriptor
`android.media.IAudioRecord`.

**2. `stopInput()` finishes the op; `releaseInput()` never has** — identical from Android 14 through
current `main`. Not a regression, and there is **no fix upstream**.

**3. Process death cannot clear it.** The started op is keyed on a **process-static `BBinder` created
inside audioserver** (`ServiceUtilities.cpp::resolveAttributionSource`, *"a static token for
audioserver requests"*), not on the helper's binder. App-ops' `onClientDeath` therefore never fires for
the helper. `AudioPolicyService::binderDied` is a stub that only logs; nothing in AudioFlinger or
`RecordingActivityMonitor` touches app-ops. **A started `OP_RECORD_AUDIO` outlives the process it is
attributed to, indefinitely.**

**4. No double-finish risk.** After our explicit `stop()` the track is `PAUSED`, and
`RecordTrack::destroy()` only calls `stopInput()` for prior states `ACTIVE`/`STARTING_2`/`PAUSING` —
`PAUSED` takes the `break`. Exactly one `finishRecording`.

### 🚨 Correction to this document's own mechanism claim

`RecordHandle::~RecordHandle()` is `stop_nonvirtual(); mRecordTrack->destroy();` — so dropping the last
binder reference **does** finish the op. The leak is therefore **not** "destruction skips the op". It is
that **the destructor never runs while our app still holds the reference**, and a Java `BinderProxy` is
released only on GC/finalization, at an unpredictable time. Two things follow:

- While we hold the reference the microphone is **genuinely still open** — the track is `ACTIVE` and
  still capturing. **The green dot is accurate, not stale.** That reframes the whole bug: it is not a
  cosmetic indicator fault, it is a real open microphone.
- `forceReleaseCaptureInput()`'s `System.gc()` was already *trying* to trigger this, best-effort. The
  explicit `stop()` makes finishing the op **deterministic instead of GC-timed**, which is the point.

### Two caveats to carry into the release

- `RecordThread::stop()` **blocks** on `mStartStopCV` until the record thread acknowledges. It is a
  synchronous binder call — must not run on a UI thread. (Ours runs on the engine's stop path, not the
  main thread.)
- If the track was already **terminated or invalidated**, `RecordThread::stop()` returns `false` and
  `stopInput()` is *not* called from `stop()`; the op is then finished only by destruction. **So keep
  dropping the binder reference after `stop()` — do not treat the call as a replacement for it.** Our
  implementation already does both, in that order.

---

## 🚨 The bug is STRUCTURAL to the handoff design, not a missing call

Second independent source pass reached the same conclusion by a different route, and framed it better:

`RecordHandle` is the **audioserver-side `BnAudioRecord`**, and its destructor is the teardown:

```cpp
RecordHandle::~RecordHandle() {
    stop_nonvirtual();
    mRecordTrack->destroy();
}
```

It runs only when the last **remote** binder reference drops. Normally the helper's death drops that
last reference and audioserver tears the track down by itself. **Handing the `IAudioRecord` to the app
is exactly what defeats that cleanup** — our reference keeps `RecordHandle` alive, `stopInput()` is
never reached, and the op stays started.

So this is a structural consequence of the handoff feature, not an oversight. **And there is no prior
art: a GitHub code search for `IAudioRecord` outside AOSP trees finds only vendored headers. Nobody
else passes a capture binder across processes.**

### 🔎 Field evidence: someone else has this symptom

**[scrcpy #5980](https://github.com/Genymobile/scrcpy/issues/5980)** — *"Microphone Remains Active After
scrcpy Audio Session Ends (Shell App Still Running)"*, **OnePlus 11, Android 15**, scrcpy 3.2, opened
April 2025, **still open with zero maintainer replies**. Uses `--audio-source=mic`, i.e.
`OP_RECORD_AUDIO`. Not identical — their shell process was still alive — but it is the same symptom,
on the same OEM, publicly unexplained. The first outside confirmation that this is real and not
specific to us.

### How other projects tear down (none of them documents the hazard)

| Project | Teardown |
|---|---|
| scrcpy (both capture classes) | `release()` only |
| JetBrains Android Studio mirroring agent (shell uid) | explicit `Stop()` then `Release()` |
| jqssun/android-display-mirror (Shizuku) | `stop(); release();` **and** stops in the `destroy()` hook |
| AmbientMusicMod | `release()` only |

`AudioRecord.release()` already calls `stop()` internally, so **ordering was never the risk — binder
liveness is.** That is why only our handoff mode is affected.

### The architecturally safe shape, if we ever revisit handoff

[NowPlaying's `ProxyAudioRecord`](https://github.com/KieronQuinn/NowPlaying) keeps the `AudioRecord`
**inside** the privileged process and proxies each call (`create`/`startRecording`/`read`/`release`)
over AIDL. The binder refcount never leaves that process, so death-cleanup keeps working. That is the
shape that has this bug by construction impossible — at the cost of the daemon being on the call path,
which is the whole thing handoff exists to avoid. Recorded as a known alternative, not a proposal.

### Worth a separate look: the VoIP policy may leak in audioserver

`AudioRecord.release()` unregisters a capture policy only when the **framework's own** builder wired it
(`unregisterAudioPolicyOnRelease`). We build the `AudioPolicy` by reflection, so `release()` does not
unregister it — `unregisterAudioPolicyAsyncStatic` must be called explicitly. We do have
`VoipAudioPolicy.disarm()`, but it runs only when the feature is switched off. Likely harmless since
the policy dies with the daemon process, but unverified. scrcpy and the JetBrains agent both leak this
per session.

---

## ⚠️ There are TWO ways the op can be left started — our fix only covers one

A third source pass corrected part of the story above. Both corrections matter.

### Correction: a killed client's op IS normally finished

`RecordHandle` is the `BnAudioRecord` living **inside audioserver**, so when an ordinary shell recorder
is SIGKILLed the binder driver drops the remote ref, `~RecordHandle()` runs *in audioserver*, and
`stop_nonvirtual()` → `stopInput()` precedes `releaseInput()`. **So "a killed daemon can never release
its capture" is wrong as a general claim.**

**It is right for us, and only for us** — because our app deliberately retains the binder, the
destructor does not run on the daemon's death. That is the structural point: normal clients are safe
precisely because nothing else holds the reference. We are the exception, and there is no prior art.

### 🚨 The second mechanism: the `silenced` guard — our fix does NOT address it

`AudioPolicyService::stopInput()` finishes the op **conditionally**:

```cpp
    // finish the recording app op
    if (!client->silenced) {
        finishRecording(client->attributionSource, client->virtualDeviceId, client->attributes.source);
    }
```

A client the policy service believes is **silenced** gets no `finishOp` even on a perfectly clean stop.
And `finishRecording()` early-returns only for `AID_SYSTEM | AID_AUDIOSERVER | AID_MEDIA | AID_ROOT` —
**uid 2000 is not on that list**, so shell is fully exposed to it.

**This is the important caveat on `a5efcd8`.** Our explicit `stop()` goes through the very same
`stopInput()` guard. If the client is silenced, the fix finishes nothing. It cures the
release-without-stop path and is powerless against the silenced-stop path.

### 🔎 Telling them apart in the field — shipped

`AudioPolicyService::releaseInput()` logs `ALOGW("%s releasing active client portId %d")` under tag
**`AudioPolicyInterfaceImpl`**. That tag is now on the system report's logcat whitelist:

| While the dot is stuck | Reading |
|---|---|
| `releasing active client portId …` **present** | release-without-stop → **`a5efcd8` is the right fix** |
| that line **absent** | the `silenced` guard swallowed the finish → **different bug, fix does not apply** |

### Why this only started happening

AOSP `9f91a5ee` ("Add NativePermissionController for audio perms") made a shell-uid AttributionSource
declare package `"shell"`. **Before that, a shell recorder had no package and produced no privacy
indicator at all.** The green dot for shell-uid capture is recent upstream behaviour, not an OEM
addition — which is why this is surfacing now.

### Google has acknowledged the underlying contract bug

On [Gerrit 3600113](https://android-review.googlesource.com/c/platform/frameworks/av/+/3600113)
(abandoned 2025-05-05), Google's audio owner wrote 2025-04-22: *"That was a regression in 25Q1 which
will be resolved in 25Q2 … there are some issues with the API contract between AppOps and audio re ref
counting. That should be fixed more durably in an upcoming release."* The named fixes landed
internally and are not on public Gerrit — so a platform fix may arrive independently of ours.

Also found: [GrapheneOS/os-issue-tracker#5128](https://github.com/GrapheneOS/os-issue-tracker/issues/5128)
— "Microphone Indicator always shown even without usage", closed, labelled `upstream`. App-attributed
rather than Shell, so it is adjacent rather than the same, but it shows the class is known.

---

## 🚨 The leading hypothesis now — UNTESTED

`AudioPolicyService::releaseInput()` (`AudioPolicyInterfaceImpl.cpp`) clears `client->active`
**without** calling `finishRecording()`:

```cpp
if (client->active) {
    ALOGW("%s releasing active client portId %d", __FUNCTION__, portId);
    client->active = false;
    client->startTimeNs = 0;
    updateUidStates_l();
}
mAudioRecordClients.removeItem(portId);
```

`stopInput()` is the only place the op is finished. **An input released while still active leaves its
started app-op un-finished.** For a `MIC` / `VOICE_*` client that stranded op is `OP_RECORD_AUDIO` —
i.e. a green dot that never goes out.

Normally `RecordTrack::destroy()` covers this by calling `AudioSystem::stopInput()` before
`releaseInput()` when the track was active. **A process that is SIGKILLed mid-capture does not run
that path** — which connects this directly to [[post-update-daemon-race]]: daemons killing each other
mid-recording is exactly the shape that strands the op.

Note the tester's log does show sibling-clearing at 10:26:57 (`Clearing 3 other recorder process(es):
[29687, 29724, 29729] (I am 29689)`), so the precondition occurs in the field.

**This is now the leading hypothesis, and it fits everything we know:** the dot is confirmed on, the
op survives the capture, and a killed daemon is exactly what skips the clean teardown. It has not
been tested — but it is where to look next, and `MicOpReport` (added 2026-09-03) is what will show it,
because a stranded op prints `Running start at:` with no closed duration.

---

## Three defects in `RecordActivityReport` — all shipped in rc3

1. **It counts system-only sources as leaks.** `REMOTE_SUBMIX` (and `ECHO_REFERENCE`,
   `VOICE_DOWNLINK`, `FM_TUNER`) can never produce a matching `stop`. **Fix: exclude them, or report
   them in a clearly-labelled separate bucket that is not called "still open".**
2. **It asserts something false.** *"that is CallVault's privileged capture, and it is what puts the
   green microphone dot on screen"* is printed for any shell-uid entry, including sources that
   provably cannot drive the indicator. **Fix: only make the indicator claim for sources that note
   `OP_RECORD_AUDIO`.** This sentence is what sent this investigation the wrong way for a day.
3. **It discards the event kind.** The regex captures `(start|update|stop)` and `OpenCapture` then
   drops it, so the report cannot distinguish a genuine unmatched `start` from an expected orphan
   `update`. That single field was the discriminator, and we threw it away. **Fix: keep and print
   it**, along with the total event count and whether the 50-entry ring saturated.

Ring saturation matters on its own: un-stoppable submix entries **accumulate**, so ours plus Android
Auto's can evict the very `rec start` that would reveal a real MIC capture. The report currently
cannot tell "no leak" from "the evidence scrolled away".

---

## The right diagnostic from here

`dumpsys audio`'s RecordActivityMonitor is **not** a usable signal for "is a capture still open". Use
instead, while the dot is lit:

- **`dumpsys appops`** filtered to uid 2000 — a *running* `RECORD_AUDIO` op is what lights the
  indicator, and it is the direct evidence. **How to read it:** a completed access prints
  `duration=+…`; an op that is still running prints no closed duration. That single distinction is
  the whole test, and it is what this report should be parsing instead of the rec-event ring.
- **`dumpsys media.audio_policy`** — authoritative for open inputs and record clients.

---

## What is still genuinely unknown

1. **Whether the green dot is actually lit at all.** Never independently confirmed. Everything in
   these two reports is consistent with it never having been on.
2. Whether the stranded-`OP_RECORD_AUDIO` path above ever fires in practice.

## Prior-art sweep: nobody else has reported this

BCR (900+ issues), ShizuCallRecorder, Shizuku, scrcpy: **zero** reports of a stuck indicator or a
submix capture leak. BCR cannot hit it — it uses `AudioRecord(VOICE_CALL)`/`InCallService` and never
registers a dynamic policy. The one directly relevant thread is
[scrcpy #4380](https://github.com/Genymobile/scrcpy/issues/4380), the origin of our
`ROUTE_FLAG_LOOP_BACK_RENDER` + `createAudioRecordSink()` recipe; nobody there raises the indicator or
policy teardown either.

---

# 🎯 Report (30), 2026-09-05 — `2.3.0-rearm1` (20313). Root cause confirmed; the fix has its own bug

Source: `~/Downloads/callvault_debug_report (30).txt`, 392 lines, generated 2026-09-05 12:50.
Same device as before: OnePlus **CPH2653** (OnePlus 13 EEA), Android 16, ROM
`CPH2653_16.0.10.501(EX01)`. STANDALONE, resilient recording **on**, VoIP on.
Includes the daemon's own 79 lines merged by timestamp — the diagnostics work end to end.

## 1. `CBLK_INVALID` is confirmed, in words, in our own log

```
11:04:54.512 [I] drain segment: streamed=241920B elapsed=1279ms dropped=0frames/0overruns
11:04:54.513 [E] handoff drain ended EARLY after 48000Hz capture:
                 TRACK INVALIDATED by AudioFlinger (CBLK_INVALID) — capture torn down mid-call
```

This was previously established only by elimination. It is now observed. **Do not re-open that
question.** Note `dropped=0 frames / 0 overruns` — we were keeping up perfectly; the platform simply
took the track away 1.28 s in.

The completeness check also fired exactly as designed:

```
11:05:18.050 [E] RECORDING IS SHORT: only 1,3s of audio for a 24,8s capture (5%).
                 The rest of the call is NOT in the file — see the drain/encode lines above for why.
```

A 25-second call produced a **2051-byte** file. Two later calls on the same build drained normally
(76 s and 504 s, both ending "stop requested (normal end of recording)"), so this remains
**intermittent**, as it always has been.

## 2. The re-arm fired correctly, built a healthy capture, and then threw it away

The recovery worked right up to the last step:

```
11:04:54.513 [W] rebuilding the capture, attempt 1/3 (call is still up)
11:04:54.944 [I] capture#3 released ... (still live: 0)          <- clean release of the dead track
11:04:55.007 [I] capture#4 opened  ... (now live: 1)             <- NEW CAPTURE CREATED FINE
11:04:55.009 [I] extracted IAudioRecord ping=true — delivering to app
11:04:59.013 [E] re-armed handoff arrived but nothing was waiting for it — closing it
11:05:03.018 [E] no re-armed capture arrived within 4000ms
11:05:03.019 [E] capture rebuild failed — the recording ends here
```

We created a working replacement capture and closed it 4 seconds later for want of a receiver.

## 3. Why — a lost rendezvous, structural, not a fluke

`HandoffReceiver.rebuild()` (around line 415) does:

```kotlin
awaitingRearm = true
if (!ask()) { ... }                                    // request the new capture — BLOCKS
rearmed.poll(REARM_WAIT_MS, MILLISECONDS)              // only NOW start waiting
```

and the delivery side does:

```kotlin
rearmed.offer(Rearm(...), REARM_WAIT_MS, MILLISECONDS) // SynchronousQueue — needs a taker PRESENT
```

`rearmed` is a **`SynchronousQueue`**, which has no capacity: an `offer` only succeeds if a taker is
*already waiting*. But the delivery happens **inside `ask()`** — the daemon calls back into the app
before the request returns — so the taker cannot possibly be waiting yet. The thread that would take
it is the one still blocked in `ask()`.

The timestamps confirm this to the millisecond, and it is worth writing down because it is what turns
a plausible story into a settled one:

| moment | time | arithmetic |
|---|---|---|
| daemon begins delivering | 11:04:55.009 | — |
| `offer` gives up | 11:04:59.013 | **55.013 + 4000 ms** — it waited its *entire* timeout |
| `ask()` returns, `poll` starts | 11:04:59.018 | — |
| `poll` gives up | 11:05:03.018 | **59.018 + 4000 ms** — it too waited its entire timeout |

Both sides burned a full 4-second timeout **in sequence, never overlapping**. That is not a narrow
race that sometimes loses; the two windows are consecutive by construction, so **the re-arm can never
succeed on this path.** It also explains the otherwise-suspicious "4-second binder delivery": the
daemon's delivery call was slow because *our own `offer` was blocking it* for its full timeout.

Cost to the user: 8.5 s of dead time, a discarded healthy capture, and the recording ends anyway.

## 4. The fix (not yet written)

Stop requiring a rendezvous. The delivery must be able to *deposit* the capture with nobody waiting:

- Replace `SynchronousQueue` with a one-slot buffering queue (`ArrayBlockingQueue(1)`), so `offer`
  succeeds immediately and `poll` finds it already there when `ask()` returns.
- Drain and close any stale contents of the slot **before** calling `ask()`, so a late delivery from a
  previous attempt cannot be mistaken for this one's.
- On give-up, drain the slot and close whatever is in it, or the fd leaks.
- The `offer` should then be untimed (it cannot block), which also stops us holding a daemon binder
  thread for 4 seconds.

⚠️ **Do not simply raise `REARM_WAIT_MS`.** The windows are sequential, so a longer timeout makes the
failure slower, not less likely.

## 5. What this says about the microphone itself

**No capture leaked anywhere in this report.** Captures #1–#8, across carrier calls and VoIP calls,
each log an open, a release, and "no capture left open in this process". The failed call included:

```
11:05:18.058 [I] IAudioRecord.stop accepted from the app (delivered=true)
11:05:18.104 [I] capture#4 released ... (still live: 0)
11:05:18.104 [I] after releasing the handoff capture: no capture left open in this process
```

So the `IAudioRecord.stop()`-before-release fix (`a5efcd8`) is doing its job on this device, and the
app process is not holding a recorder open after a call.

**This is not yet proof the green dot is gone.** A debug report cannot see app-ops; only the *system*
report can, and none was attached this time. Ask for a system report taken **while the dot is
showing**. If the dot still appears with a clean capture ledger, the holder is outside our app
process and the next place to look is the daemon's own `AudioRecord`, not the app's.

## 6. Config worth noting

`WRITE_SECURE_SETTINGS: false` on this device, with USB debugging on and Wireless debugging off.
Not implicated in this failure — the daemon was connected and recording — but it is the grant that
install-over drops, and it is worth telling the tester to re-arm it.

---

# ✅ Why six rounds produced no answer — and the fix (2026-09-05)

The tester reported that CallVault produced only the debug report and never the system report. **It
was our bug, not his setup**, and it is the reason six iterations produced no usable evidence.

## Reproduced on our own OP12

His transport state, from report (30)'s header: `Wireless debugging: false`,
`WRITE_SECURE_SETTINGS: false`. Ours differed in exactly one way — we re-grant that permission over
the cable on every install, so **our test phone is systematically healthier than any real user's.**

Setting the OP12 to his state (`pm revoke WRITE_SECURE_SETTINGS`, Wireless debugging off) and tapping
Share reproduced it exactly: a long modal, then **one file**. Confirmed by the maintainer.

## Cause

The system report runs seven commands — `logcat -g`, `logcat -G`, `logcat -d`, `dumpsys audio`,
`dumpsys appops`, `ps`, `logcat -G` to restore — each through the app's own ADB shell, each able to
force a reconnect, all behind the share screen's 45-second budget (`SettingsScreen`,
`SYSTEM_REPORT_BUDGET_MS`). When the budget runs out the app shares the debug report alone and
**says nothing at all**.

Two further facts made it worse on his device:

- Turning Wireless debugging on is not enough on its own. Without `WRITE_SECURE_SETTINGS` the app
  cannot manage the transport, and install-over silently drops that grant — which he had done
  repeatedly, because we kept sending him builds.
- The ROM floods logcat. Measured on the OP12: the 256 KiB ring sat at 252 KiB consumed, almost all
  of it `OsenseCommonUtils` spam, so CallVault's own lines rotate out within seconds. Any diagnosis
  resting on logcat history was doomed regardless.

## Fix — collect over binder instead of over ADB

The daemon already runs as the shell user and the app already talks to it over binder, so it can run
these dumps itself: no Wireless debugging, no `WRITE_SECURE_SETTINGS`, no transport, no retries, no
budget to run out. It works in Shizuku mode too, where the ADB path deliberately refuses.

A **whitelist, not a command runner** (`DiagnosticDumps`): the app names a dump, the daemon decides
what that name means, so nothing reaching the binder can choose what executes as shell. The only
caller-supplied value on a command line is the logcat size on restore, refused unless it is a plain
size. Absolute paths throughout, per the earlier CodeQL finding. ADB remains the fallback when no
daemon is connected.

**✅ VERIFIED 2026-09-05** by the maintainer on the OP12, still in the tester's broken state: two
files, fast. The condition that produced one file twenty minutes earlier now produces both.

## The lesson worth keeping

We asked the tester to tell us what happened six times because we could not make it happen ourselves.
Both bugs found today were reproducible on our own hardware once we tried: this one by matching his
permission state, and the re-arm bug by exercising the rebuild path at all — it failed 100% of the
time and never needed his phone. **Reproduce before shipping another build to a user.**

## ❌ Audio route changes do NOT cause the teardown — tested 2026-09-05, do not re-propose

The obvious theory for `CBLK_INVALID` is a mid-call audio route change, since that is what usually
makes AudioFlinger rebuild a patch. **Tested directly on the OP12 and disproven.**

The maintainer said first that they switch outputs constantly and have never seen a problem, which
was the right instinct. The measured test, from a host-side logcat capture (not the device ring,
which is flooded on this ROM):

| moment | time | offset from capture start |
|---|---|---|
| capture started | 14:45:39.049 | — |
| switched to **speaker** | 14:45:41.098 | **+2.0 s** |
| back to earpiece | 14:45:48.715 | +9.7 s |
| further switches | to 14:46:16 | — |

His failure fired at **+1.28 s**, so a genuine route change landed within a second of the same
window. Result: `recording complete: 37.1s encoded of 37.1s captured`, no invalidation, clean
release. **Route changes are not the trigger.**

What that leaves: something specific to his device, ROM or network, or to the exact timing of capture
setup there. We have **not** reproduced the invalidation on our hardware and should not pretend a new
theory is better than the last one without testing it.

### The way to validate recovery without reproducing the cause

We control the recovery, not the teardown. A developer-only trigger that forces the drain to exit
once as `INVALIDATED` would exercise the rebuild path end to end on our own phone — proving the
machinery works before a user ever sees it. That is what should have existed before the first re-arm
build went out: the lost-rendezvous bug failed 100% of the time and needed no special hardware, only
for someone to run the path once.
