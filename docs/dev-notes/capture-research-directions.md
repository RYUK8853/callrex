# Capture & Persistence — Research Directions

**Status:** forward-looking investigation tracks. **Nothing here is proven or built.** These are the "what if" paths worth probing, each with a code-grounded read of *what it would let us replace* and the *open risks that gate it*.

Companion to `spike-audio-handoff.md` (which proved **Option B** — the app can hold a handed-off `IAudioRecord`+cblk and keep capturing after the daemon dies). That proof unlocks the tracks below. Read §7 of that doc first for the CREATE-vs-SUSTAIN framing this builds on.

**Devices:** primary OnePlus 12 / A16 / SDK 36 (`6011b07e`); secondary OnePlus 9 Pro / A14.

---

## Track A — Pre-created idle capture track (persistence via a permanently-held handoff)

**Status (2026-08-26): DECLINED by the maintainer. Do not re-propose.** The remaining experiment will
not be run. The objection is the product, not the engineering: holding a capture track outside a call
means the microphone is open when the user is not on a call, and a 24/7 mic indicator — attributed to
"Shell", not even to CallVault — is not something this app is willing to show. That is a fair reading
of what "held **started**" costs, and it is not worth trading for a debugging switch.

Note for anyone tempted to reopen on a technicality: the **stopped** hold avoids the wakelock and the
indicator, and the HAL finding below forces a `stop()`/`start()` at call-connect anyway. So a variant
without the 24/7 indicator does exist on paper. It was still declined, because it buys less than it
first appeared (it reduces but does not retire the off-Wi-Fi loopback opt-in) and it carries the
eviction and blind-failure risks below. **Take the decision as made unless the maintainer reopens it.**

The research below is kept because it is expensive knowledge about this device's audio stack — the HAL
use-case pinning in particular — and it stays true whether or not Track A is ever built.

**Original status (2026-07-26): RESEARCHED, ON HOLD. One experiment away from a decision.** The go/no-go
question was settled empirically on-device; the blocker then moved somewhere nobody expected. Read
this before re-opening — most of the original open risks are now closed, and the two that matter are
not the ones this section originally worried about.

### The idea

Option B proved the app can *hold* a live capture track after the daemon dies. Track A asks: what if
the app holds the track **from before the call even starts** — created once by a briefly-alive daemon,
handed off, then kept indefinitely? If an idle `VOICE_CALL` track simply begins yielding audio when a
call becomes active, **no daemon is needed at call start at all**, and the daemon collapses from a
per-call requirement to a once-per-boot "track factory".

### What a working Track A would replace

| Today's machinery | Track A effect |
|---|---|
| `ensureServerRunning` at ring (`RecordingForegroundService`) and at record start (`AudioRecordingEngine`) | gone at call time — the app already holds the track |
| `DaemonKeepAliveService` (persistent FGS + 60 s watchdog) | collapses to "create once per boot" |
| Cold start (`ensureServerRunning` budgets 24 s; `AdbShell.ensureConnected` can still hang ~75 s) | gone except the single post-boot creation |
| WD enable/disable churn — the primary self-inflicted daemon killer | minimised to once per boot |
| Loopback off-Wi-Fi opt-in and its `0.0.0.0` listener | **reduced, NOT retired** — see "the payoff is smaller than it first looked" |

### VERIFIED — creation with no call active WORKS

Probed on the OnePlus 12 / A16 with a shell-uid `AudioRecord(VOICE_CALL)` and no call in progress:
creation **succeeds**, routes to `AUDIO_DEVICE_IN_TELEPHONY_RX`, is reported `silenced:false`, and
survived 240 s / ~1875 reads untouched. Input availability is a static `attachedDevices` property of
the device's `audio_policy_configuration.xml`, not something gated on telephony state.

So the question this section was originally gated on — "can you even pre-create it?" — is answered YES.

### VERIFIED — but the pre-created stream is SILENT for the whole call

The blocker is the vendor HAL, not the framework. `voice_check_and_set_incall_rec_usecase()` runs
**once per `start_input_stream()`**. A stream started before the call is pinned to
`USECASE_AUDIO_RECORD_AFE_PROXY` and stays there — so it keeps delivering silence for the entire call,
while looking perfectly healthy from every angle the app can see.

**Candidate fix, UNTESTED:** `stop()` then `start()` on the existing track at call-connect, forcing
RecordThread standby → HAL restart → use-case re-evaluation. This preserves the whole point of Track A
because **permission is checked at creation, not at start** — so the app can do it with no daemon and
no ADB.

### VERIFIED — risks that are now CLOSED

- **No idle timeout, no reaper.** `RecordThread` standbys purely on `mActiveTracks.size() == 0`; there
  is no aging mechanism for record tracks anywhere in AudioFlinger. A held track is not timed out.
