# Transport and daemon architecture — what actually holds this app up

Written 2026-07-27, after a day of getting it wrong three times. Everything here is measured on a
OnePlus 12 (CPH2581) and a Galaxy S24 FE (SM-S721B), both Android 16, unless marked otherwise.

The point of this document: the transports, the daemon and the recording path were each understood in
isolation and repeatedly reasoned about wrongly *together*. This is the combined model.

---

## The four moving parts

| Part | Lives as | Dies when |
|---|---|---|
| **`adbd`** | init service | **no transport is enabled** (see invariant 1) |
| **daemon** (`RecorderServer`) | detached `app_process`, shell uid 2000 | **`adbd` stops or restarts** (invariant 2) |
| **app** | normal uid | Android kills it; survives everything above |
| **capture track** | `AudioRecord` owned by whoever created it | released, or **evicted** by another input client |

## The invariants — each one measured, not assumed

1. **`adbd` runs if and only if USB debugging OR Wireless debugging is enabled.**
   `service.adb.tcp.port` (the "Record without Wi-Fi" loopback) says *where* `adbd` listens; it is not a
   reason for `adbd` to exist. Proven: with both switches off, 150 probes over 60 s got nothing; the
   listener answered 1.5 s after Wireless debugging was switched back on.

2. **Any `adbd` stop or restart kills the daemon.** Android's init calls `KillProcessGroup`, which walks
   the service's **cgroup** — `setsid` does not escape it, and our daemon's cgroup is literally
   `0::/system/uid_0/pid_<adbd>`. The only escape in Shizuku's source is `switch_cgroup()` + `setns()`,
   gated on `uid == 0`. Shizuku's maintainer: *"All processes started by adb… will be killed for sure…
   there is nothing we can do."* **Root-only. Do not attempt this again.**

3. **Toggling Wireless debugging while USB debugging is ON does not restart `adbd`.** Measured: pid
   unchanged across both toggles, daemon alive. This is why switching Wireless debugging off is safe
   *only* in that state.

4. **Permission for `VOICE_CALL` capture is checked at CREATION, not at start.** This is the single
   most valuable fact in this document — see Track A below.

5. **A capture track held STOPPED and started at call-connect delivers real audio.** Measured
   2026-07-27: held −6.7 dBFS vs a control created mid-call at −5.6 dBFS. The old "pre-created track is
   silent" finding applies only to a track left **started** across the call boundary, which pins the
   vendor HAL use-case.

---

## Boot to running, today (1.5.1)

```
boot
 └─ BootReceiver → AdbConnectionService → ensureServerRunning
     ├─ ensureConnected
     │   ├─ loopback armed AND adbd alive? → connect over 127.0.0.1:<port>
     │   └─ else → enable Wireless debugging → wait for adbd → connect → arm loopback
     ├─ launch daemon  (detached app_process, inside adbd's cgroup)
     └─ applyWdPolicy
         ├─ USB debugging ON  → switch Wireless debugging OFF   (adbd stays up: invariant 3)
         └─ USB debugging OFF → KEEP Wireless debugging ON      (it is the only transport)

running
 ├─ DaemonKeepAliveService (permanent FGS)
 │   ├─ watchdog every 60 s: daemon down twice in a row → restore a transport, relaunch
 │   ├─ observer on adb_enabled     → both switches off? enable WD. USB just on? drop WD.
 │   └─ observer on adb_wifi_enabled → user switched WD on and it is redundant? drop it (and say so)
 └─ per call → ensureServerRunning → daemon must be alive → capture starts
```

### Every state, and what happens

| USB dbg | WD | Loopback armed | `adbd` | Daemon | Notes |
|---|---|---|---|---|---|
| off | off | either | **stopped** | **dead** | The broken state. Recovered by the observer/watchdog switching WD back on. |
| off | on | no | running | alive | Default for most users. WD stays on — correct, and the notification explains why. |
| off | on | yes | running | alive | "Record without Wi-Fi": works without a network; WD still required to keep `adbd` alive. |
| on | off | either | running | alive | **Best state.** No network port open. What the app steers toward. |
| on | on | either | running | alive | Transient; app drops WD to reach the row above. |

### Where the daemon dies in normal use

- **Screen lock** on ROMs that renegotiate the USB gadget → `adbd` restart → daemon dies → keep-alive
  relaunches. Mitigated by setting Default USB Configuration to "Charging only".
