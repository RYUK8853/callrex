# Shizuku support — what it would take, and what would actually work

Research only, 2026-07-30. **No code changed, and nothing here has been run.** Claims about
CallVault's own code are verified by reading this repository and are cited. Claims about Shizuku come
from its published API and its maintainer's statements — **none of them have been tested on a device**,
and that distinction is kept explicit throughout because the conclusion depends on it.

---

## The short version

Shizuku is a **much better fit than it looks**, because Shizuku's `UserService` and CallVault's daemon
are the same thing: our own code running in a separate process as **uid 2000 (shell)**. The
app-to-daemon boundary is already a single AIDL interface, so the change is mostly in *how the daemon
is started*, not in what it does.

But the headline reason people ask for it — reliability — **does not hold**. Shizuku's own server is
started over ADB and is therefore an `adbd` child exactly like our daemon, so every `adbd` restart
kills it too. Shizuku would stop CallVault *causing* that churn; it would not make either app survive
it.

---

## What CallVault's daemon actually needs

Verified from the source:

| Requirement | Where | Shizuku `UserService` provides it? |
|---|---|---|
| Runs as **uid 2000 (shell)** so `AudioRecord(VOICE_CALL)` is permitted | `RecorderServer.main` logs `uid=2000` | **Yes** — `UserService` processes run as shell |
| A `Looper` and a binder it can hand to the app | `RecorderServer.main`: `Looper.prepareMainLooper()`, `BinderDelivery.deliverBinderToApp` | **Yes** — Shizuku returns the binder to the app directly, so `BinderDelivery` becomes unnecessary |
| Register a dynamic `AudioPolicy` (hidden API, reflection) | `VoipAudioPolicy.arm()` → `registerAudioPolicyStatic` | **Yes** — same uid, same reflection |
| Pass an `IAudioRecord` binder + ashmem fd to the app | `HandoffSource.deliverToApp` | **Yes** — ordinary binder/fd passing |
| Spawn shell commands | `VoipAppIdentity` (`dumpsys audio`), `VoipCallerName` (`dumpsys notification --noredact`), `RecorderSession` (`pkill`, `app_process`) — all `ProcessBuilder` **from inside the daemon** | **Yes** — a shell-uid process can `ProcessBuilder("sh", "-c", …)` exactly as today |

That last row is the one that makes this tractable. The daemon already runs its own shell commands
because it *is* shell; it does not depend on the ADB connection to do so.

## The seam is already there

The app reaches the daemon only through `RecorderConnection.service` (an `IRecorderService`), from
**five files**: `AudioRecordingEngine`, `DaemonKeepAliveService`, `VoipCaptureController`,
`VoipRecordingCoordinator`, `TrackAProbe`.

Everything else — connecting ADB, arming loopback, toggling Wireless debugging, killing stale daemons,
`app_process` launch — exists purely to *get that binder into `RecorderConnection`*. Shizuku replaces
that entire apparatus with `Shizuku.bindUserService(...)`, which hands us the same binder.

So the shape is: **a `PrivilegedProvider` abstraction with two implementations** — today's ADB
launcher, and a Shizuku one — both producing an `IRecorderService`. The recording code above the seam
does not change at all.

## Feature-by-feature

| Feature | Under Shizuku | Notes |
|---|---|---|
| Carrier recording (direct `AudioRecord` path) | ✅ | Same uid, same permissions |
| VoIP recording (policy mix + mic) | ✅ | Including the 1.5.5 mic re-take |
| Resilient recording (handoff) | ✅ | Binder + fd hand-off is uid-agnostic |
| scrcpy fallback | ⚠️ | Needs a second process running the scrcpy jar. `Shizuku.newProcess` is hidden/restricted; alternatively host it inside the `UserService`. **Only matters for sources/codecs the direct path cannot handle** — see `DirectAudioRecorderSession.supports()` |
| In-app updater (`pm install -r -S`) | ⚠️ | Would need routing through the `UserService` rather than an ADB `exec:` stream |
| Screen-lock USB fix (`svc usb setScreenUnlockedFunctions`) | ✅ | Shell command from the `UserService`. **Still needed** — see the reliability section |
| `pm grant WRITE_SECURE_SETTINGS` | 🚫 not needed | It exists only to toggle Wireless debugging, which Shizuku makes unnecessary |
| Offline recording (loopback `tcpip`) | 🚫 not needed | **A security win**: this is the feature that opens an RSA-gated port on all interfaces |
| Hands-free operation after reboot | ❌ **lost** | See below. This is the significant regression |

## The reliability argument does not survive contact

The obvious pitch is "Shizuku's helper is more robust than ours". It is not, and this repo already
holds the evidence — `transport-and-daemon-architecture.md`, quoting Shizuku's maintainer:

