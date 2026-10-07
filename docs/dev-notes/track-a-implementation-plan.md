# Track A — implementation plan

**Goal — "debug bypass".** A debugging switch is needed **once at boot**, to prepare capture, and never
again. No Wireless debugging and no USB debugging while the phone is in use, and no waiting for a
warm-up after boot. Speed is a side effect, not the point.

The mechanism: a capture track is created once while privileged, handed to the app, and simply
**started** when a call begins.

**The blocker for the whole goal is VoIP.** Carrier calls no longer need the daemon; VoIP capture still
runs *inside* it, so with VoIP recording enabled a switch must stay on. Both features work together
today — VoIP via the daemon, carrier calls via the held track — but the debug-bypass prize needs VoIP
handed off the same way. See `voip-without-the-daemon.md`.

**Why it is worth doing — stated honestly, after an early overstatement.** The 18-second cold start
that lost a call on 2026-07-27 happened in a *broken* state: both debugging switches off, so no `adbd`
and no daemon. **1.5.1 fixed that cause.** On a healthy phone the daemon simply persists — measured the
same day, alive 7m53s of an 8-minute uptime with Wireless debugging off and USB debugging on — and the
normal path is already fast.

So Track A is **not** an everyday speed-up, and it does not help in the ~16 s after boot either, since
arming needs the daemon too. What it is:

> **Insurance against the daemon dying between calls.** It changes the requirement from "the daemon must
> be alive when a call arrives" to "the daemon must have been alive at some point since boot."

That still matters — daemon death is the recurring theme of this app's failures (screen-lock `adbd`
restarts, USB mode changes, memory pressure, OEM reaping) — but it is a narrowing of failure surface,
not a visible improvement. Weigh it against the new failure mode it introduces: an evicted track that
records silence. **If step 7 (idle for hours) fails, this feature costs more than it saves.**

**WORKING end to end on a OnePlus 12, 2026-07-27.** Two consecutive calls recorded through a held
track with the creating daemon killed and never consulted:

```
14:14:57.483  OFFHOOK
14:14:57.824  Instant: starting the held track (no daemon involved)
14:14:57.826  IAudioRecord.start accepted from the app
14:14:57.827  Instant: capture live
```

**3 ms from start to live**, 344 ms from OFFHOOK (the rest is metadata and SAF file creation, which the
old path also pays) — against an 18-second daemon cold start. Recordings verified by ear.

**Proven earlier the same day, not assumed:**

- A track created with no call active, held **stopped**, started at call-connect: **−6.7 dBFS**
- The **app** may start a track the **daemon** created: accepted by AudioFlinger from an unprivileged uid
- With the creating daemon **killed by pid**: **−6.3 dBFS over 1,151,936 samples** during a verified call

---

## Shape

```
once per boot / on arm            per call
──────────────────────            ────────
daemon creates VOICE_CALL         app: HeldRecordControl.start(binder)
  track, STOPPED                  app: drain ring → encoder → SAF file
hands binder + cblk to app        app: HeldRecordControl.stop(binder)
daemon may die freely             ← no daemon, no ADB, no debugging switch
```

Everything after the handoff already exists: `HandoffReceiver` drains the ring and `HandoffEncoder`
writes the container. Track A is the *arming* and the *per-call start*, not a new capture pipeline.

## Non-negotiables

1. **Opt-in, default off, experimental** — same as Resilient recording and VoIP. It changes how
   recording *starts*, so a regression costs whole calls rather than degrading quality.
2. **Off means byte-identical to today.** The existing path must not be touched, only bypassed.
3. **Every failure falls back to the daemon path, and says so.** Silent fallback is acceptable at call
   time (recording the call matters more than explaining); silent *failure* is not.
4. **No permanent wakelock, no 24/7 mic indicator.** The track is held STOPPED, which costs neither —
   that is the reason it is held stopped and not started.

---

## Work, in order

### 1. Arming and the held track — `HeldTrackStore`

App-side holder for `binder + cblk + geometry`, plus `isUsable()` (binder ping).

- **Arm** when: the toggle is switched on, `RecorderConnection.onDaemonReady`, and app start.
- **Re-arm** when: the stored track fails a ping, or a call ends having used it (see §4).
- Arming calls the existing `startHandoffHeld` and must never block a call — it runs off the call path
  entirely, so a slow arm is invisible.

**Note:** the track dies with the *app* process (the app holds the only ref once the daemon is gone),
so arming must happen on every app start. That is cheap and needs the daemon, which is alive then.

### 2. Per-call start — a branch in `AudioRecordingEngine.startPipeline`

```
if (trackAEnabled && heldTrack.isUsable() && source == voice-call) → start held track, drain, encode
else                                                              → today's path, unchanged
```

The branch sits **before** `ensureServerRunning`, because skipping that call is the entire point.

### 3. Failure handling

| Failure | Detection | Response |
|---|---|---|
| No track armed | `isUsable()` false at call start | fall back to daemon path, re-arm after |
| `start()` refused | return value | fall back, re-arm after |
| Track evicted mid-call | `CBLK_INVALID` + stalled-ring detectors already in `nativeDrainToPipe` | finalise what was captured, notify honestly, re-arm |
| Ring geometry rejected | `HandoffGeometry.validationError()` | fall back |

