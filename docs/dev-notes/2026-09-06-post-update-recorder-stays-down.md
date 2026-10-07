# An update left the recorder unable to record the next call

> **✅ VERIFIED 2026-09-06.** Fixed in 2.3.0-rc6 (20334) and confirmed by the maintainer on the OP12:
> after the update, a carrier call and a WhatsApp call both recorded normally, with no app opened in
> between.

## What it cost

A **13-minute incoming call**, lost. The call immediately after it recorded perfectly, which is what
made it look mysterious rather than systematic.

## What happened, from the phone's own records

| time | event |
|---|---|
| 11:35:05 | an update was installed → Android stopped the app and **both foreground services**; the shell-uid daemon went with them |
| 11:35–11:42 | nothing brought them back |
| 11:42:59 | incoming call — the app *did* wake, because the `PHONE_STATE` receiver is static |
| 11:43:13 | `RecordingForegroundService` started, 14 s into the call |
| 11:54:45 | that service stopped, having written **nothing** — there was no daemon to capture through |
| 11:56:24 | app process and daemon finally reborn |
| 12:01:24 | next call recorded perfectly |

## Cause

`UpdatePackageReplacedReceiver.recoverAfterReplace` returned early whenever `WRITE_SECURE_SETTINGS`
survived the update. Its own comment said *"Skipped when the grant survived (in-app updater re-grants
inline)"* — the reasoning being that there was then nothing to heal.

**Healing the grant was only ever half the job.** A replace kills the privileged daemon and stops the
foreground services whatever happens to the grant. So on the common path — grant intact — the recorder
simply stayed down until something else happened to restart it.

This was never a lab-only problem: **the in-app updater installs updates the same way**, so any user
updating had the same unarmed window, and a call arriving inside it was lost exactly like this one.

## The fix, and why it needed two halves

The decision moved into `PostUpdateRecovery.plan()` so the invariant can be a test: **`ensureRecorder`
is true in every mode, for both grant outcomes.** An app replace must never leave the phone unable to
record the next call.

🚨 **The first half alone looked like it worked, and did not.** Restoring the daemon left the phone with
**no foreground service at all** — caught only by watching a real install and checking
`dumpsys activity services`. The keep-alive **hosts app-call detection**, so VoIP recording was dead
until the app was opened, and nothing was keeping the daemon warm against an idle reaping.

The keep-alive is therefore started **first, on the broadcast thread**. From Android 12 a backgrounded
app may start a foreground service only inside a short exemption window, which `MY_PACKAGE_REPLACED`
grants while the broadcast is being processed — and the daemon relaunch that follows took ~6 s when
measured, well outside it.

## How this was diagnosed after logcat had rotated

Worth keeping: none of it needed the app's own log.

- `content query --uri content://call_log/calls` — the phone's own record of the missed call (time,
  duration, direction), to line up against the recordings folder.
- `ps -A -o PID,ETIME` on the app and daemon — both born *after* the missed call ended.
- `dumpsys usagestats | grep <pkg>` — `FOREGROUND_SERVICE_STOP` at the install second and
  `FOREGROUND_SERVICE_START` 14 s into the call. This is what proved the app woke and tried.
- `dumpsys package <pkg> | grep lastUpdateTime` — tied it to the install.

## The operational rule that follows

**Installing on the maintainer's daily driver arms a window where the next call may not record.** The
audio-mode check before installing proves only that no call is happening *at that second*. After any
install: confirm the daemon **and** the keep-alive are back — `ps` for `RecorderServer` with its APK
path matching `pm path`, and `dumpsys activity services` showing the keep-alive foreground.