- **Entering a call does not invalidate inputs.** `AudioPolicyManager::setPhoneState` touches outputs
  only — no `closeInput`, no `updateInputRouting`.
- **Shell UID is immune to proc-state silencing.** `isServiceUid(uid) → uid < AID_APP_START` makes
  `UidPolicy::getUidState()` return `PROCESS_STATE_TOP` permanently for uid 2000. This is why the
  ~60 s-after-screen-lock silencing that several open-source projects report does not apply to us.
  Confirmed on-device that `com.android.shell` also holds `CAPTURE_AUDIO_OUTPUT`, which clears the
  separate in-call gate.
- **It does not fight other apps.** `AUDIO_SOURCE_VOICE_CALL` is a *virtual source*
  (`AudioPolicyService::isVirtualSource`), which excludes it from the concurrent-capture arbitration
  entirely: it can neither silence nor be silenced by the user's voice recorder, Assistant, camera,
  WhatsApp or VoIP. Those all keep working, and so does the tap.

### VERIFIED — the risks that actually matter now

**1. Eviction.** The protection rule in `getInputForDevice()` is `active && !IDLE`. So:

| Hold strategy | Preemption | Idle cost |
|---|---|---|
| held **started** | protected (shell uid is permanently TOP) | permanent `PARTIAL_WAKE_LOCK "AudioIn"` — the device never deep-sleeps — plus a 24/7 mic indicator attributed to **"Shell"**, not CallVault |
| held **stopped** | vulnerable — any app opening the same profile closes it | free: HAL standby, no wakelock, no indicator |

On this device the trade-off is sharp: `dumpsys media.audio_policy` shows `Telephony Rx` sharing an
input profile with the built-in/back/headset/SCO mics at `maxOpenCount: 2`, and `VOICE_CALL` has
**source priority 0, the lowest there is** — so it is first to be closed when that profile fills.

Given the HAL finding forces a stop/start at call-connect anyway, **stopped** is probably the right
choice: it avoids the wakelock and the indicator, and the start you must do anyway re-arms the
use-case. The cost is accepting eviction, which requires detection.

**2. Blind failure.** A holder of a bare binder + cblk cannot self-heal: `restoreRecord_l` is
client-side logic inside the `AudioRecord` object, which lives in the daemon, and
`EVENT_NEW_IAUDIORECORD` is never dispatched on the record path. Mitigated in the shipped code by the
`CBLK_INVALID` + stalled-ring detectors in `nativeDrainToPipe`, which make the death visible rather
than silent — but they cannot recover it.

### The payoff is smaller than this section originally claimed

The original text said Track A could **retire** loopback. It cannot. The held track can be evicted, and
re-creating it needs the daemon and therefore ADB — off Wi-Fi, that means loopback. The honest claim is
that the ADB path becomes a **rarely-used recovery mechanism instead of a per-call dependency**. Still
a large win; not a deletion.

### No prior art — anywhere

Surveyed BCR, ShizuCallRecorder, cally, Ever Call Recorder, ACR/ACR Phone, Cube ACR, Boldbeast,
Skvalex, Truecaller, Automatic Call Recorder, Shizuku itself, and LSPosed/Magisk modules: **not one
pre-arms a capture track.** The field solves "no privileged work at call time" either by making the
*permission* permanent (root/Magisk/Xposed) or by keeping a *process* warm (Shizuku-class).
ShizuCallRecorder's `ACTION_STANDBY` at RINGING is the closest, and it starts a *process*.

The only real precedent in any category is **WebRTC/LiveKit `prewarmRecording()`**, which starts an
`AudioRecord` early and hands the already-running instance to the consumer — Option B's pattern, but
within one process and seconds ahead, never across a process boundary or across calls. Their caveat
transfers: prewarming freezes format and effects at arm time, before the call's route is known.

No XDA thread, GitHub issue or writeup describes anyone holding a capture track across sessions. The
absence is real, not a gap in searching. **You would be first — with nobody's scar tissue to learn
from.**

### The one experiment that decides it

Hold a pre-created `VOICE_CALL` track across a real call-connect, `stop()`/`start()` it at off-hook,
and confirm **real audio rather than silence**. Everything else is understood. Check with
`AudioRecordingConfiguration.isClientSilenced()` (API 30+, registered before `startRecording`) plus an
RMS floor — **never** by checking whether the ring advances, because a silenced track advances frames,
timestamps and the ring at full rate while delivering zeros.

## Track B — VoIP call capture

