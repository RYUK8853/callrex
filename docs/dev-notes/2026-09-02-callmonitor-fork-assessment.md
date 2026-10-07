# Assessment: the CallMonitor-Android fork's `callmonitor/core-v1` branch

**Status: 📐 CALCULATED — read from source and compared against our tree. Nothing adopted, nothing
built, nothing tested. The Xiaomi claim at the centre of it is UNVERIFIED by us.**

Date: 2026-09-02. Raised by the maintainer ("is there any value from this fork's branch?"), to be
discussed later. **No decision has been taken and no code has been changed.**

- Fork: <https://github.com/alekseykidisyuk/CallMonitor-Android>
- Branch: `callmonitor/core-v1`
- Relationship: a fork of **our** repo (`madkongo/CallVault`), created 2026-09-01, GPLv3 both sides.

---

## What is actually on the branch

It branched at `aa1cae53` — our 2.2.0 line — and adds **four commits**, three of them a single file
each. The fork's other two branches (`diag/loopback-oneui`, `diag/scrcpy-only`) are **our own old
branches**, carried along by the fork; they contain no third-party work.

| Commit | Subject | Files | Verdict |
|---|---|---|---|
| `264f0647` | `fix(xiaomi): bridge embedded ADB through local Wi-Fi address` | +`AdbLoopbackProxy.kt` (161) | 🟡 idea yes, patch no |
| `2580d31c` | `fix(xiaomi): route mDNS ADB endpoints through loopback bridge` | `AdbMdns.kt` +31/-24 | 🟡 one line worth taking |
| `0d6e7d7b` | `fix(ui): bound offline-recording shutdown and skip stale post-reboot disarm` | `OfflineRecording.kt` +31/-3 | ✅ partly worth taking |
| `36c9ae2d` | `ci: build CallMonitor Android fork with native dependencies` | +workflow (53) | ❌ nothing |

Net: **about one commit's worth of value, plus one idea worth recording.**

---

## 1. `0d6e7d7b` — bounding the offline-recording disable ✅ take the early return

### The part worth having

Three lines: return early from `OfflineRecording.disable()` when `AdbShell.isLoopbackArmed()` is
false.

After a reboot Android has already cleared `service.adb.tcp.port`, so **there is nothing to disarm**
— yet we currently still run `ensureConnected` in order to send `usb:` to a listener that does not
exist. It is the slowest possible no-op, and it sits on the path a user hits every time they toggle
the setting off after a restart.

`AdbShell.isLoopbackArmed()` already exists (`AdbShell.kt:145`, `internal`), so their patch does
compile against our tree unchanged.

### The part to leave

- **Their stated premise is overstated.** The commit message says the old code left "the Settings
  dialog spinning" and that "a wedged ADB socket must never wedge the UI". The spinner part is true;
  the UI part is not — `disable()` is already called on `Dispatchers.IO`
  (`OfflineRecordingDialog.kt:75`), so it never blocked the main thread.
- **Their 5 s abandon-on-timeout is the shape of a bug we have already fixed once.**
  `disarmLoopback` holds `heavyOperationLock`. Their worker thread is abandoned on timeout *while
  still holding it* — that is the lock-starvation pattern behind the `RewarmGate` wedge
  (`backlog.md` §"the daemon had been dead ~21 hours", 2026-07-30).
- **It is redundant with a bound we already have.** `disarmLoopback` already caps the `usb:` stream
  at `ARM_FIRE_CAP_MS` (`AdbShell.kt:441`). The genuinely unbounded call is `ensureConnected`
  *inside* it.

### How this relates to work we had already agreed

This is an independent, partial re-discovery of our own open backlog item:

> 🔵 `AdbShell.ensureConnected` is unbounded on the recording-start path — `backlog.md:1649`

Their patch fixes **one call site** and not the root. Bounding `ensureConnected` itself would fix
this call site *and* the recording-start path, which is the one that costs users a missed call. If
anything, this fork is an argument for finally doing the backlog item.

**Proposed disposition:** take the `isLoopbackArmed` early return; skip the thread-join; raise the
priority of the `ensureConnected` bound.

---

## 2. `264f0647` + `2580d31c` — the Xiaomi/HyperOS loopback bridge 🟡

### The hypothesis, which is sharp and worth keeping

`AdbMdns.isPortAvailable()` — inherited unchanged from Shizuku — confirms a discovered mDNS service
is a *genuine* adb daemon by attempting to bind `127.0.0.1:port` and **expecting the bind to fail**.
A failure means something is already listening, i.e. adbd.

