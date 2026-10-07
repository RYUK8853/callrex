# Issue #23 — stuck at "Call recorder starting up" on a Xiaomi (HyperOS, Android 17)

**🧪 VERIFYING — 2026-09-07.** What shipped is *diagnostics*, not a fix: discovery now says why it
dropped a service. The cause below is a **hypothesis with a matching field report**, not a measurement.
It is settled by one thing only — a fresh debug log from the reporter on a build that contains this
change.

Reporter: `deltaxsingh`, CallVault 2.2.0 (20200), Xiaomi 17 / HyperOS 3, **Android 17 (API 37)**.

## What the log established

From the exported header and lines:

- `Privileged mode: STANDALONE`, `Binder connected: false` — the daemon never started.
- `Wireless debugging: false`, `USB debugging: true`, `WRITE_SECURE_SETTINGS: true`,
  `WD plan: DROP_USB_KEEPS_ADBD`.
- `Offline recording (loopback): on`, and repeatedly
  `loopback tcpip :54240 unavailable (unarmed/refused)`, then `Loopback arm result on :54240 = false`.
- `Attempt 3/3: ADB not connected` → `ensureServerRunning gave up after 3 attempts`.

So there was **no reachable endpoint at all**: Wireless debugging off, loopback never armed. Arming the
loopback needs one successful Wireless-debugging connection, and that never happened — after which
every retry hits the same wall for good. That is the "stuck" the title describes.

Two of the reporter's own statements are contradicted by their log, and it matters: they answered "USB
debugging OFF, Wireless debugging ON" on the form and wrote "it is enabled and paired correctly", while
the export says the opposite for both. Ask what the switch says *at the moment it is stuck*, not from
memory. (CallVault also turns Wireless debugging off itself once a daemon is up, so "I turned it on"
and "it is off now" are not in conflict.)

## What the log could NOT establish, which is the defect we fixed

`AdbMdns.kt` contained **no logging at all** — `grep -c AppLogger` returned 0 — and its two `Log.w`
lines went to logcat only, which a debug export does not carry. So a failure to connect could not be
told apart from:

1. no adb service advertised at all,
2. a service found and then dropped by one of our own gates,
3. a service accepted and the connection refused.

On a phone we do not own, that is the difference between a diagnosis and a guess.

**Shipped:** `MdnsServiceVerdict` (a named reason per gate, unit-tested) plus `AppLogger` lines through
`AdbMdns` — discovery started, service found, resolve failed, each drop with its verdict, and "no
service accepted within Nms" on timeout. Also fixes an NPE: `resolvedService.host` can be null and
`.hostAddress` was read on it unguarded.

## The hypothesis this report matches

`AdbMdns` accepted a resolved service only if binding `127.0.0.1:<port>` **failed** — a failed bind
being the proof that `adbd` is listening. On a ROM whose `adbd` binds only the Wi-Fi address, that bind
**succeeds**, the real service reads as fake, and discovery reports nothing with no error shown.

This is exactly the mechanism recorded in `2026-09-02-callmonitor-fork-assessment.md` §2, whose open
question #1 was "do we have any field report that matches the Xiaomi shape?". **#23 is the first
candidate.** If a new log shows `NOTHING_LISTENING_ON_LOOPBACK`, the guess becomes a measurement and
the fix is ours to design — dial the resolved address rather than probe loopback — not to import.

## What the CallMonitor fork does and does not answer

The fork's newer HyperOS line (builds 15–21, `HyperOsWirelessDebugAccessibilityService.kt`) solves a
**different** wall: on their Redmi Note 12, in their words, "HyperOS denies WRITE_SECURE_SETTINGS even
to shell", so they drive the Settings UI with an opt-in, Settings-only accessibility service to flip
Wireless debugging back on after a reboot. **That does not fit #23** — this reporter's log shows
`WRITE_SECURE_SETTINGS: true`.

Their earlier Xiaomi commits (the loopback bridge) are the ones that match, and the assessment note
already records why the patch is not adoptable as-is (stale mDNS records start passing, proxies are
never closed, and it opens a local ADB endpoint the ROM deliberately did not have).

## Also true, and unrelated to the bug

The reporter is on Android 17. We target 36, so `NsdManager` still gets local-network access implicitly
via `INTERNET`. At `targetSdk 37` it needs `ACCESS_LOCAL_NETWORK` or discovery dies for everyone — see
`backlog.md`.

## What to ask the reporter

1. A fresh debug log from a build containing this change (the `CV:AdbMdns` lines are the point).
2. What the Wireless debugging switch shows *while* it is stuck.
3. Whether Developer options → "USB debugging (Security settings)" is on — HyperOS gates secure-setting
   writes behind it, and it needs a Xiaomi account.