**Status (2026-07-26): SOLVED ON-DEVICE, BOTH DIRECTIONS, NON-ROOT.** A complete two-sided WhatsApp
call was recorded as shell uid 2000 on the OnePlus 12 / Android 16 — far party and near party, in one
Opus file, at 48 kHz, with neither side of the call disturbed. Working probes:
`spike-tools/VoipProbe.java` (downlink), `VoipUplinkProbe.java` (source comparison),
`VoipCallRecorder.java` (the complete recorder).

### What works — the dynamic Audio Policy path

NOT `AudioPlaybackCapture` (needs MediaProjection consent) and NOT `REMOTE_SUBMIX`. The mechanism is a
**dynamic audio policy**: register an `AudioMix` whose `AudioMixingRule` matches playback with
`USAGE_VOICE_COMMUNICATION`, routed `ROUTE_FLAG_LOOP_BACK_RENDER`, then read
`AudioPolicy.createAudioRecordSink(mix)`.

```
AudioMixingRule: MIX_ROLE_PLAYERS + RULE_MATCH_ATTRIBUTE_USAGE(USAGE_VOICE_COMMUNICATION)
AudioMix:        ROUTE_FLAG_LOOP_BACK_RENDER, format 48 kHz stereo (OUT channel mask!)
register:        AudioManager.registerAudioPolicyStatic(policy)   // static, needs no Context
read:            policy.createAudioRecordSink(mix)                // an ordinary AudioRecord
```

### Measured result (real WhatsApp call)

| Window | Level | Meaning |
|---|---|---|
| before the call | **−91.0 dB** | digital silence (true floor) |
| ringing | −16.3 dB | ring tone captured |
| **far party speaking** | **−19.6 dB mean, 0.0 dB peak** | **downlink captured** |
| user speaking | −58.6 dB | not captured — uplink is a separate stream |
| after hang-up | −87.3 dB | silence |

The **39 dB gap** between the far party's window and the user's own is what proves this is a digital
tap and not acoustic bleed: a microphone would have made the user — inches away — the LOUDEST thing in
the file. Instead they are the quietest.

### ⚠️ THE ORDERING RULE — the thing that actually blocks people

**The policy must be registered BEFORE the call's audio track is created.** Android fixes a track's
output routing (including which secondary mixes it feeds) at track-creation time; registering mid-call
leaves the already-running call audio permanently unattached to the mix.

First attempt, registered mid-call: 28 s of noise floor (peaks 0-2, ≈ −120 dB), then a burst only when
a NEW stream started. Second attempt, registered first: ringing and the far party captured cleanly.
Same code, same call type — only the ordering differed. Production must arm the policy on call
detection, before audio starts.

### Four obstacles, all solved (do not re-derive)

1. **`Looper.prepareMainLooper()` is required** or the app_process is killed outright.
2. **The mix format takes OUT channel masks** (`CHANNEL_OUT_STEREO`), not IN —
   `createAudioRecordSink` converts them itself via `inChannelMaskFromOutChannelMask`. Passing IN masks
   yields an uninitialised `AudioRecord`.
3. **Do NOT call `ActivityThread.systemMain()`.** It sets the process attribution to packageName
   `android` while the uid is 2000, and AudioFlinger rejects the mismatch:
   `EX_SECURITY … createFromTrustedUidNoPackage: invalid attr`. A trusted uid with NO package is
   exactly what the native validator accepts. Neither a `ContextWrapper` overriding `getPackageName`
   nor `createPackageContext("com.android.shell")` fixes it — the attribution is process-global.
4. **Register with `AudioManager.registerAudioPolicyStatic(policy)`** and build the policy with a
   **null Context** (`getAttributionSource(null)` → `myAttributionSource()`). This is what avoids
   needing a Context at all, and therefore avoids (3).

### Quality: 48 kHz stereo, not the 16 kHz mono everyone else uses

Every published example caps at 16 kHz mono. That cap comes from
`AudioMix.canBeUsedForPrivilegedMediaCapture()`, which is only checked when
`allowPrivilegedPlaybackCapture(true)` is set — and that flag is **not needed** on the voice-comm path,
whose gate is the `CAPTURE_VOICE_COMMUNICATION_OUTPUT` permission. Drop the flag and 48 kHz stereo
registers fine. True fidelity is still bounded by the call codec, but nothing is artificially capped.

### REMOTE_SUBMIX is DEAD for this — measured, not theorised

Capturing `REMOTE_SUBMIX` **reroutes** playback instead of duplicating it: device audio goes silent.
Confirmed on-device (music went quiet during capture, restored after). `ROUTE_FLAG_LOOP_BACK` makes the
mix the *primary* output; only `LOOP_BACK_RENDER` keeps the normal output and adds a secondary tap —
verified on-device (music kept playing throughout). This is why every previous attempt at VoIP capture
via submix was correctly abandoned as unusable.

