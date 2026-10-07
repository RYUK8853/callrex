# Standalone Shizuku mode — audit

**2026-09-05.** Asked for after issue #28g, in the maintainer's words: *"when we re-introduced shizuku
into the app we were supposed to make sure it is working properly as a standalone mode including
everything around it so an issue like the usb picker shouldn't have even come up."* Patching each
symptom as a user reports it is the wrong shape; this is the sweep.

**Method.** Every path that reaches for the embedded ADB shell (`AdbShell`, `AdbConnectionManager`) or
gives ADB-flavoured advice was listed and checked for a mode gate, plus the boot, update and debug-report
paths, and the wizard. Plus one measurement on the OP9, which is what makes the section below concrete
rather than reasoned.

## The measurement everything else follows from

`svc usb setFunctions` was used to change the USB configuration on the OP9 (ColorOS 14) while watching
`shizuku_server` (pid 12409) and `adbd` (pid 5672):

- **adbd restarted** (new pid 18888).
- **`shizuku_server` was gone**, and did not come back.
- A plain detached shell script (`setsid`, ppid 1, shell uid) started beforehand **survived and ran to
  completion** — so this is not a blanket kill of shell-uid processes. It is specific to how Shizuku's
  server is hosted.

**Therefore: in Shizuku mode, anything that restarts adbd stops the user's recorder.** That is one rule
covering the USB configuration, the USB-debugging switch, and Wireless debugging alike. It is also why
the answer is never "grey it out and say unavailable" — the user can still do it from system Settings,
so the app has to say what it would *cost*.

## Findings

| # | area | verdict |
|---|---|---|
| 1 | Settings → USB debugging toggle | ❌ **was ungated** — fixed here |
| 2 | Settings → Default USB Configuration picker | ❌ was recommending it — fixed in `39136e2` (issue #28g) |
| 3 | Home reliability advisory + one-tap fix | ✅ unreachable in Shizuku mode (`1c37f1f`) |
| 4 | Setup prerequisites / Home status | ✅ mode-aware, and documented as such |
| 5 | "Recorder unavailable" notification | ✅ separate Shizuku wording |
| 6 | Onboarding wizard | ✅ the RELIABILITY step is not built in Shizuku mode |
| 7 | App-replaced receiver | ✅ restarts the user service, since `daemon(true)` survives the replace holding a dead APK path |
| 8 | Daemon keep-alive service | ✅ gated |
| 9 | Debug-report collection | ✅ skips the shell in Shizuku mode (fixed 2026-08-25) |
| 10 | `UsbDefaultConfig` read/write | ✅ refuses in Shizuku mode, and no longer caches an intent the device never got |

### 1 — The USB-debugging toggle was the same bug as the USB picker, one row above it

`SettingsScreen.UsbDebuggingToggle` was rendered unconditionally in the Reliability section. In Shizuku
mode it did two wrong things at once:

- **It recommended turning USB debugging ON** when off ("Recommended"), which is advice for the
  embedded-ADB backend and means nothing to a Shizuku user.
- **Turning it OFF would have stopped Shizuku**, by the mechanism measured above — while in that mode
  the app usually has no `WRITE_SECURE_SETTINGS` anyway, so the more likely outcome was a switch that
  simply failed.

Now locked in Shizuku mode with a hint that says why, rather than hidden: it still reports the true
state of the setting, and a control that quietly disappears teaches nobody anything.

## What this audit did NOT cover

- **One UI.** Everything measured here is ColorOS. The reporter's Samsung is consistent with it, but
  not confirmed.
- **A Shizuku recording end to end on a phone.** The OP9's Shizuku server was stopped by the very test
  above and could not be restarted from the host (no `start.sh` in its external files directory;
  invoking `moe.shizuku.server.Starter` over `app_process` aborts). It needs a tap in the Shizuku app.
- **The capture rules that already have their own notes** — one recorder host at a time, and a
  Shizuku-hosted process being unable to hold an `AudioRecord` in RECORDING state. Unchanged here.
