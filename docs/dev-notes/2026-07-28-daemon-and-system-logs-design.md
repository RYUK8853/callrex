# Getting the daemon's diagnostics into a bug report — design

**Date:** 2026-07-28
**Motivating case:** [issue #18](https://github.com/madkongo/CallVault/issues/18) — "recording works but playback nothing"

---

## The problem

A user reported carrier recordings that contain data but play back silent, and attached a full debug
log. The log was clean end to end: pipeline start, capture dispatch, clean stop, successful copy, no
error anywhere. It could not answer the question, because **everything that happens inside the recorder
daemon is absent from it**.

Two facts make that so:

- `AppLogger.init(context)` runs only in the app process (`CallVaultApplication.onCreate`). The daemon
  is a separate `app_process` running as shell uid; there `AppLogger` has no context, so `logInternal`
  returns early and only the `android.util.Log` call survives.
- The daemon is launched detached — `setsid sh -c '… exec app_process …' >/dev/null 2>&1 </dev/null &`
  (`RecorderServerLauncher`). Its stdout and stderr go nowhere by design: keeping the stream open let
  `adbd` kill the child before `app_process` finished starting.

So `RecorderServer` already logs which capture path it took (`Recording via DIRECT AudioRecord …` /
`Recording via scrcpy …`), and that line reaches nobody. For the bug classes most likely to produce
"records but plays nothing" — capture, encoding, muxing — the exported report shows only the app's half
of the conversation.

## Constraint that shapes everything

The daemon cannot write to the app's log. `app_debug.log` lives in `context.cacheDir`, owned by the app
uid and unreachable from a shell-uid process under SELinux. Any design that has the daemon write
directly into the app's file is a non-starter.

## The approach

**Extend the existing debug-logging toggle to capture the daemon and the system, with no daemon changes
at all.** The daemon already writes every line to logcat; the app already holds a privileged shell.

| When | What happens |
|---|---|
| Debug logging switched **on** | Grow the logcat ring via the shell (`logcat -G 8M`), remembering the previous size |
| User reproduces the bug | Nothing — the daemon's lines land in logcat as they always have |
| User taps **Share debug logs** | The app pulls a logcat slice over the shell, filters it to a tag whitelist, redacts it, and attaches it beside `app_debug.log` |
| Debug logging switched **off** | Restore the previous ring size |

### Why the ring has to grow

Measured on the OnePlus 12 (2026-07-28): `logcat -g` reports the main buffer at **256 KiB**, and at the
time of measurement the entire buffer contained **2** CallVault lines. On a busy phone that is minutes
of history, sometimes less. Without resizing, a user who reproduces a bug and then navigates to Settings
to share can outlive the evidence. `logcat -G` costs nothing while debug logging is off, and the change
is bounded by a reboot.

### Why no daemon changes

The only channel that could carry the daemon's own output is the `>/dev/null 2>&1` redirect in its
launch line, and that redirect is load-bearing for detachment. Changing how the daemon is launched in
order to improve its logging risks the daemon's survival — the wrong thing to trade.

### What this buys beyond our own lines

The same slice carries **AudioFlinger, AudioPolicy, SELinux denials, lowmemorykiller and tombstones**.
For issue #18 our daemon could at best have said "I captured N bytes"; the system layer is where the
answer to *why Samsung returned silence* actually lives. This is the larger half of the value.

## Rejected alternatives

- **Push log lines to the app over the binder.** Needs an AIDL change, buffering and ordering, and it
  delivers nothing in the case that matters most — a daemon that dies mid-call loses its final lines,
  which are the interesting ones.
- **Daemon writes its own rolling file** to a path both uids can reach. Durable, and it survives a
  failure that happened with logging off. But it needs the daemon to learn the toggle state, own a
  writer and a rotation policy, and it still cannot capture the system-side messages that answered
  nothing in our own logs. Deferred, not dismissed — see *When to revisit*.
- **Change the launch redirect** to capture stdout. Rejected on risk, as above.

## Design

### Enabling and restoring the ring size

`SettingsViewModel.setLoggingEnabled(enabled)` is the single existing entry point; both directions hang
off it.

- On enable: read the current size (`logcat -g`, parse the `main: ring buffer is N KiB` prefix), persist
  it, then `logcat -b main -G 8M`.
- On disable: read the persisted value and restore it; if absent, leave the buffer alone rather than
  guessing a default.
- Both are best-effort: no shell, no permission, or an unparseable response means we skip silently and
  the feature degrades to "whatever the ring already held". Logging must never block the toggle.
- Idempotent. Enabling twice must not stack, and a missing persisted value must never grow the ring
  permanently by accident.

### Building the slice

At share time, `AppLogger.buildShareableReport` gains a companion that produces a second file in the
same `cacheDir/logs/` directory the FileProvider already exposes.

1. `logcat -b main -b system -d -v threadtime` over the shell.
2. Keep a line only if its tag matches the whitelist:
   - `CV:*` — everything of ours, app and daemon alike
   - `AudioFlinger`, `AudioPolicyService`, `AudioPolicyManager`, `AudioRecord`, `AudioTrack`
   - `avc`/`SELinux` denials
   - `ActivityManager`, `lowmemorykiller`, `libc`, `DEBUG` — how and why our process or the daemon died
   - `adbd` — transport churn, which has cost recordings before
3. Redact every surviving line through `AppLogger`'s existing phone-number regex.
4. Cap the result (last ~4000 lines, hard byte ceiling) so the attachment stays sendable.
5. Write `callvault_system_report.txt` next to `callvault_debug_report.txt` and attach both.

### Privacy

This is the deliberate trade in the design. A raw logcat dump contains other applications' logs and
telephony lines carrying phone numbers, and these reports get posted publicly on GitHub. The whitelist
plus redaction means less context in exchange for not leaking a user's contacts into a public issue.
The header of the generated file states what was collected and what was filtered out, so the user can
see what they are about to share.

## Testing

- Tag filtering: whitelisted tags kept, everything else dropped, malformed lines dropped rather than
  passed through.
- Redaction: numbers in system-originated lines are redacted by the same rules as our own.
- Ring-size parsing: a well-formed `logcat -g` response, an OEM-mangled one, and an empty one — the
  latter two must yield "unknown" and leave the buffer untouched.
- Cap enforcement: a slice larger than the ceiling is truncated from the *oldest* end, keeping the most
  recent lines.
- On-device: enable the toggle, confirm `logcat -g` reports the larger ring, make a call, share, and
  confirm the attachment contains `CV:RecorderServer` lines — the exact lines issue #18 was missing.

## When to revisit

If field reports show users cannot reproduce on demand — the failure is rare and happens with logging
off — then the deferred durable daemon file becomes worth its complexity. The trigger is evidence, not
taste: two or three issues where "please turn logging on and reproduce" fails to produce anything.

## Out of scope

- Any change to how the daemon is launched, including its stdout/stderr redirect.
- A durable daemon-written log file (deferred above).
- Streaming daemon logs over the binder.
- Automatic log collection without the user enabling debug logging.