### Permissions (all verified granted on the OnePlus 12 / A16)

`CAPTURE_VOICE_COMMUNICATION_OUTPUT` (the real gate), `MODIFY_AUDIO_ROUTING`, `CAPTURE_AUDIO_OUTPUT`,
`CAPTURE_MEDIA_OUTPUT`, `CALL_AUDIO_INTERCEPTION`, `MANAGE_AUDIO_POLICY`. Note the builder's
`.voiceCommunicationCaptureAllowed(true)` is **advisory only** — `AudioService.isPolicyRegisterAllowed`
overwrites it from the permission check.

### No prior art — non-root, this appears to be first

Surveyed BCR, ShizuCallRecorder, cally, ACR/ACR Phone, Cube ACR, Boldbeast, Skvalex, Truecaller,
Automatic Call Recorder, Shizuku, LSPosed/Magisk modules. None capture VoIP downlink. scrcpy ships
playback capture but matches **only `USAGE_MEDIA`** (and calls `voiceCommunicationCaptureAllowed` after
`build()`, making it a no-op), so it never captures calls; its maintainer reported he could not capture
call audio, and the voice-comm PR (#6906) is open and unreviewed. The one working VoIP implementation,
**Mufanc/Friston-3**, is a **root** Magisk module that patches `audioserver`'s
`AudioPolicyService::setAppState_l` to stop its client being silenced.

**Why we may not need that patch:** shell uid 2000 satisfies `isServiceUid(uid) → uid < AID_APP_START`,
so `UidPolicy::getUidState()` returns `PROCESS_STATE_TOP` permanently — which is precisely the state
Friston-3 patches the binary to fake. What they need root for, we appear to get from being shell.

#### What the "records all VoIP" apps actually do (investigated 2026-07-26)

Prompted by *Call Recorder: Talker ACR Plus*, which advertises recording "virtually any VoIP
conversation" — WhatsApp, Signal, Telegram, Viber, Zoom and more. It does not do what this feature
does, and the vendors say so themselves.

**Confirmed, from the vendors' own documentation:**

- **The far party is captured through the MICROPHONE, not the stream.** [Cube ACR's FAQ](https://cubeacr.app/faq.html)
  instructs users to select **"voice recognition (software)"** on Android 10–14 — i.e.
  `AudioSource.VOICE_RECOGNITION`, an ordinary mic source.
- **[Boldbeast's troubleshooting page](https://www.boldbeast.com/android/call_recorder_troubleshooting.html)
  states it outright:** without speakerphone "the other party's voice in the recording is very weak
  almost inaudible", and turning the loudspeaker on lets the mic "record the call in both sides" —
  adding that only **rooting** can "record calls perfectly without switching on the loudspeaker".
- **NLL Apps (ACR Phone):** recording "may require turning on the loud speaker on others", and "might
  be one sided on phones that do not have a Qualcomm chipset".
- **Talker's own Play listing:** "Not all Android devices support the recording of VoIP calls", and
  buying premium "will NOT improve the quality of recorded calls". Its developer, replying to a review:
  **"Bluetooth has not been supported since Android 9."**
- **Accessibility is not capturing audio** — it has no audio API. Cube ACR's FAQ says its connector
  exists for correct **contact-name labelling**. It detects the call; a separate `AudioRecord` records.
- **Their "VoIP recording" is the same mic path**, surfaced through `ConnectionService` so the OS treats
  a VoIP call like a native one — with the same chipset- and speakerphone-dependent failure mode.
- **Boldbeast's actual VoIP recorder is root-only**, shipped as a
  [Magisk module](https://github.com/boldbeastsoft/CallRecordingFix). That is the one genuine internal
  tap found anywhere in the survey, and it needs root — which confirms the boundary rather than
  breaking it.

**The framework fact underneath all of it:** `AudioPlaybackCapture` — the only playback-capture API a
normal app can reach — accepts **only** `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN`
([docs](https://developer.android.com/media/platform/av-capture)). VoIP audio is
`USAGE_VOICE_COMMUNICATION`; "All other usages CAN NOT be captured." Google's own WebRTC Android
reference hardcodes `USAGE_VOICE_COMMUNICATION` for the remote track, and Signal/WhatsApp/Telegram all
build on that stack. **No non-root bypass of this exclusion is documented anywhere.**

**The tell that separates the two approaches:** their capture breaks on **Bluetooth** and weakens
without **speakerphone**. A digital tap cannot care about the output route. Ours was tested across
speaker → wireless headphones mid-call with no effect, and measured the far channel at **−81.9 dB**
(digital silence) while only the near side spoke. That is two independent digital streams, not a
microphone hearing a room.

**Inference, flagged as such:** where the mic path *does* work without speakerphone on some devices,
the likely cause is an OEM audio HAL mixing downlink into the `MIC`/`VOICE_RECOGNITION` path. Vendors
describe the symptom consistently (works on some phones, one-sided on others) but **none publishes the
mechanism**, and no engineering-level source was found. Treat the causal claim as unproven; the
concrete evidence is chipset-level (Qualcomm vs not) and policy-layer (Xiaomi ships custom
`getRecordSilenced` logic silencing a second `VOICE_DOWNLINK` client).

**Consequence for distribution:** even Talker could not put its mechanism on Google Play. Since the
[May 2022 policy](https://www.androidpolice.com/google-ends-call-recording-apps-accessibility-services/),
"the Accessibility API is not designed and cannot be requested for remote call audio recording", so the
Play app is a shell and the capability lives in a **sideloaded** "Talker ACR Helper" (their
[install guide](https://talkeracr.app/installation-guide.html) lists Galaxy Store / AppGallery / Aptoide
/ Amazon / direct APK — explicitly not Play) requiring an accessibility grant and, on Android 13+,
"Allow restricted settings". See [`distribution-not-play-store`] — this is corroboration, not a reason
to revisit Play.

### UPLINK — solved: the SOURCE is the whole trick

The near side cannot be captured with `VOICE_COMMUNICATION`: it is **zero-filled for the entire call**,
because a record client that cannot bypass the in-call policy is silenced —

```cpp
!(isInCall && !canCaptureCall) && !(isInCommunication && !canCaptureCommunication)
```

WhatsApp puts the device in `MODE_IN_COMMUNICATION`, and `VOICE_COMMUNICATION` is the very source the
VoIP app itself is using, so we lose that contest. Note this is a *different* mechanism from the one
protecting the downlink: `USAGE_VOICE_COMMUNICATION` **playback** capture is a virtual source and sits
outside arbitration entirely; microphone capture does not.

**Plain `MIC` is not silenced.** Measured during one live call, downlink running throughout as a live
control:

| Uplink variant | Result |
|---|---|
| `VOICE_COMMUNICATION` plain | SILENCED (rms 0.0) |
| **`MIC` plain** | **AUDIO — rms 1372, peak 11384** |
| `VOICE_COMMUNICATION` + `setForCallRedirection()` | SILENCED (rms 0.0) |
| **`MIC` + `setForCallRedirection()`** | **AUDIO — rms 1595, peak 11793** |
| shell-package attribution via the private ctor | CREATE FAILED |

Call redirection made no difference either way — the source alone decides it. `CALL_AUDIO_INTERCEPTION`
is therefore not needed for this.

### The complete recording

`VoipCallRecorder.java` runs both and writes ONE interleaved stereo stream, **LEFT = near, RIGHT =
far** — the same channel layout the shipped `VOICE_CALL` path already produces, so `HandoffEncoder`
consumes it unchanged.

Measured on a real 40 s WhatsApp call:

| | LEFT (near mic) | RIGHT (far party) |
|---|---|---|
| far party talking (5–17 s) | −35.6 dB | **−20.8 dB** |
| near party talking (21–32 s) | **−18.6 dB** | **−81.9 dB** |

The **−81.9 dB** is the proof: while only the near party spoke, the far channel was at digital silence.
These are two independent digital streams, not one microphone hearing the room. The −35.6 dB on the
near channel during far-party speech is earpiece bleed, ~15 dB below their real level and harmless
after downmix.

**Sync needs no correction.** Two free-running `AudioRecord`s stayed aligned for 40 s with **0 of 2000
chunks silence-filled**. The muxer pairs one 20 ms chunk from each and substitutes silence for whichever
side has nothing ready, so a stalled or silenced side costs its own channel and never the timeline.

**Neither side of the call was disturbed** — confirmed by both participants across three test calls.

### SHIPPED (behind the Experimental opt-in) — how it is wired

Working end-to-end in the app as of 2026-07-26, verified through the real feature (not probes) on
**WhatsApp, Telegram and Signal**: the call is detected, recorded both directions, finalised, listed in
the app and plays back correctly, with clean teardown at hang-up.

| Piece | Role |
|---|---|
| `server/VoipAudioPolicy` | arms/disarms the loopback mix (daemon-side; needs the shell permissions) |
| `server/VoipCaptureSession` | far sink + `MIC` → stereo → mono → codec → SAF fd; implements `RecordingSession` |
| `services/recording/VoipCaptureController` | arms on toggle **and on every daemon restart** |
| `services/recording/VoipCallDetector` | detects calls; hosted by `DaemonKeepAliveService` |
| `services/recording/VoipRecordingCoordinator` | creates the file, drives the daemon, catalogues the result |

**Detect on the AUDIO MODE, not on playback attributes.** The obvious approach — watching for a
`USAGE_VOICE_COMMUNICATION` playback config — CANNOT work from the app: the framework hands out
**anonymised** `AudioPlaybackConfiguration`s to any caller without `MODIFY_AUDIO_ROUTING`, which only
the shell daemon holds. The usage is never visible, so the condition never becomes true, on any device.
Use `AudioManager.getMode() == MODE_IN_COMMUNICATION`: no permission, not redacted, and carrier calls
use `MODE_IN_CALL` so the two paths cannot collide. End-detection is debounced ~1.5 s because the mode
wobbles on route changes.

**The Home list is catalog-backed, not a folder scan.** A recording written straight into the SAF
folder exists on disk and is invisible in the app. `RecordingCatalog.recordLocal` (+ `StorageRouter`)
must be called explicitly — the carrier path gets this from `RecordingForegroundService`, which the
VoIP path deliberately does not use.

**Feedback:** the VoIP path does not run `RecordingForegroundService`, so it has no notification of its
own. `DaemonKeepAliveService`'s permanent notification switches to "Recording VoIP call" instead of
adding a second one — which doubles as the field diagnostic for whether detection fired.

Verified on-device: clean teardown at hang-up (mode returns to NORMAL, zero open output fds), correct
duration, and the recording appears and plays in the app.

### Identifying WHICH app is on the call — from audio, never from notifications

Scanning notifications for one tagged `category=call` is **wrong and was replaced**. It produced two
separate field bugs: a Telegram call filed under WhatsApp, then the same call filed under **Google**.
Both because the search ranged over every app's notifications and took the first match.

The correct source is the audio system itself. The app is identified from the uid on the
`USAGE_VOICE_COMMUNICATION` stream — *the very stream this feature records* — so it cannot name the
wrong app. `VoipAppIdentity` tries three sources, strongest first:

1. `IAudioService.getActivePlaybackConfigurations()` → `getClientUid()` (structured, via reflection).
2. **`mAudioModeOwner` from `dumpsys audio`** — the app that requested `MODE_IN_COMMUNICATION`.
3. The started `USAGE_VOICE_COMMUNICATION` player line in the same dump.

**Source 2 is not redundant — it is load-bearing.** Measured on-device: during a Telegram call the
playback track did **not yet exist** when the mode changed (`modeOwner=10304 player=-1`), while during
a Signal call it did (`binder=10414`). Detection fires on the mode change, so the mode owner is the
only source guaranteed to be present at that instant. The lookup also retries for up to 1.2 s.

Only the daemon can do this: `getActivePlaybackConfigurations` is **anonymised** for callers without
`MODIFY_AUDIO_ROUTING`. The daemon returns a **uid**, not a package — it holds no `Context`, and
`PackageManager.getPackagesForUid` on the app side handles shared uids and work profiles correctly.

### The contact's name — notification only, and that is a real boundary

For *who* was on the call there genuinely is no privileged source, confirmed by elimination on-device
during live calls: Telecom lists **no call** (these apps register no connection), there is no media
session, and the app's own call history is in a private data dir unreadable without root. The
ongoing-call notification is the only place the system holds the name.

Consequences, all hit for real:

- **Read `android.title` AND `android.text`.** WhatsApp puts the person in the title; **Telegram puts
  the status line "Ongoing Telegram call" in the title and the person in `android.text`.** Reject a
  candidate that merely restates the app, then fall through to the next.
- **Do not filter on `category`.** Telegram's call notification sets **no category at all**. Scoping to
  the already-known package makes that filter unnecessary; `ONGOING_EVENT` separates a call from a chat.
- **An app denied `POST_NOTIFICATIONS` yields a correct but nameless recording.** Diagnosed on Signal:
  `POST_NOTIFICATIONS granted=false` → no record to read. Granting it made the name appear immediately.
  This is surfaced in Settings so it does not read as a CallVault bug.

### Filename grammar — `{date}_voip-{App}[_{caller}]`

The app rides on the marker, not in its own underscore slot. With both in underscore slots a call with
an app and no caller (`_voip_Signal`) is indistinguishable from a caller with no app, and Signal calls
silently lost their app badge because the lone token was read as the contact. Legacy `_voip_{App}_{caller}`
names are still parsed for files already on disk. Pinned by `VoipFileNameParsingTest`.

`<queries>` (LAUNCHER) is **required** or `getPackagesForUid`/`getApplicationIcon` see nothing and every
badge falls back to a generic glyph. Badges use the **installed app's own icon**, never bundled artwork.

### Open — what is NOT proven

1. **Carrier VoWiFi / VoLTE is out of reach by this route and always will be** — carrier downlink is a
   hardware telephony bridge (`AUDIO_DEVICE_IN_TELEPHONY_RX`), not a software `AudioTrack`, so no
   PLAYERS mix can match it. The originating ShizuCallRecorder complaint (Wi-Fi calling) is NOT solved.
2. **One device, one Android version.** WhatsApp, Telegram and Signal are all verified working and all
   set only `flags=0x800` (`FLAG_NO_MEDIA_PROJECTION`, bypassable) — none sets the unbypassable
   `FLAG_NO_SYSTEM_CAPTURE = 0x1000`. Still check the flag per app at runtime rather than promising
   "records all VoIP".
3. Registering mid-call may cause a brief audible glitch as tracks are re-evaluated.
4. Long-call drift beyond 40 s is unmeasured, though the chunk-pairing muxer degrades safely.
5. VoIP user-facing strings are English-only (no locale files yet).

## Track C — Toward a native install (kill the Developer-Options / Wireless-Debugging / USB ceremony)

**The dream:** no developer options, no wireless-debugging pairing, no USB — the app is *install + grant permissions*, like any native app.

### The hard wall (state it first, so we don't chase ghosts)

On **stock, non-root** Android, privileged call-audio capture (`VOICE_CALL` / `CAPTURE_AUDIO_OUTPUT`) requires a **privileged process** (shell-uid or system). The **only non-root vector to spawn one is ADB.** There is **no app-grantable permission, no consent dialog, and no default-app role — including default dialer — that gives an app-uid process `VOICE_CALL` capture.** Proven twice in our own code:
- The Option B spike: the app process can't even `dlopen` the audio libs; the permission is uid-checked at `AudioFlinger::createRecord`, not appop-grantable to an app uid.
- The dialer work (`dialer-mode-status` memory): even as the **forced default dialer with `InCallService` bound**, recording still rode the ADB daemon — `DialerDefaultEnforcer.enforce()` must call `AdbShell.ensureConnected()`. Being the dialer gives call *control/state*, not call *audio*.

So **full both-sides cellular capture with zero ADB is not achievable on stock non-root.** Every credible non-root recorder (SCR, etc.) hits this exact wall. Don't promise otherwise.

### Vectors investigated — honest verdicts (don't re-derive)

| Vector | Gives an app-uid process call audio without ADB? | Verdict |
|---|---|---|
| Request `CAPTURE_AUDIO_OUTPUT` at runtime | No — `signature\|privileged`, not requestable | ❌ dead |
| Default dialer / `InCallService` role | No — control/state only; still needs the daemon (proven) | ❌ dead for audio |
| Device Owner / DPC | Can grant *runtime* perms silently but NOT signature/privileged; setup needs ADB or QR-factory-reset anyway | ❌ dead + more friction |
| Carrier privileges (cert on SIM) | Cert must be whitelisted on the carrier's SIM; no call-audio API in the surface | ❌ dead |
| Assistant / `VoiceInteractionService` role | Hotword/assist audio, not `VOICE_CALL` | ❌ dead |
| Accessibility service | No audio-capture capability at all | ❌ dead |
| One-time `pm grant`/`appops` to bless the app uid | Perm is uid-checked at AudioFlinger, not appop-grantable; app uid can't reach the libs | ❌ dead |
| `sharedUserId=android.uid.shell` | Signature-checked, deprecated | ❌ dead |
| **MediaProjection `AudioPlaybackCapture`** | **Yes for VoIP/media *downlink*** (app-uid, one consent) — but **CANNOT** capture telephony `VOICE_CALL` (exempt), and A14+ re-consents per session | 🟡 **partial — VoIP/media only** |
| **On-device wireless pairing (Android 11+)** | Removes the *computer* from setup (pairing code entered in-app), not ADB itself | 🟢 **friction reducer** |
| **Root (optional tier)** | Yes — truly install + grant-su, no ADB ceremony | 🟢 **viable for rooted users** |

### The creative reframe — a tiered capability model (this is the real out-of-the-box output)

Instead of one all-or-nothing path, ship **tiers**, so the app is *useful on first launch with zero ceremony* and power users opt up:

- **Tier 0 — true "install + permissions", ZERO developer options.**
  - **VoIP:** MediaProjection `AudioPlaybackCapture` (remote downlink) + MIC (local uplink) — app-uid, one consent, no ADB. (This is Track B done *app-side*, respecting per-app opt-outs.)
  - **Cellular:** become the default dialer (already-built machinery), force **speakerphone**, capture acoustically via MIC (`RECORD_AUDIO` only). Honest, limited (speakerphone only).
  - Net: the app **records *something* for a user who will never touch developer options** — the true native-install experience, at reduced capability. Strong default first-run.
- **Tier 1 — one-time on-device pairing, then autonomous ("feels native after onboarding").**
  - Current ADB daemon path, but: (a) **on-device wireless pairing** (no computer — pairing code in-app), (b) **Track A** held-track so ADB is touched *at most once per boot*, (c) app silently self-manages WD/loopback thereafter. After a ~2-min onboarding the user never sees developer options again → full-quality both-sides cellular that *feels* native day-to-day.
  - Irreducible friction: the **one-time** WRITE_SECURE_SETTINGS grant, and reinstall dropping it (`reinstall-drops-write-secure-settings` memory; in-place updates preserve it).
- **Tier 2 — root (optional).** Truly install + grant-su, no ceremony, best persistence. Rooted users only; auto-detect root and offer it.

**Product framing:** Tier 0 is the zero-friction default that works immediately (VoIP + speakerphone). An in-app "unlock full call recording" flow upgrades to Tier 1 for users who want both-sides cellular quality. The daemon becomes *invisible infrastructure* the average user opts into once — not a barrier at the door.

### What to probe / decide

1. **Tier 0 VoIP viability** = Track B's mic-concurrency + `allowAudioPlaybackCapture` tests, but done **app-side via MediaProjection** (no daemon) — cheapest, unlocks a real zero-ADB feature.
2. **Tier 0 cellular** = confirm default-dialer + force-speakerphone + MIC gives an acceptable acoustic recording (honest quality bar).
3. **Tier 1 onboarding** = polish on-device wireless pairing so no PC is ever required; measure real onboarding drop-off.
4. **A14+ MediaProjection re-consent** — check whether a persistent foreground-service capture token survives, or if each VoIP call needs a fresh consent tap (UX cost of Tier 0 VoIP).

---

## Creative ideas / cross-cutting

1. **Universal capture abstraction — daemon as a "track factory."** If Track A holds, generalize the handoff: the app holds handed-off tracks for *any* source (`VOICE_CALL`, `OUTPUT`, `PLAYBACK`), and the daemon's entire runtime role shrinks to "create N tracks once per boot and hand them off." Everything else (encode, mux, lifecycle, output file) is already app-side (`AudioRecordingEngine` owns the SAF fd + codec today).

2. **Reframe bootstrap from per-call to per-boot.** The whole ADB/WD/loopback dependency exists to have a privileged process *ready at call time*. Track A moves that to *once at boot* — `BootReceiver` does a single create+handoff, and there is no ADB/WD/loopback in the hot path ever again. This is the strategic prize: it doesn't just improve persistence, it removes the per-call attack/failure surface.

3. **Loopback retirement.** If Track A works, `OFFLINE_RECORDING_ENABLED` + the `0.0.0.0` tcpip listener can be deleted, closing the one real security surface in the product (see `loopback-tcpip-offwifi` memory). Document this as an explicit payoff of Track A, not an afterthought.

4. **Dual-hold redundancy.** Hold both a `VOICE_CALL` track (cellular) and an `OUTPUT`/`PLAYBACK` track (VoIP) simultaneously, so *any* call type is covered without knowing in advance which will ring. Cost = two idle tracks' battery (measure in Track A's probe).

5. **Proactive re-create on state change.** Rather than a periodic watchdog, listen for audio-route / call-state / screen transitions and refresh a stale held track *before* the next call, so a track that silently went invalid during idle is renewed at the cheapest moment (device already awake, possibly on WiFi).

6. **Graceful degradation ladder.** If a held track is invalid at call start and we're off-WiFi with no loopback, fall back to: (a) fast daemon relaunch if any transport exists → (b) Option B SUSTAIN-only (launch at call start, survive mid-call death) → (c) honest "not recorded" state. A clear ladder keeps the UX truthful when the ideal path isn't available.

---

## Relationship to what's shipped / proven

- **Option B (proven):** SUSTAIN fix — every *started* recording completes through daemon death. Keeps WD/loopback/keep-alive.
- **Track A (unproven):** CREATE fix — collapses bootstrap to once-per-boot, could retire loopback and off-WiFi opt-in. Gated on idle-track survival.
- **Track B (unproven):** new capability — non-acoustic VoIP via privileged output/playback capture. Gated on mic-concurrency + capture-exemption tests.
- **Track C (reframe):** tiered capability model toward a native install. Hard wall confirmed — zero-ADB full cellular capture is impossible on stock non-root. But Tier 0 (MediaProjection VoIP + speakerphone acoustic, zero dev-options) + Tier 1 (one-time on-device pairing, then autonomous via Track A) + Tier 2 (root) make the daemon *invisible infrastructure* rather than a door barrier.

Update this doc as each probe runs. Cross-referenced from `spike-audio-handoff.md` §7 and memory `spike-audio-handoff-status`.