- **USB mode change** (plugging into a PC as File Transfer) → same.
- **Either switch toggled while it is the only transport** → same.
- **OS memory pressure** → app and daemon both go; `START_STICKY` brings the service back.

**Cost of a death: a cold start of 18+ seconds** — measured, and long enough to lose a short call.
This is the single biggest remaining fragility, and it is what Track A removes.

---

## Track A — what the 2026-07-27 result changes

**Measured:** a `VOICE_CALL` track created with no call active, held **stopped**, and started at
call-connect produced −6.7 dBFS against a −5.6 dBFS control. Audio is real and equivalent.

Combined with invariant 4 (permission checked at creation), the target shape is:

```
once per boot            per call
─────────────            ────────
bootstrap ADB            app calls start() on the track it already holds
daemon creates the       reads the ring, encodes
  track (STOPPED)        stop() at the end
hands binder+cblk        ← no daemon, no adbd, no debugging switch involved
  to the app
daemon may now die
```

The daemon becomes a **once-per-boot track factory**. Its death stops mattering, and so does the
18-second cold start.

### What it does NOT do

- **It does not remove the debugging switch.** Creating the track still needs the privileged daemon
  once per boot, which still needs `adbd`, which still needs USB debugging or Wireless debugging.
- **It does not retire the loopback.** Re-creating an evicted track needs the daemon again.

### ANSWERED 2026-07-27 — the app CAN start a track the daemon created

`content call --method trackAProbe` on a OnePlus 12 returned `started=true`: AudioFlinger accepted
`IAudioRecord.start()` from the app's unprivileged uid, on a track created by the shell-uid daemon.
Capture permission really is bound to creation, not to the caller of `start`.

So Track A is viable in principle: daemon creates once per boot, app runs it per call.

**PROVEN 2026-07-27 — audio flows with the creator dead.** A track created by the daemon, handed over
stopped, its creator then killed by pid, started by the app and drained by the app during a verified
`MODE_IN_CALL` call: **−6.3 dBFS over 1,151,936 samples**, against a −6.7 dBFS same-process baseline.
Track A works.

Three probe bugs cost three calls before this landed, all mine and all worth remembering: calling native
code before loading the library (twice — once per process), handing a `ParcelFileDescriptor`'s fd to
native code that closes it (fdsan aborts the process), and — worst — measuring twice **after the call
had ended** and reading the resulting digital silence as a negative result. The probe now refuses to
measure unless `AudioManager.getMode() == MODE_IN_CALL`. Note that guard must ask `AudioManager`, not
`dumpsys`: the app holds no DUMP permission, so a shell-based check silently answers "no call" forever.

**Superseded:** that *audio actually flows* through that
app-started track with the daemon **dead**. `started=true` means the binder transaction was accepted
and returned no error — it does not by itself prove the transaction was `start` (the AIDL codes are
assumed from declaration order) nor that samples arrive. Both are settled by one measurement: kill the
daemon by pid, place a call, drain the ring, compare dBFS against a control. Do not build on this until
that runs.

### The original open question, for the record

**Can the *app* call `start()` on the handed-off `IAudioRecord`, with the daemon dead?**

The shipped handoff proves the app can *read* the ring without the daemon. Control is different:
`start()` is a binder transaction into AudioFlinger, and whether it validates the *calling* uid (the
app) or the *registered* uid (shell, from creation) is not established. If it accepts the app, Track A
is real. If it demands the owning process, the daemon is needed at call time and the win evaporates.

**Testing note:** kill the daemon **directly by pid**, not by toggling a debugging switch. Toggling
changes `adbd` state and drags transports into a test that is about binder permissions — that is exactly
the kind of confound that produced three wrong conclusions today.

### Risks that remain even on success

- **Eviction.** `dumpsys media.audio_policy` shows Telephony Rx sharing an input profile at
  `maxOpenCount: 2`, and `VOICE_CALL` has **source priority 0** — the first to be closed under pressure.
  Needs detection and re-creation (which needs the daemon, hence ADB).
- **Blind failure.** A bare binder+cblk holder cannot self-heal; `restoreRecord_l` is client-side in the
  owning process. The shipped `CBLK_INVALID` and stalled-ring detectors make death visible, not
  recoverable.
- **Held stopped is evictable; held started is not** — but held started pins the HAL use-case (silent
  call) *and* costs a permanent wakelock and a 24/7 mic indicator attributed to "Shell". Stopped is the
  only viable choice, so eviction must be handled rather than avoided.
