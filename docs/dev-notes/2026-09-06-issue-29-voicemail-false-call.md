# Issue #29 — playing a voicemail is reported as a call that failed to record

> **🧪 VERIFYING — fixed 2026-09-06 on `fix/issue-29-voicemail-false-call` (`067b490`, `9519bba`).**
> Unit-tested, device-tested for the one property no unit test can cover, and the release build is
> green. **Not verified**: nobody has yet played a voicemail on an affected phone and watched the
> warning not appear. That needs the reporter, or a Samsung.

**Reporter:** mirror176 (Samsung Galaxy S20 FE 5G, One UI 5.1, Android 13), against 2.2.0, standalone
mode, VoIP + resilient + offline recording all on. *"Playing back voicemail… causes callvault to
indicate like a call was initiated and then marks that the call was not recorded. Issue disappears once
I activate Shizuku for recording."*

## Root cause (from our source; the Shizuku clue is what proves it)

`VoipCallDetector` has exactly one signal: `AudioManager.getMode() == MODE_IN_COMMUNICATION`. That is
deliberate and documented — there is no broadcast for WhatsApp-style calls, and playback *usages* are
anonymised for ordinary apps, so the mode was the only thing left. But the mode is a **routing state,
not a call state**: any app that wants its audio in the earpiece takes it.

Samsung's voicemail app plays through the earpiece by default (there is a speaker toggle beside it,
which is why the reporter noticed it made no difference). So each play — and each resume after a pause,
which he also noticed — looks exactly like an app call starting.

`VoipRecordingCoordinator.onCallStarted` then finds it cannot record (no daemon connected, or the
policy was not armed before the "call") and calls `reportMissed`, which shows *"An app call was not
recorded — CallVault was not ready in time"* **and writes `recordGap(now, "App call")` into
`SetupHealthStore`.**

**Why Shizuku makes it vanish, and why that is the proof:** `VOIP_RECORDING` is unavailable in Shizuku
mode (`ModeCapability`: `!mode.needsShizuku`), so the mode switch turns the feature off and
`VoipCallDetector.start()` returns immediately. **Carrier recording stays on in Shizuku mode** — so if
this were the carrier path, Shizuku would have changed nothing. It is not a fix; the feature is absent,
and the symptom returns the moment he goes back to standalone.

## Severity: higher than "an annoying notification"

Nothing is lost — there was no call. But every false trigger writes an **unexplained gap** into the
health record, the strongest signal the app has, and it surfaces on the status card the next day. So
playing voicemails fabricates a history of missed calls, and it teaches the user to ignore the one
warning that must never be ignored. Same shape as the Drive-health false positive: **a claim about the
present, made without evidence about the present.**

## Two defects found while reading, both real on their own

1. **The per-app exclusion is checked too late.** `VoipAppPolicy.shouldRecord` sits *after* the
   daemon-missing and folder-invalid branches, and both report a miss first. A user who excludes the
   voicemail app still gets the notification whenever the daemon is down.
2. **He may have no workaround at all.** `VoipAppList` offers only apps that have a launcher entry
   **and** request `RECORD_AUDIO`. A visual-voicemail app may fail either test.

## What the research found, and what it changed

### ✅ It is not necessary to ask the daemon whether something is recording

The first plan was "have the daemon check for an active capture, since it holds `MODIFY_AUDIO_ROUTING`".
The platform docs say that is unnecessary: *"You can get a general view of all active recordings on the
device by calling `AudioManager.getActiveRecordingConfigurations()`"* — device-wide, no permission. Only
the **identity** is redacted without `MODIFY_AUDIO_ROUTING` (the client uid reads `-1`), and identity is
not what we need. **Existence is enough**, and existence is exactly the discriminator:

- a real two-way call means **some app is capturing the microphone**;
- voicemail playback captures nothing.

App-side, no daemon, no new permission — which matters precisely because in this reporter's case **the
daemon is the thing that is missing**. We use none of this API today.

### 🆕 `TelecomManager.isInCall()` is a positive-only signal, and must not be used to suppress

It *does* include self-managed (VoIP) calls, and needs only `READ_PHONE_STATE`, **which we already
hold**. So `true` is solid corroboration that a call exists. But it can only be used one way: not every
VoIP app registers a self-managed `ConnectionService`, and this research could not confirm that WhatsApp
does. Using `isInCall() == false` to stay quiet would silence a genuine missed WhatsApp call — trading
this bug for the worst failure this app has.

### 🆕 A non-UI `InCallService` cannot see app calls at all

Worth knowing before anyone proposes "detect calls through Telecom like upstream does":
`METADATA_INCLUDE_SELF_MANAGED_CALLS` *"can only be set for an InCallService which also sets
METADATA_IN_CALL_SERVICE_UI, and only the default phone/dialer app or a car-mode InCallService can see
self-managed calls."* So Telecom binding solves **carrier** detection, never VoIP — unless the parked
dialer-mode work lands and we become the default dialer.

### 🆕 Upstream and BCR both drive *carrier* detection from an `InCallService`

