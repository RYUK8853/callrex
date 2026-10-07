# The keep-alive rewarm latch can permanently disable recording

**Found:** 2026-07-30, on the maintainer's OP12, by diagnosis — not by the app noticing.
**Severity:** high. Recording stops completely, silently, until the app process is killed. No user
would ever discover the recovery.
**Introduced:** `a2b248e`, shipped in **v1.4.0**. **Not a 1.5.3 regression** — 1.5.3's install merely
supplied the trigger.

## Symptom

"Since yesterday recordings aren't working." The app looked healthy from the outside: process alive,
`DaemonKeepAliveService` foreground, battery-exempt, standby bucket EXEMPTED,
`WRITE_SECURE_SETTINGS` granted, adbd's tcpip listener up on its port.

But the daemon was **dead and never being relaunched**, and had been for ~21 hours.

## Evidence, in the order it landed

1. `pgrep -f com.baba.callvault.server.Recorder[S]erver` → nothing. No daemon = no recording.
2. Launching the daemon **by hand** with the app's own command worked first try
   (`RecorderServer starting pid=… uid=2000`). So the daemon was fine; the app was not launching it.
3. The app held exactly one socket to adbd, in **`CLOSE_WAIT`**:

   ```
   127.0.0.1:34114 → 127.0.0.1:51392   state 08   uid 10546
   ```

   adbd had hung up; the app never closed its end. Sampled three times over 36 s: **identical local
   port, no new connection attempts, no daemon.** The app was not retrying.
4. Thread list for the app process still contained a live **`cv-keepalive-rewarm`** thread —
   state `S`, `00:00:00` CPU. Parked, not spinning. Plus four leaked `cv-shell-probe` threads,
   which is `probeShellOnce`'s documented abandon-on-timeout behaviour firing over and over against
   the dead socket.

## Root cause

`DaemonKeepAliveService.maybeRewarm`:

```kotlin
private fun maybeRewarm(force: Boolean = false) {
    if (rewarming) return                    // ← guard
    ...
    rewarming = true
    Thread {
        val ok = runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }…
        rewarming = false                    // ← ONLY cleared here
    }.start()
}
```

`rewarming` is a latch cleared exclusively by the worker thread finishing. The worker calls
`ensureServerRunning`, which takes `synchronized(AdbShell.heavyOperationLock)` and then
`AdbShell.ensureConnected` — and **`ensureConnected` is unbounded** (already a known backlog item,
previously filed as a latency problem). On a half-dead connection it blocks indefinitely.

So one hung relaunch attempt latches `rewarming` **true for the life of the process**, and the 60 s
watchdog then returns at its first line, forever. The daemon is never relaunched again. The only
escape is killing the app — which nothing in the product ever does or suggests.

`ensureConnected` returning `true` on a stale connection is what starts the chain: it trusts
`AdbConnectionManager.isConnected`, which is a state flag, not a liveness check. A `CLOSE_WAIT`
socket satisfies it.

## What actually happened — settled by the call log, 2026-07-30

Two earlier answers here were wrong, and both were guesses dressed as findings: first "the 1.5.3 USB
install churned adbd", then "the install-over did it". The device settled it.

**Recordings against the call log:**

| time | call | recording |
|---|---|---|
| 07-29 13:15 | *(1.5.3 installed over local build 1.5.3-dev / 10623)* | |
| 07-29 13:21 | out 7 s | none — short-call cold start, a separate known issue |
| 07-29 15:29 | in **3040 s** | ✅ 12,653,000 B |
| 07-29 17:33 | in **1421 s** | ✅ 5,906,285 B |
| *overnight, phone idle* | | |
| 07-30 09:54 | in 66 s | ❌ 0 bytes |
| 07-30 09:56 | in 1262 s | ❌ 0 bytes |
| 07-30 10:25 | in 1085 s | ❌ 0 bytes |

Two long calls recorded perfectly **2¼ and 4½ hours after the install**. That exonerates the install,
the cable, and 1.5.3 by evidence rather than argument — and matches the maintainer's objection that
cable cycles and install-overs had been routine for many versions without this.