Eviction is the risk that matters: `VOICE_CALL` has **source priority 0** on this device and shares an
input profile at `maxOpenCount: 2`, so another input client can take it. It cannot be prevented — only
detected and recovered from.

### 4. Reuse across calls — ANSWERED: reusable

Measured on a OnePlus 12, 2026-07-27: **two consecutive calls on the same held track**, both recorded,
both sounding correct, with no re-arm between them. So arm once per boot — the simpler shape — and no
re-arm-after-call logic is needed.

### 5. Surfacing it

- Settings ▸ General ▸ Experimental ▸ **Instant recording**, with the honest description: recording starts
  immediately because capture is prepared in advance; if the prepared capture is lost, CallVault falls
  back to the slower path.
- The readiness notification should distinguish *armed* from merely *daemon alive* — they are no longer
  the same thing.
- The debug log must record which path each recording used. Without that, "did Track A actually run?"
  becomes guesswork, exactly as "did the observer fire?" did.

---

## Audit findings — must be fixed before this ships

An adversarial read of the implementation, 2026-07-27. Ranked; the first two are the reason this is
not release-ready.

1. **CRITICAL — the held track is never stopped after a recording.** `HandoffReceiver.stop()` releases
   fields that only `onReceived()` populates; `startHeld()` never sets them, so `HeldRecordControl.stop`
   is not called on the record path. Every Instant recording leaves a **started** `VOICE_CALL` track
   behind — a permanent wakelock and a 24/7 microphone indicator attributed to "Shell", which is exactly
   the cost this feature was designed to avoid by holding the track stopped.

2. **HIGH — teardown destroys the track it means to reuse.** `AudioRecordingEngine.release()` calls
   `stopHandoff()` unconditionally in handoff mode; on the daemon that is `rec.stop(); rec.release()` on
   the held record. **The two-call reuse test passed only because the arming daemon had been killed
   first** — its replacement held no record, so the release was a no-op. On a normal phone the track
   would be destroyed after the first call. **"Reusable, arm once per boot" is therefore UNPROVEN**, and
   was reported as measured. Re-test with the arming daemon left alive.

3. **HIGH — no locking in `HeldTrackStore`.** Concurrent arms (daemon-ready and the Settings toggle) each
   create a track; the loser is leaked while still pinning the exclusive `VOICE_CALL` input, and
   `onHandoff` overwrites the previous binder/fd without releasing them.

4. **HIGH — TOCTOU between `held()` and `release()`.** `held()` hands a raw fd number to native; a
   concurrent `release()` closes it. Use-after-close in native code, not a safe fallback.

5. **MEDIUM — readiness can lie.** A track evicted by another app still pings, so the notification
   reports ready while the next call records nothing. There is no periodic re-arm and no eviction
   detection; the idle test (step 7) is the one that would expose it.

6. **LOW/MEDIUM — toggling on with the daemon down silently does nothing.** The preference reads on, but
   nothing is armed until some unrelated event triggers `onDaemonReady`.

7. **LOW — pause is a no-op** on this path, as it already is for daemon mode, but now undocumented here.

## Test plan (OnePlus 12)

Order matters: each step assumes the previous passed.

1. ✅ **Arms** — toggle on, log shows armed, no call involved.
2. ✅ **Survives the daemon** — `kill -9` the daemon by pid; the track is still usable.
3. ✅ **Records a call with the daemon dead** — verified by ear, not by file size.
4. ✅ **Instant start** — 3 ms to live, no `ensureServerRunning` anywhere on the call path.
5. ⚠️ **Second call** — passed, but CONFOUNDED: the arming daemon had been killed, so the teardown that
   would have destroyed the track was a no-op (see audit finding 2). Re-run with the arming daemon
   alive before believing "reusable".
6. ✅ **Reboot** — re-arms without intervention. Measured: boot 14:24:01 → armed 14:24:18, i.e.
   **16.4 s**, during which a call falls back to the daemon path (correctly, and still recorded). The
   Wireless-debugging flicker users will see at boot *is* the arming: it comes on to reach the daemon
   and goes off again once USB debugging can hold `adbd`.
7. **Idle for hours, then call** — the eviction question, and the one that needs patience rather than
   cleverness.
8. **Screen lock mid-call** — the classic `adbd` killer; the recording must be unaffected.
9. **Toggle off** — today's behaviour returns exactly.

**Do not skip 9.** The reversibility guarantee is what makes shipping this defensible.

## Rollback

The toggle. Off restores today's path completely, because the existing code is bypassed rather than
modified.

## Not in scope

- Removing the debugging switch — impossible without root; the daemon is still needed to *create* the
  track once per boot.
- Retiring the loopback — re-creating an evicted track needs the daemon, hence ADB.
- VoIP calls — Track A is the `VOICE_CALL` (carrier) path; VoIP has its own armed policy already.