`kitsumed/ShizuCallRecorder` binds one as NON_UI through the `MANAGE_ONGOING_CALLS` AppOp (Android 12+),
and reads `Call.Details.PROPERTY_VOIP_AUDIO_MODE` and `isSelfManaged`; its source carries the AOSP
links and an adb check (`adb shell telecom is-non-ui-in-call-service-bound <pkg>`). BCR calls this
*"much more reliable than using the READ_PHONE_STATE permission and relying on PHONE_STATE
broadcasts."* Not a fix for #29, but it is the industry answer for the carrier path, and it is sitting
in our own upstream.

### 🆕 Two corroborations of things we already believed

- *"Two ordinary apps can never capture audio at the same time"* — the documented rule behind
  [[no-second-voice-capture-during-call]].
- `AudioRecordingConfiguration.isClientSilenced()` exists: the platform will **tell** a recorder that it
  has been silenced by capture policy. We currently infer this. Relevant to the one-sided-VoIP warning.

## What was implemented

1. **`CallEvidence`** — asks the platform who is capturing, and treats only a **communication-type
   source** (`VOICE_COMMUNICATION`, `VOICE_CALL`, `VOICE_UPLINK/DOWNLINK`) as evidence of a call. A
   source and not a count, because our own capture opens `MIC` and appears in the same list, and an idle
   phone was twice seen holding a stray `MIC` session.
2. **All four miss-report paths now go through that gate** (`reportMissedIfReal`). Nothing else changed
   about when a recording is attempted.
3. **The per-app exclusion moved above the report paths.** It sat below two of them, so an app the user
   had switched off still warned them whenever the recorder was not ready or the folder was unwritable.

### The one design decision worth defending

**The gate is on the report, never on the recording.** A VoIP app that captures with plain `MIC`, or
that opens its capture a moment after the audio mode flips, will not corroborate. Gating the *recording*
on that would trade this bug for a lost call, which is the worst failure this app has. Gating only the
*warning* means a wrong answer costs a warning we did not print.

A consequence, accepted knowingly: on a phone whose daemon **is** connected, a voicemail playback can
still start a recording. The two fixes cover the two states between them — with the daemon up the app is
identifiable and the user can exclude it (and that exclusion now works before any report), and with the
daemon down there is no false warning any more. Revisit only if someone reports the spurious recording
itself.

### Not gated, deliberately

`VoipRecordPrompt` — the "ask me" offer shown when auto-start is off. Suppressing a *prompt* on this
evidence could cost a real recording, which is the line drawn above.

## ✅ MEASURED ON THE OP12 (2026-09-06) — the probe answered both questions

An instrumented probe running in an **ordinary app process** (the isolated test app, so the visibility
measured is the visibility the real app has) sampled `AudioManager` and `TelecomManager` once a second
across playback and a real WhatsApp call:

| state | mode | `isInCall` | `recordingConfigs` |
|---|---|---|---|
| idle | NORMAL | false | 0 |
| **audio playing** (nothing else) | NORMAL | false | **0** |
| **real WhatsApp call** (21 s) | IN_COMMUNICATION | **false** | **2** — `src=7 silenced=false`, `src=1 silenced=false` |
| a few seconds after the call | NORMAL | false | 1 (`src=1`), then 0 |

**Attribution is certain:** `src=7` is `VOICE_COMMUNICATION` — WhatsApp capturing for the call — and
`src=1` is `MIC`, which is **our own daemon** (`VoipCaptureSession` opens `AudioSource.MIC`). Both calls
produced recordings, so the second session is ours by construction.

### What this proves

1. **The discriminator is real, and needs nothing privileged.** A call shows another app capturing with
   a communication source; playback shows no capture at all. `clientAudioSource` is **not** redacted for
   an ordinary app — we read `src=7` from an unprivileged process. No daemon, no new permission.
2. **`TelecomManager.isInCall()` was FALSE for the entire WhatsApp call.** WhatsApp does not register a
   self-managed `ConnectionService` on this device. So Telecom is not merely unusable as a *suppressor*
   — it is not even a usable *positive* signal for the most common app. **Drop it from the design.**
3. **Our own capture is visible in the same list**, so "any capture" is the wrong rule — it would let our
   own recording confirm itself. A stray background `MIC` session was also seen twice while idle, which
   would have false-positived a naive count.

### The rule the measurement supports

Treat `MODE_IN_COMMUNICATION` as a call only when **another app is capturing with a communication-type
source** (`VOICE_COMMUNICATION`, `VOICE_CALL`, `VOICE_UPLINK/DOWNLINK`). At the moment the mode event
arrives our own capture has not started, so anything already in the list belongs to someone else.

**Apply it asymmetrically, and this is the important part:** use it to gate the **miss report**, never
the recording attempt. A VoIP app that captures with plain `MIC`, or that starts capture a moment after
the mode flips, would fail the strict test — and the cost of being wrong must be a missing *warning*,
never a missing *recording*.

## Verified; what is left to ask the reporter



Both device questions are now answered above. Still worth asking the reporter: the voicemail app's package name, whether the notification says "not ready in time",
  and a 2.3.0 debug report taken right after playing a voicemail.

**rc2 does not change this** — the capture-start check added for 28c is on the carrier path only.
Expect the same report against 2.3.0-rc2.