**It broke overnight, and the overnight window is the hostile one.** From `waitForShellReady`'s own
doc: *"On OnePlus, adbd RESTARTS on screen on/off transitions (even cable-out, WD off) — a TCP connect
to the loopback port can succeed while adbd is still mid-init."* While the phone idles the daemon is
reaped (normal), the keep-alive rewarms every 60 s, and adbd restarts on every screen transition —
hundreds of chances to land a connect *inside* an adbd restart.

`connectLoopback` called:

```kotlin
mgr.connect("127.0.0.1", port)   // TCP connect + full CNXN/AUTH handshake, no timeout anywhere
```

When adbd dies mid-handshake the reader dies and nothing ever signals the waiter, so the caller parks
**forever** — holding this object's monitor and `heavyOperationLock`. Every observation matches:

- socket left in **`CLOSE_WAIT`** — peer sent FIN, our side never closed
- thread parked at **`00:00:00` CPU** — waiting on a latch, not spinning on a socket read
- park point at launch attempt 2's `forceReconnect`, after `waitForShellReady` had already failed

**So it is a rare race that finally came up, not a regression.** It needed one bad roll out of hundreds
of overnight attempts. The `rewarming` latch is what turned a transient hang into a 16-hour outage.

## The fix — implemented 2026-07-30

Layers 1 and 2 are done; layer 3 stays on the backlog.

1. ✅ **The latch cannot stick.** The two fields became `RewarmGate` (`RewarmGate.kt`), a small
   synchronized class that expires an in-flight attempt after `REWARM_STUCK_MS` (90 s) so a wedged
   one can be superseded instead of vetoing every future attempt. Pure logic, so it is unit-tested
   directly — nine cases in `RewarmGateTest`, including the wedge itself.
2. ✅ **`ensureServerRunning` is bounded.** `launchDaemonBounded()` runs it on a worker and
   `join(LAUNCH_BUDGET_MS)` (45 s), abandoning it on timeout exactly as `probeShellOnce` abandons a
   hung probe. On timeout it also calls the new `AdbShell.dropConnection()`, which closes the
   half-dead socket so the abandoned thread unwinds and **releases `heavyOperationLock`** — without
   that, every later attempt would queue behind it: bounded, but never succeeding.
3. ✅ **The connect itself is bounded** — this is the actual cure, and it was only identified once the
   call log dated the outage to the overnight window. `connectBounded` runs the library's connect on a
   throwaway thread with `CONNECT_BUDGET_MS` (8 s) and abandons it on timeout. Abandoning is safe
   *because it is a separate thread*: it never entered the `AdbShell` monitor, so the caller returning
   false frees both locks and the next attempt runs clean. Layers 1-2 alone were not enough —
   `dropConnection` closes the socket, but a thread parked on a handshake latch may never be woken by
   that.
4. ⬜ Make `isConnected` prove liveness rather than trusting a flag. Still open, and still why a
   `CLOSE_WAIT` socket reads as connected.

Two things the tests caught that review had not:

- A `Long.MIN_VALUE` sentinel for "never attempted" **overflowed** `now - lastAttemptAtMs`, so the
  throttle silently blocked the very *first* warm-up. Replaced with an explicit flag.
- `tryEnter` runs on the watchdog's handler thread while `leave` runs on the worker it spawned, so
  the state crosses threads — the field it replaced was `@Volatile` for that reason. Both methods are
  synchronized.

Worth adding regardless: a **stuck-rewarm** signal in the setup-health card. The status card added in
1.5.3 reports an empty recording; it cannot report "the daemon has been down for 21 hours", which is
the louder failure.

## Recovery, for anyone hitting this before the fix ships

```bash
adb shell am force-stop com.baba.callvault
adb shell am start -n com.baba.callvault/.MainActivity   # must relaunch: a force-stopped app gets no broadcasts
```

Verified on device: daemon back within ~25 s, socket `ESTABLISHED` on a fresh port, zero wedged
threads, stable on re-check. Non-destructive — no effect on ADB pairing or app data.

## What made this expensive to find

The app's log lives in app-private storage and the release build is not debuggable, so on a release
device the log is unreadable — `run-as` is refused and logcat is silent on this ROM. The entire
diagnosis had to come from `/proc/net/tcp`, `ps -T` thread names, and `dumpsys`. Every conclusion
above was reached *without* a single line of the app's own logging. See
`2026-07-28-daemon-and-system-logs-design.md`.