> Any `adbd` stop or restart kills the daemon. Android's init calls `KillProcessGroup`, which walks the
> service's cgroup […] *"All processes started by adb… will be killed for sure… there is nothing we
> can do."*

Shizuku's server is started over ADB. It is in `adbd`'s cgroup. **A screen-off `adbd` restart kills
Shizuku's server, and with it any `UserService` we are hosting.** Switching to Shizuku moves the
daemon into someone else's process tree; it does not take it out of `adbd`'s.

What Shizuku *does* fix is the part that is our fault: CallVault would stop restarting `adbd` itself
(the `tcpip:` arm and the Wireless-debugging toggles), which is what kills Shizuku today.

**Corollary:** the USB "Charging only" fix stays just as necessary under Shizuku, because it addresses
`adbd` churn from USB gadget cycling, not from us.

## What is lost, and it is not small

**Hands-free after reboot.** CallVault's pairing survives a reboot: the loopback port clears, the app
re-arms it, and recording resumes without the user doing anything — the "post-reboot cold start" is
seconds, not a manual step. Shizuku (without root) **must be restarted by the user after every
reboot**, from a PC or via wireless-debugging pairing.

This is the project's stated differentiator ([[shizuku-scr-vs-callvault]]: *"CallVault wins 1-app +
hands-free reboot"*). A Shizuku-only CallVault would be strictly worse for the median user, who
reboots and expects their next call recorded.

**The one-app promise.** The README leads with self-contained operation and no companion app.

Both are reasons Shizuku must be **an option, never a replacement**.

## What it would take

Roughly, and in dependency order:

1. **Add the dependency** — `dev.rikka.shizuku:api` and `:provider`. Note F-Droid reproducibility
   ([[distribution-not-play-store]]): the artifacts must be acceptable to F-Droid's build.
2. **Extract a `PrivilegedProvider`** returning an `IRecorderService`, with `AdbProvider` wrapping
   today's `RecorderServerLauncher`. This is worth doing **even if Shizuku is never shipped** — it
   isolates the transport from the recording logic.
3. **Wrap `RecorderServer` as a `UserService`.** Its `main(args)` currently takes an apkPath and
   delivers its binder via a ContentProvider; a `UserService` is constructed by Shizuku and returns
   the binder directly, so this is a second, thinner entry point onto the same `IRecorderService.Stub`.
4. **Route the app's shell needs through the service** — `dumpsys usb`, `svc usb`, `pm install` —
   since there is no ADB connection to open a stream on. Either add methods to the AIDL or a single
   `exec(String): String`. The daemon-side commands need no change at all.
5. **Permission and lifecycle UI** — `Shizuku.pingBinder()`, `checkSelfPermission()`,
   `requestPermission()`, plus a wizard branch and a Settings row for choosing the provider.
6. **Decide the interaction with offline recording** — it should be disabled and hidden under
   Shizuku, since the open port exists only to serve our own ADB transport.

Steps 2 and 3 are the substance; the rest is plumbing and UI.

## Unverified — do not treat any of this as settled

- **Nothing Shizuku-side has been tested.** No device here has Shizuku installed. Every claim about
  `UserService`, uid, and API shape comes from documentation and general knowledge.
- Whether a `UserService` may register a **dynamic `AudioPolicy`** — same uid says yes, but the policy
  registration path is reflective and hidden, and Shizuku's process is created differently from
  `app_process`.
- Whether a **`UserService` survives the app process dying**, which Resilient recording depends on in
  the opposite direction (there, the *daemon* dies and the app survives).
- Whether `Shizuku.newProcess` is still usable for the **scrcpy fallback**, or whether it must be
  hosted in-process.
- Whether F-Droid will accept the Shizuku artifacts.
- **Whether either reporter actually wants this**, or simply wants the two apps to stop killing each
  other — which is a warning dialog, not an architecture (see
  `2026-07-30-voip-only-mode-and-shizuku-coexistence.md`).

## Recommendation

**Do the cheap thing first and see whether the expensive thing is still wanted.** The reported symptom
is "Shizuku crashes when CallVault is active", and the cause is CallVault restarting `adbd`. A warning
before the one destructive action addresses the complaint for a day's work.

**Then extract `PrivilegedProvider` regardless.** It pays for itself in testability and in separating
transport from recording, and it is the prerequisite for any Shizuku work later.

**Treat full Shizuku support as a considered product decision, not a fix.** It buys: no conflict, no
open port, no `adbd` churn from us, and no need for `WRITE_SECURE_SETTINGS`. It costs: a companion
app, a manual restart after every reboot, a second privileged path to maintain forever — and it does
**not** buy the reliability people assume, because Shizuku's helper dies to `adbd` exactly as ours
does.