On a ROM where adbd binds **only the WLAN address** and not `0.0.0.0`, that bind *succeeds*. We
therefore conclude "not a real service", never call `onPort`, and discovery silently reports nothing.

The user-visible result is pairing that never completes, **with no error shown**, on every device of
that OEM family. That is a plausible and specific explanation for a class of report we would
otherwise never diagnose, and it is the reason this branch is worth reading at all.

**We have no Xiaomi device and no evidence of our own. Their commits carry no device model and no
HyperOS version.** Treat as a hypothesis, not a finding.

### Why the patch should not be taken as-is

1. **Stale mDNS records now pass.** `AdbLoopbackProxy.ensure()` returns `true` as soon as the
   loopback bind succeeds — it *never verifies the target accepts*. A cached or stale record now
   yields a "successful" port report pointing at a bridge to nowhere. The bind-probe they deleted
   rejected exactly that case.
2. **Bind-probe replaced by connect-probe.** On a normal ROM every mDNS resolve now opens and
   immediately drops a TCP connection to adbd. Probably harmless; unmeasured.
3. **It creates a loopback ADB endpoint where the ROM deliberately had none**, reachable by any app
   on the device. ADB's TLS/RSA auth still gates it, so it is not a hole in itself — but this
   project's standing posture is that an open ADB endpoint is a decision that gets documented and
   put behind a warning (cf. the offline-recording opt-in), not something acquired as a side effect
   of a discovery fix.
4. **Resource lifetime.** Proxies are never closed — a bound port plus threads for the process
   lifetime — and the bridged sockets carry no read timeouts, so half-open connections leak threads.

### The free win to lift out regardless

```kotlin
val hostAddress = resolvedService.host?.hostAddress ?: return
```

Our current line (`AdbMdns.kt:83`) is `resolvedService.host.hostAddress`, which **NPEs if `host` is
null** — a state `resolveService` can produce. One-line robustness fix, entirely independent of the
Xiaomi question.

---

## 3. `36c9ae2d` — the CI workflow ❌ nothing to take

Builds a **debug** APK on `ubuntu-latest` with NDK 27 + CMake and uploads it as an artifact.

Our known CI problem is different and untouched by this: our release workflow's `SIGNING_KEYSTORE`
is a *different key* from the real release key, and it still names its artifact
`ShizuCallRecorder-<version>.apk` while the in-app updater only accepts an asset named exactly
`CallVault.apk` — so a release published by it would be invisible to every existing install
(`backlog.md`, "CI release workflow is broken"). A debug-build workflow addresses neither.

---

## Licensing

GPLv3 on both sides, and the fork is downstream of us, so porting changes back is clean. If we adopt
anything, credit `alekseykidisyuk/CallMonitor-Android` in the commit message. Note their new file
carries a "CallMonitor Android" header rather than our GPL header block — any adopted code needs our
standard header restored.

---

## Open questions for the discussion

1. Do we have any field report that matches the Xiaomi shape — **pairing that finds nothing and
   shows no error**? If so this hypothesis moves from interesting to urgent, and the fix is ours to
   design properly rather than to import.
   **ANSWERED 2026-09-07 — a candidate: issue #23** (Xiaomi 17 / HyperOS 3 / Android 17, stuck at
   "Call recorder starting up", no endpoint ever reached, no error shown). It is a *match in shape*,
   not a confirmation: the discovery path logged nothing, so the log cannot say which gate dropped the
   service. Diagnostics for exactly that shipped on `diag/mdns-discovery-logging`; a fresh log from the
   reporter settles it. See `2026-09-07-issue-23-xiaomi-discovery.md`.
2. Is `ensureConnected`'s unbounded connect finally worth doing now, given it has been rediscovered
   independently by a third party?
3. If we ever do bridge, does it go behind the same warning as offline recording?

---

## What was checked, so it is not re-checked

Verified against our tree rather than trusting the diffs:

- `AdbShell.isLoopbackArmed` exists at `AdbShell.kt:145` → their `OfflineRecording` patch compiles here.
- `disarmLoopback` at `AdbShell.kt:441` **already** has the `ARM_FIRE_CAP_MS` bound they present as new.
- `OfflineRecording.disable` is **already** off the main thread (`OfflineRecordingDialog.kt:75`).
- The fork's `diag/*` branches are ours, not theirs.

---

# Second pass — 2026-09-11 (builds 14 → 21)

**Status: 📐 CALCULATED — read from source and from the fork's patch scripts. Nothing adopted, built or
tested.** Asked by the maintainer: what else has the fork done, and can we pull any of it safely?

