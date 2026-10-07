# Two user requests: VoIP-only recording, and coexisting with Shizuku

Investigation only — **no code changed**. Written 2026-07-30 from reading the source; nothing here has
been tested on a device except where it says so.

---

## 1. "Turn off cell recording" — a VoIP-only mode

### It already works. The capability exists; the presentation does not.

The two paths are gated **independently**:

| path | gate |
|---|---|
| Carrier calls | `CallSessionManager.shouldAutoRecord()` → `isAutoRecordIncomingEnabled()` / `isAutoRecordOutgoingEnabled()` |
| VoIP calls | `VoipRecordingCoordinator` → `isVoipRecordingEnabled()` **only** |

`VoipRecordingCoordinator` never consults the auto-record preferences. So **turning both auto-record
toggles off while leaving VoIP recording on already produces a VoIP-only recorder today.**

Checked and it holds up in the awkward corner too: `CallGapDetector.sweep` is passed
`autoRecordIncoming` / `autoRecordOutgoing`, so with them off the setup-health card will **not** report
unrecorded carrier calls as failures. A user in this mode is not nagged about calls they deliberately
chose not to record.

### So what is actually missing

Discoverability and naming — the same shape as issue #20, where the upload schedule worked from the
day it shipped but lived only in the wizard.

A user who wants "VoIP only" has to work out that it means *two* switches in Recording, both off, and
then trust that an app called CallVault showing "Automatically record incoming calls: off" is still
going to record something. Nothing anywhere says the combination is a supported mode.

### Options, cheapest first

1. **Document it.** A line in the README and in the wizard's auto-record step: "Only want app calls?
   Turn both of these off and enable VoIP recording." Zero code, and it is already true.
2. **Name it in the UI.** A "What should CallVault record?" row offering *Carrier calls*, *App (VoIP)
   calls*, or *Both* — writing the three existing preferences underneath. Purely a presentation layer
   over what exists; no capture logic changes. Best value for effort.
3. **A separate `recordingScope` preference.** Do NOT do this. It would duplicate state that the three
   existing flags already hold, and the two could disagree — the exact drift that produced the
   wizard/Settings bitrate bug (`AUDIO_BIT_RATE_OPTIONS`) and the schedule-picker split.

### Worth verifying before promising it

The daemon is still required for VoIP capture, so a VoIP-only user still needs the whole ADB setup.
Nothing about that changes, but the onboarding copy currently frames it in terms of *calls* generally.
Also unverified: whether `VoipCallDetector` behaves when the app has never recorded a carrier call —
it keys off `MODE_IN_COMMUNICATION`, which should be independent, but no one has run the app in this
configuration for a whole session.

---

## 2. Shizuku "crashes" when CallVault is active

### It is almost certainly not a crash. CallVault kills Shizuku's server.

Both apps run a privileged helper **started over ADB**, so both helpers are children of an `adbd`
shell and live in `adbd`'s cgroup. From `transport-and-daemon-architecture.md`, already established
here and quoting Shizuku's own maintainer:

> Any `adbd` stop or restart kills the daemon. Android's init calls `KillProcessGroup`, which walks
> the service's cgroup […] *"All processes started by adb… will be killed for sure… there is nothing
> we can do."*

That applies to **Shizuku's server exactly as it applies to ours.** Anything that restarts `adbd`
takes both down. Shizuku then reports itself as not running, which a user reasonably describes as a
crash.

### Where CallVault restarts adbd

| site | when | restarts adbd? |
|---|---|---|
| `armLoopbackIfNeeded` → `openStream("tcpip:<port>")` | enabling offline recording; re-arming after a reboot | **Yes, always** — this is the destructive one |
| `enableWirelessDebugging` → `adb_wifi_enabled = 1` | WD bootstrap when there is no other transport | **Yes** |
| `disableWirelessDebugging` → `adb_wifi_enabled = 0` | `applyWdPolicy`, after every daemon launch | **Only when USB debugging is off** — measured: with USB debugging on, adbd's pid is unchanged |
| `killStaleDaemons` | before each launch | **No** — `pgrep -f` is scoped to our own FQCN and cannot match Shizuku |

So the collision is concentrated in **two** places, not spread through the app. And the third row is
the reason it will look intermittent to users: on a phone with USB debugging enabled, CallVault's
routine WD toggling is harmless to Shizuku; on a phone without it, the same action kills it.

### Why it will feel random to the user

- Offline recording arms **once per boot**, so Shizuku dies once and then stays up — until the next
  reboot, when it happens again
- With USB debugging on, the frequent path (WD off after each launch) does not restart `adbd` at all
- Reboot ordering matters: whoever starts second kills the first

### What could be done, roughly in order of honesty

1. **Say so.** Detect Shizuku (`moe.shizuku.privileged.api` installed) and warn before the one
   genuinely destructive action: *"Enabling offline recording restarts Android's debug bridge, which
   will stop Shizuku. You'll need to start Shizuku again afterwards."* Cheap, truthful, and turns a
   mystery into an expected event. **This alone probably resolves both reports.**
2. **Do the destructive arming during onboarding**, before a user has anything else depending on
   `adbd`, and never again unless the port is genuinely gone.
3. **Prefer USB debugging** where it is already on, so the routine WD toggle stays in the
   pid-unchanged case. Largely true today via `WirelessDebuggingPolicy`; worth confirming it holds
   when Shizuku is the other transport's owner.
4. **Use Shizuku as our privileged provider when it is present** — the actual "integration" the users
   are asking for. Shizuku exposes a binder to a shell-uid process; if CallVault could run the
   recorder through it, we would not need our own daemon, our own ADB connection, or any `adbd` churn
   at all, and the conflict disappears by construction.

   This is a large architectural option, not a patch. It also *inverts* a design goal: CallVault's
   pitch is being self-contained with no companion app ([[shizuku-scr-vs-callvault]] — "CallVault wins
   1-app"). It would have to be an *optional* provider — use Shizuku if installed, own daemon
   otherwise — which means maintaining two privileged paths. Worth a proper design discussion before
   anyone starts.

### What to ask the reporters

The evidence would sharpen this a lot, and none of it needs a debug build:

- Is **USB debugging** on? That single answer predicts whether the frequent path is destructive.
- Is **offline recording** (no Wi-Fi) enabled in CallVault? That is the one action that always kills
  Shizuku.
- Does Shizuku stop **once** — around enabling offline recording or after a reboot — or repeatedly
  during normal use? Repeatedly would mean this analysis is incomplete.
- Does *CallVault* also stop working at the same moment? Both helpers die together, so "only Shizuku
  broke" would point somewhere else entirely.

---

## Also noted: the README is out of date after 1.5.5

Not part of either request, but found while checking:

- Screenshots (`Home | Setup Wizard | Settings`) predate the panel; the wizard also gained two steps
- **"Settings ▸ Experimental"** is referenced throughout — the path is now **Settings ▸ General ▸
  Experimental**
- The VoIP compatibility table does not know that Samsung/One UI works as of 1.5.5
- Screenshots were already stale before today (see [[spike-audio-handoff-status]]) — regenerating them
  means care about real contacts appearing in a public repo
