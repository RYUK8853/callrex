# Bluetooth headsets (and smart glasses) can make a carrier recording silent

**Status: PARKED — not planned.** Written down so the next person who meets it does not spend a week on
it, as issue [#18](https://github.com/madkongo/CallVault/issues/18) did. Nobody is expected to pick this
up; there is no hardware here to test it on.

## What happened

Issue #18 reported carrier recordings on a Galaxy Z Fold 6 that were the **correct size and the correct
duration but played back silent**. It ran for about a week. The daemon-and-system-log work exists because
of it — the app's own log was clean end to end, because the failure lived in the recorder daemon's
process where no bug report could reach.

The reporter eventually found the cause himself, and it was none of the things we were chasing: **his
Meta (Ray-Ban) smart glasses.** With the glasses in use the recording was silent; without them it worked.

## Why it almost certainly happens

CallVault captures with `MediaRecorder.AudioSource.VOICE_CALL` — a tap in the device's audio HAL on its
own voice path. Ray-Ban Metas are a Bluetooth headset: an HFP/SCO device with its own microphone and
speakers. When the call routes to Bluetooth, the voice path moves, and on many HALs the `VOICE_CALL` tap
then delivers zeros. A live capture faithfully recording nothing produces exactly the reported symptom:
right size, right duration, silence.

This is below anything the app can reach without root. It is not specific to Meta glasses either — every
Bluetooth headset, car kit and hearing aid is the same shape of problem.

**This is inference, not measurement.** It fits the symptom and the reporter's own finding, but nobody
here has tested it with a Bluetooth headset connected.

## What was considered, and why it was not done

| Option | Verdict |
|---|---|
| `VOICE_UPLINK` / `VOICE_DOWNLINK` separately | **The one experiment worth running** if hardware ever appears. Both sources already exist in `ScrcpyAudioSource`; on some HALs one survives when the mix does not. Untested. |
| `MIC` | Captures the near end through the phone's own microphone. Useless when the phone is in a pocket and the user is speaking into the headset. |
| The VoIP technique (dynamic AudioPolicy loopback mix) | Does not apply. It captures what *apps play*; carrier call audio never traverses that path. |
| Force the route back to the phone | We hold the privilege to do it, and it would make recording work — by taking the call out of the user's headset. User-hostile. Only defensible as an explicit opt-in, and not worth building for an edge case. |

## What would actually have helped, and still would

Not a fix — a diagnosis. Two cheap things would have turned a week into one call:

1. **Silence detection** — an all-zeros check on the daemon's PCM (cheap on the direct path). Already
   listed as an unscheduled idea argued for by this same issue.
2. **Log the audio route at record start** — one `AudioManager` call naming the active communication
   device. As of 2026-08-04 the app has *no* audio-route awareness at all: no reference anywhere to
   Bluetooth, SCO, `communicationDevice` or `AudioDeviceInfo`.

Together they would have produced: *"recording started, audio routed to Bluetooth, captured 14 s of pure
silence"* — on the first call, for the reporter and for us.

Deliberately not built on 2026-08-04: an edge case, with no hardware here to verify either the diagnosis
or the fix. If a second report of silent recordings ever arrives, **ask what the audio was routed to
before anything else.**