The fork (`callmonitor/core-v1`, `callmonitor/upload-v20`) added ~37 commits between 2026-09-02 and
09-07, all still based on our 2.2.0 line (`aa1cae5`, 152 commits behind 2.3.0). Their reference device is
a **Redmi Note 12 / HyperOS / Android 15** (`docs/ANDROID_DEVICE_TEST_PLAN.md`). Builds 16–21 are not in
their Kotlin source: CI runs Python scripts (`.github/scripts/build1x_*.py`) that rewrite source before
building. Running those scripts was blocked here, so their effect was read from the script text.

| Change | Commit / build | Verdict |
|---|---|---|
| Don't flag recovery as stuck while the recorder is connected | `6f70af3` | ✅ take — same rule we applied to the update banner in 1.4.4 |
| Turning off offline recording returns at once when nothing is armed | `0d6e7d7` (part) | ✅ take — still not in our tree; our `disable` now runs under the ADB lease |
| Retry post-boot recovery the moment Wi-Fi or Wireless debugging appears | `4aab39b` | 🟡 idea only |
| Accessibility service that drives Settings to switch Wireless debugging on | `e06497a`…, builds 14–18, 21 | ❌ not as-is |
| Stereo recordings (uplink/downlink kept as two channels, Opus ≥ 48 kbps) | build 19 | ❌ not by default |
| Upload queue to their server, branding, updater disabled, signer pinning, Windows installers | build 20 etc. | ❌ their product only |

**1. Stuck flag (`DaemonRecoveryPolicy.isStuck`).** Ours is `consecutiveFailures >= escalateAfterFailures`
alone, so a failure streak from before Wireless debugging was switched on keeps the red "not working"
state after the recorder has recovered. Theirs adds `&& !RecorderConnection.isConnected`. Adopt the rule,
but pass the connection state in rather than reading the singleton, so the policy stays unit-testable.

**2. Offline disable.** Take only the `isLoopbackArmed` early return, as the first pass already said; skip
their abandoned-thread timeout (lock starvation).

**3. Retry on Wi-Fi / WD.** Ours: `AdbConnectionService` makes one attempt and stops; the keep-alive
watchdog then retries every 60 s (`WATCHDOG_INTERVAL_MS`) and skips while off Wi-Fi. So the gain is up to
a minute after a late Wi-Fi association. Their version keeps the startup foreground service alive
indefinitely (`START_STICKY`, never stops on failure), which duplicates the keep-alive and its
notification. If wanted, the right shape is a Wi-Fi callback in `DaemonKeepAliveService` that triggers
`maybeRewarm` immediately.

**4. Accessibility service.** For phones that deny `WRITE_SECURE_SETTINGS`, it opens Settings (later Quick
Settings + Settings search, because HyperOS/Android 15 blocks background `startActivity`) and clicks the
Wireless debugging switch. Reasons not to take it:
- Six builds in one day to make it work; build 21's own notes say it fixed Settings opening *endlessly*
  with Wi-Fi off, and cap it at 3 attempts per boot, 45 s each, 5 min apart (`RecoveryUiPolicy`).
- Button and row labels are hard-coded in Russian and English only.
- Android 13+ "restricted settings" block enabling an accessibility service for apps installed from an
  APK until the user allows it in App info — every GitHub/Obtainium install.
- An app that clicks through system Settings by itself is a trust and review problem for a call recorder.
- It only matters where the grant is denied; issue #23's reporter has it granted.

**5. Stereo.** Reverses our field-verified mono rule. They avoid the far-side starvation by raising stereo
Opus to at least 48 kbps, so files roughly double. Their reason is server-side transcription with a
channel per speaker; our speaker labels already read the stereo capture inside the daemon.

## What the research added (not from the fork)

HyperOS/MIUI deny `WRITE_SECURE_SETTINGS` unless **Developer options ▸ USB debugging (Security settings)**
is on; without it `pm grant` silently does nothing or throws. Sources: PhoneProfilesPlus grant guide,
Easer issue #489 (Xiaomi 15, HyperOS). CallVault says nothing about this anywhere — no string, no README
line. A Xiaomi-specific hint where the grant fails is the cheap way to help the users the fork's
accessibility service was built for. Separately, Android 13+ restricted settings apply to any
accessibility idea we ever have.

Web search found no independent source for "HyperOS adbd listens only on the WLAN address" (the first
pass's loopback-bridge hypothesis); it still rests on the fork's claim and waits on issue #23's log.
