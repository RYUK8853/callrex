# GitHub issues #25–#28 — code triage

**Date:** 2026-09-05
> ## ⏸️ ON HOLD as of 2026-09-05
>
> All four issues are parked to make room for the stuck-mic work, which now has a confirmed root
> cause (see `2026-09-03-shell-mic-open-first-evidence.md`). Nothing here is abandoned and nothing is
> half-finished: #27 and #26 are complete on their branches, #25 and #28 are diagnosed but untouched.
>
> | issue | state when parked | what unblocks it |
> |---|---|---|
> | #27 | ✅ fixed, verified on device | nothing — ready to merge |
> | #26 | ✅ **DONE**, verified on device | nothing — ready to merge |
> | #28 | diagnosed only, no code | a decision on the bounded-retry fix (see 28a) |
> | #25 | diagnosed only, no code | a check of upstream whisper.cpp, then a session of its own |
>
> **#26 closed 2026-09-05.** Measured on the OP12: a real 7.38-minute run, quoted at 7 minutes. The
> same code previously quoted hours for a two-minute call.
>
> Neither branch is merged. `fix/transcription-estimates-issue-26` is stacked on
> `fix/rotation-state-issue-27`, which is stacked on `8e3059e`.

**Status:** every finding below was read out of the source and independently re-checked. The
document began as pure triage with no code changed; **#27 and #26 have since been fixed** and carry
their own status blocks in their sections — trust those over this header, and over the "Suggested
order of work" table near the end, which is a plan rather than a record.

| issue | state |
|---|---|
| #27 | ✅ VERIFIED on device 2026-09-05, plus one follow-up fix still 🧪 |
| #26 | ✅ DONE — fixed and VERIFIED on device 2026-09-05 |
| #25, #28 | diagnosed only — no code written |
**Reported against:** v2.2.0 (the current public release, 2026-08-30).
**HEAD at time of writing:** `8e3059e` on `fix/mic-diagnostics-appops`, 47 commits ahead of `v2.2.0`.
**Reporter:** `mirror176`, Samsung Galaxy S20 FE 5G (SM-G781V), Android 13. All four filed 2026-09-04.
Experimental features they had on: VoIP recording, Resilient recording, Offline recording.

A note on the reporter: these are unusually good bug reports. #26 contains a correct diagnosis
of its own root cause, and #28's "I tried Shizuku and had clean audio" is the single clue that
localises the crackling defect to one file. Worth saying so when we reply.

---

## Summary

| # | Title | Root cause | Verified? | Severity | State on HEAD |
|---|---|---|---|---|---|
| 28 | Crackling in audio | Default carrier path **silently drops a 21 ms PCM chunk** whenever the encoder is busy, splicing the waveform | ✅ **PROVEN by A/B on our own hardware** | **Critical** — corrupts the core artefact | 🧪 **ALL PARTS DONE** — 28a `1499a33`, 28b `1f54eda`+`f8445df`, 28c `fe78b95`, 28d/28e done, 28g `39136e2`, plus the Shizuku audit `1970c33`. Awaiting a real call. |
| 26 | Transcription estimates wildly off | Believability clamp is defeated by `fallback = measured`; one absurd sample is stored permanently | ✅ in code | High — visible nonsense | ✅ **DONE — VERIFIED on device 2026-09-05** |
| 25 | Wrong timestamps on long pauses | whisper.cpp's VAD maps **any** pause onto a hardcoded 100 ms bridge; post-pause starts interpolate into the silence | ✅ in code | Medium | Still present |
| 27 | Rotation closes entry + scrolls to top | Activity is recreated; `playbackFor` is `remember`, not `rememberSaveable` | ✅ in code | Medium — daily annoyance | ✅ **VERIFIED on device 2026-09-05** (`fix/rotation-state-issue-27`); one follow-up fix 🧪 |

**The single most important line in this document:** issue #28 is a real audio-corruption bug in
the default recording path, it is trivially fixable, and the fix is a copy-paste from a file we
already fixed months ago.

**Update, 2026-09-05 evening — that line was half right, and the half it missed is the point.** The
copy-paste fixed the encoder drop (28a). It did not fix the *other* way the same splice signature is
produced: the ring overrunning while the single capture thread was busy (28b). Both are now closed, and
the recording carries a counter that says which of the two, if either, is happening on the reporter's
phone. **Do not read 28a's fix as "the crackling is fixed" until a report from his device says so.**

---

## Issue #28 — crackling in audio

> ### 🧪 SMALL PARTS DONE 2026-09-05 — branch `fix/issue-28-small-parts`
>
> The three sub-issues that are not the crackling are fixed or resolved. **28a (the crackling
> itself) and 28b are untouched** and are the next phase.
>
> | part | what it was | state |
> |---|---|---|
> | **28c** AAC kills the next recording | a failed start was completely silent | 🧪 `e04d595` — the app now says so, and logs why |
> | **28d** Shizuku loses speaker labels | **not a bug** — structural | ✅ resolved, needs only a reply |
> | **28e** Shizuku USB nag loop | warned about a setting it refuses to read or change | 🧪 `1c37f1f` |
> | 28a crackling | drops a 21 ms chunk when the encoder is busy | ⬜ next phase |
> | 28b ring overrun | single-threaded capture loop, ~80 ms ring | ⬜ next phase, with 28a |
>
> 1188 unit tests pass. **None of this has been on a phone yet.**
> **To settle 28c:** set the audio codec to AAC and make a call. Either it records — in which case
> his failure is something else and the log now says what — or a notification appears saying the
> recording did not start, where previously there was silence and no file.
> **To settle 28e:** in Shizuku mode, confirm the lock-screen/USB warning no longer appears.


> "Audio is recorded with a popping sound which happens when audio is present from their side
> and/or my side. Silence doesn't have it keep happening; likely an active distortion based on
> the original waveform." … "I tried pairing through Shizuku and had clean audio."

#28 bundles **five** distinct complaints. They are separated below because only the first is the
headline bug, and three of the others are expected behaviour that we should simply explain.

### 28a — The crackling (CRITICAL, root cause found)

**Which path they were on.** Resilient recording defaults to off (`AppPreferences.kt:202`,
`HANDOFF_PERSIST_ENABLED = false`), and the daemon prefers direct capture
(`RecorderServiceImpl.kt:426`). So a standalone carrier call goes through
`DirectAudioRecorderSession` — daemon-hosted `AudioRecord` → `MediaCodec` → `MediaMuxer`.

**The defect.** `app/src/main/java/com/baba/callvault/server/DirectAudioRecorderSession.kt:162-169`:

```kotlin
val inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)   // 10_000 µs
if (inIdx >= 0) {
    val inBuf = enc.getInputBuffer(inIdx)!!
    inBuf.clear(); inBuf.put(buf, 0, len)
    val ptsUs = totalFrames * 1_000_000L / SAMPLE_RATE
    enc.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
    totalFrames += len / bytesPerFrame
}
// <-- there is no `else`. When inIdx < 0 the chunk is discarded silently.
muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
```

**Why it pops rather than gaps — and this is the part that matches the report exactly.**
`totalFrames` is *not* advanced when the chunk is dropped, so `ptsUs` stays continuous. The
encoder receives an unbroken timeline with 21 ms of waveform excised. The file is not gappy, it
is **spliced**. A splice during silence is inaudible; a splice mid-vowel is a step discontinuity
whose click amplitude scales with the instantaneous signal level. That is a restatement of
*"popping when audio is present… silence doesn't have it… distortion based on the original
waveform."*

**📐 CALCULATED — why the race is lost on this device and not ours.** From
`DirectAudioRecorderSession.kt:265-273`:

| Constant | Value | Meaning |
|---|---|---|
| `READ_CHUNK_BYTES` | 4096 | 1024 stereo PCM-16 frames |
| `SAMPLE_RATE` | 48_000 | → each read holds **21.3 ms** of audio |
| `DEQUEUE_TIMEOUT_US` | 10_000 | → we wait only **10 ms** for a buffer |

We produce audio roughly twice as fast as we are willing to wait to hand it over. Whether the
dequeue times out is a pure timing race against the device's codec and scheduler, so a fast SoC
never trips it and a slower or differently-scheduled one trips it continuously. This is the same
device-specific shape already recorded in `HandoffGeometry.kt:127-131` — *"a OnePlus 12 sounded
clean while a Galaxy S24 FE crackled on every call."* Our test phones are a OnePlus 12 and a
OnePlus 9 Pro. **We would never have seen this ourselves.**

**We have already fixed this bug — in a different file.**
`app/src/main/java/com/baba/callvault/services/recording/handoff/HandoffEncoder.kt:97-104`:

```kotlin
// Feed the chunk — NEVER drop it. The pipe is bursty, so all input buffers can be briefly
// busy; if dequeue times out we drain output (frees a buffer) and retry, rather than
// discarding audio (which caused periodic dropouts = choppiness).
var inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
while (inIdx < 0) {
    muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
    inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
}
```

`VoipCaptureSession.kt:293-298` has the same retry loop. **`DirectAudioRecorderSession` is the
only one of the three encoders that still drops — and it is the default carrier path.** The
retry loop predates v2.2.0 (commit `7e5ccb8`) and was never back-ported.

**Fix:** drain and retry instead of dropping — but **BOUNDED**, not the unbounded loop
`HandoffEncoder` uses. An encoder that is genuinely wedged would spin that one forever, holding the
capture thread while the `AudioRecord` ring overruns behind it, trading a crackle for a dead
recording. Three attempts, then drop *and count it*.

### ✅ PROVEN BY CONTROLLED A/B ON OUR OWN HARDWARE (2026-09-05, `1499a33`)

Our OP12 and OP9 win this race essentially always, so the bug cannot be observed here by waiting for
it. It was made deterministic instead: a temporary injection forced the "no input buffer" branch
every 20th chunk (scaffolding, reverted; not in the shipped commit). Two calls, same phone, same
conditions, one variable changed.

**A dry run first caught a mistake worth recording.** The first attempt injected into
`DirectAudioRecorderSession` while the OP12 had *Resilient recording ON* — which routes through
`HandoffEncoder` instead, so the injected fault never executed. The null result was nearly read as
evidence. Confirm the path your device will actually take before trusting a reproduction; the log
now prints a banner naming the path.

With Resilient recording off, so the direct path really ran:

| | injected | recovered | dropped | splices at the predicted 19456-sample spacing |
|---|---|---|---|---|
| **before** (bug) | 84 | — | **84** | **p = 4.9 × 10⁻³ — clustered** |
| **after** (fix) | 82 | **82** | **0** | p = 0.92 — uniform, gone |

The 19456 figure is a *prediction*, not a fitted parameter: dropping every 20th chunk leaves 19
surviving chunks of 1024 frames between splices. Controls at 18432 and 20480 — one chunk either side
— showed nothing (p = 0.76, p = 0.94), so the detector is not simply finding structure everywhere.

**This also retro-validates the reporter's recording.** The same mechanism produces the same
signature, so his p = 5.4 × 10⁻¹⁵ clustering at 1024 is chunk dropping, measured rather than argued.

### ✅ CONFIRMED FROM THE REPORTER'S OWN RECORDING (2026-09-05)

The reporter attached a real affected call in a comment on #25
(`20260904_111322.721-0700_in_+16026093676.ogg`, mono Opus 48 kHz, 101.4 s) and noted it
*"also expresses the bug in my report #28"*. Analysed on the desktop:

- **165 step discontinuities** at a robust-outlier threshold (439 at a looser one) over 101 s.
- They correlate with signal presence: median envelope at events is ~2× the file's median, and
  only 23 of 165 land in the quietest quartile — matching *"silence doesn't have it"*.
- **The decisive test.** Every queued chunk is exactly 1024 frames, so if these are dropped
  chunks the events must sit at a fixed phase modulo 1024 in the output. A Rayleigh uniformity
  test over event positions:

  | period | meaning | R | p |
  |---|---|---|---|
  | **1024** | **our `READ_CHUNK_BYTES` frame count** | **0.27** | **5.4 × 10⁻¹⁵** |
  | 960 | Opus's own frame size at 48 kHz | 0.03 | 0.62 (uniform) |
  | 1100 | control | 0.06 | 0.21 (uniform) |

  The events align to **our chunk boundary** and **not** to the codec's frame boundary. Had these
  been Opus artefacts they would have clustered at 960. This is direct evidence for the splice
  mechanism, independent of the code reading above.

**Consequence: #28a no longer needs a Samsung to reproduce or to verify.** Re-running this
analysis on a post-fix recording is a sufficient regression test, and the pre-fix file is a
permanent fixture. Analysis scripts were ad-hoc; re-create from this description or keep them
alongside the fixture if we want them in-tree.

**Process lesson worth recording separately:** a fix landed in one of three parallel
implementations and no mechanism existed to notice the other two. Compare the memory entry
*"three capture paths: handoff, direct, voip — anything touching captured audio must be added to
all three or it silently never runs."* This is that failure mode, realised, on the default path.

### 28b — `AudioRecord` ring overrun — 🧪 VERIFYING (fixed and measured, 2026-09-05 evening)

**Both halves of the plan are done: the loss is counted, AND the loop that caused it is decoupled.**
Branch `fix/issue-28b-overrun` (`1f54eda`, `f8445df`), off `fix/issue-28-crackling`.

**🚨 The realisation that changes how 28a should be read** (found while explaining this on 2026-09-05):
an overrun produces **the same chunk-aligned splice signature as 28a**. Each `record.read` returns a
whole 4096-byte chunk, so audio lost *between* two reads still leaves the join exactly on a chunk
boundary. The reporter's recording **cannot distinguish 28a from 28b**, and until his next report we
must NOT assume 28a fixed him. That is what the counter is for.

#### What was measured, on the OP9, one variable changed

A 500 ms stall was injected every 25 chunks — the same forcing technique that proved 28a, because our
own phones win this race every time:

| the same stall, injected | audio lost |
|---|---|
| on the **read** side (i.e. the old single-threaded loop) | **3 460 ms**, 15 events |
| on the **encode** side, behind the new queue | **0 ms** — backlog peaked at 49 chunks and drained |

A control run with no stall at all reported nothing, so the counter does not cry wolf.

#### The measurement had to be the clock, and that is a finding

The first build asked the hardware where it was, via `AudioRecord.getTimestamp` — the obvious choice,
and **it is blind to this exact failure.** In the run where the clock saw 3.46 s missing,
`framePosition` stayed within a few hundred frames of what we had read, and twice went *negative*. It
reports frames delivered to the client, not frames the hardware produced, so it only moves when we
read. A ledger built on it reports a clean run through a catastrophic one. `RingOverrunLedger`'s class
comment says so; do not reintroduce it.

#### Two things the emulator taught, both worth not rediscovering

- **The emulator cannot reproduce a ring overrun.** Its audio input produces on demand: a 200 ms stall
  every 50 chunks lost precisely nothing, and frames read tracked the clock exactly. Real hardware is
  required for anything about capture timing.
- **`connectedAndroidTest -PisolateTestApp` cannot drive the daemon.** The daemon delivers its binder to
  the hardcoded authority `com.baba.callvault.recorder`, which under isolation belongs to the *other*
  (release) install — so `RecorderDaemonRoundTripTest` fails with "never delivered its binder" on any
  phone that has the release app on it. The proof drives `DirectAudioRecorderSession` directly instead,
  from a probe run over `app_process` as shell.

#### Where the count surfaces

Not only in the daemon's log, which reaches a bug report **only** through logcat and therefore only if
the reporter had debug logging on *before* the call — the opposite of what a report about a past call
can offer. It travels the way speaker turns already do: session → daemon cache at stop →
`captureDiagnostics()` over the binder → the **app's own** log. Verified at the session level on the
OP9: `captureDiagnostics='overrunMs=3440 overruns=15'`.

#### What is still open

- **A real two-sided call on the OP12** — nothing here has been through a carrier call yet. The
  decoupling moves speaker detection and the downmix onto the encode thread, so speaker labels and the
  channel map are the things to listen for, along with the front of the recording.
- **His next report** is what finally separates 28a from 28b on the hardware that has the problem.

#### The original finding, for reference

`DirectAudioRecorderSession.kt:253` sized the ring at `minBuf * BUFFER_FACTOR` (`BUFFER_FACTOR = 4`) —
measured at **7 680 frames (160 ms)** on the OP9 — while `captureLoop` ran read → speaker detection →
downmix → `queueInputBuffer` → `drainEncoder` → `mux.writeSampleData` (an actual file write) **all on
one thread**. Any stall in that chain longer than the ring overran the `AudioRecord`, which discards
frames silently. Nothing counted or logged it.

The handoff path deliberately decoupled ring consumption from downstream for exactly this reason
(`audiohandoff.cpp:279-284`, *"DECOUPLE ring consumption from downstream … periodic micro-gaps
(the choppiness)"*). The direct path has that treatment now.

### 28c — AAC selection kills the next recording — 🧪 VERIFYING (both suspects fixed, 2026-09-05 evening)

**Fixed in `e04d595` (the silence) and `fe78b95` (the causes), branch `fix/issue-28c-aac`.** We still
cannot say which AAC setting *his* device refuses — that needs his phone — but all three ways this
could happen are closed, and none of them needed it.

> "changing from Opus (my preference) to AAC failed to record the next call"

**⚠️ One claim in the earlier version of this note was wrong, and is corrected here.** It said a stored
bit rate could never fall back to a codec's default, so switching Opus→AAC always kept 24 kbps. The
Settings screen never had that bug: `setAudioCodec` already adopted the new codec's recommended rate.
**The onboarding wizard did not** — `WizardViewModel.setAudioCodec` wrote the codec and nothing else, so
a codec chosen there kept the previous codec's rate. Two paths, two behaviours, and the wizard's is the
one that hands AAC 24 kbps. The rule now lives in one place (`AppPreferences.chooseAudioCodec`) and both
call it. Re-picking the codec already in use no longer overwrites a rate the user deliberately set,
which the Settings path used to do.

**The encoder we measured was not necessarily the encoder that ran.** `EncoderLimits` took the *first*
codec advertising the MIME type, while `DirectAudioRecorderSession` created its codec with
`createEncoderByType`, which makes its own choice — and one that takes the sample rate and channel count
into account, which first-in-list never did. On a device shipping several AAC encoders (Samsung does;
it ships exactly one Opus encoder, which is why this could only ever bite AAC) the clamp was computed
from the wrong ranges. Both now resolve the same encoder for the same format, and the codec is created
**by name**, so what was measured is what runs.

**A refused `configure()` used to end the call.** The direct path threw, and the scrcpy fallback then
ran with the same rejected settings — nothing recorded, which is exactly the report. It now retries once
at the codec's own recommended rate before giving up, turning "nothing was recorded" into "recorded, at
a rate you did not pick", and saying so in the log. No clamp can replace this: an encoder can advertise
a range that includes a rate and still decline it.

Checked on the OP9 (2026-09-05): opus@24k, aac@24k and aac@32k all record, and the resolved encoder is
logged each time (`c2.android.aac.encoder`, `c2.android.opus.encoder`). That is a no-regression check,
not a reproduction — this phone never refused AAC in the first place.

**Still open:** which setting his Samsung actually refuses. `CaptureStartCheck` (`e04d595`) is what will
tell us: a live daemon with no capture three seconds after dispatch now raises a notification and an
explicit log line naming the likely cause, instead of a silent empty file.

### 28d — Shizuku loses speaker labels — ✅ RESOLVED, no code, reply only

Verified as expected behaviour, not a regression. Nothing to fix; it needs a sentence in the issue.

**Draft reply:** *"That one is expected rather than broken. Shizuku can't host the microphone capture
directly, so it records through a different path that hands us audio already encoded. Speaker labels
are worked out from the raw two-channel audio before it's mixed down, and on that path we never see
it — so there's nothing to derive them from. It's also why you get mic on the left and the other
party on the right there, which the normal path doesn't do."*

Verified as expected behaviour. Shizuku cannot host an `AudioRecord` (`HandoffPolicy.kt:40`), so it
uses scrcpy, which hands us **already-encoded stereo**. `RecorderSession` therefore never sees PCM,
and `SpeakerTurnDetector` runs on the stereo buffer *before* the downmix on the paths we encode
ourselves. No PCM → no speaker turns, structurally.

This also explains their "mic on left and speaker on right": scrcpy's stereo is passed through,
whereas every path we encode is forced to mono (`DirectAudioRecorderSession.kt:268`
`ENCODE_CHANNELS = 1`, rationale at `:90-94` — stereo Opus at 24 kbps starves the far party to
~12 kbps).

**Opportunity, not a defect:** their observation is a reminder that when we *do* have stereo with
mic-L/far-R, speaker attribution is exact rather than inferred. Worth a look for the diarization
backlog — but note the standing "MUST-NOT-UNDO mono-encode rule" before touching it.

### 28e — Shizuku USB-mode nag loop — 🧪 FIXED in `1c37f1f`

**Fixed by resolving the cached value to UNKNOWN in Shizuku mode**, rather than by adding a rule.
UNKNOWN already means "we cannot see this" everywhere else in that file, and `noticeFor` already
handles it: silent while the recorder is up, an honest "could not check" when it is not. Standalone
mode is untouched and still warns.

⚠️ The premise flagged below — whether the screen-lock risk is even real under Shizuku — is **still
unverified**, and this fix deliberately does not depend on it. The argument stands either way: the
app refuses to read the setting and refuses to change it in that mode, so warning from a stale value
the user cannot correct in-app is indefensible regardless of the underlying risk.

> "With Shizuku mode I get a constant warning that lock screen recording may not work because USB
> is set to file transfer… if I change it to charge only it stops Shizuku"

The **writer is mode-gated; the warner is not.**

- `UsbDefaultConfig.setViaShell()` refuses in Shizuku mode — logs *"Shizuku mode: not setting the
  Default USB Configuration — no embedded ADB"* (`UsbDefaultConfig.kt:245`)
- `readViaShell()` refuses too (`:299`)
- but `isScreenLockRisk()` (`:196`) reads **only** the cached mode, with no privileged-mode check:
  `cached(context).let { it !in SAFE && it != UNKNOWN }`
- and `HomeViewModel.kt:515` calls it while reading `privilegedMode` **two lines above**, unused

So in Shizuku mode we nag about a setting we refuse to read, refuse to change, and whose cached
value can never be refreshed — while the app's own picker declines to fix it. `PrivilegedMode`'s
own doc comment states the principle being violated (`PrivilegedMode.kt:37-40`): *"in Shizuku mode
none of it applies, and machinery that fires anyway would report failures for setup the user was
never asked to do."*

**Fix:** gate `usbScreenLockRisk` on `privilegedMode.needsAdbSetup`.

⚠️ **Not yet established:** whether the underlying screen-lock risk is genuinely absent under
Shizuku. The rationale for the warning is that a data USB default restarts `adbd`, killing the
shell-uid daemon. Whether a Shizuku-hosted recorder is immune has **not** been verified and should
be before we silence the warning rather than merely re-scoping it.

---

### 28g — The Shizuku / USB advice was harmful — ✅ MEASURED, then fixed (2026-09-05 evening)

**Fixed in `39136e2`, branch `fix/issue-28g-usb-advice`.** `1c37f1f` had silenced the nag and left the
advice standing everywhere else.

The reporter wrote three things; the first reading took only the first:

> "With Shizuku mode I get a constant warning that lock screen recording may not work because USB is
> set to file transfer instead of charge only **and if I change it to charge only it stops Shizuku**
> and after restarting Shizuku USB is switched to debugging"

#### The mechanism is no longer reasoned — it was measured on the OP9 (ColorOS 14, 2026-09-05)

`svc usb setFunctions` / `setScreenUnlockedFunctions` was used to change the USB configuration, with
`shizuku_server` (pid 12409) and `adbd` (pid 5672) watched across it:

- **adbd restarted** — it came back as pid 18888.
- **`shizuku_server` was gone**, and did not come back.
- A plain detached shell script (`setsid`, ppid 1, shell uid) launched beforehand **survived the same
  event and ran to completion**, so this is not a blanket kill of shell-uid processes; it is specific
  to how Shizuku's server is hosted.

**It is the renegotiation that does it, not the destination.** The change measured here was *to* a data
mode, not to charge-only. So every option in our picker is equally unsafe in Shizuku mode — the advice
was worse than "wrong recommendation", it was "do not touch this control at all".

⚠️ Measured on ColorOS, and reported by a Samsung user. One UI is not separately confirmed; the
user-facing wording says what the change *does* (restarts the debugging service), which holds either
way.

#### What changed

| where | before | now |
|---|---|---|
| Home screen warning | ✅ suppressed in Shizuku mode (`1c37f1f`) | unchanged |
| Settings picker | greyed, but still labelled "Charging only (recommended)" | no recommendation in Shizuku mode, and a hint saying what changing it would cost |
| `setUsbChargingOnly()` fix-it | silently no-ops | unreachable in Shizuku mode anyway; now also refuses **during a recording**, and says so |
| Settings picker, any mode | applied mid-call, killing the recording | refused while a recording is live, in both callers |
| `README.md:129` | recommended Charging only, no caveat | carries the Shizuku and mid-call warning |

**The mid-call refusal is new, and was found by this test rather than reported.** Applying a USB change
restarts adbd, which kills the shell-uid daemon holding the capture. A setting whose entire purpose is
to stop recordings being lost was able to end one in progress.

#### Side effect worth knowing

The test **stopped Shizuku on the OP9** and it could not be restarted from the host: there is no
`start.sh` in its external files directory, and invoking `moe.shizuku.server.Starter` over `app_process`
aborts. It needs a tap in the Shizuku app. Budget for that before running this experiment again.

## Issue #26 — transcription time estimates are too far off

> ### 🧪 VERIFYING — fixed 2026-09-05 on branch `fix/transcription-estimates-issue-26`
>
> Four commits, branched off the verified #27 work (both touch `HomeScreen`, so stacking them beat
> rebasing later). One concern each, so any can be reverted alone:
>
> | commit | covers | change |
> |---|---|---|
> | `c01817c` | 26a | call site passes the model's published figure; `blend` now also refuses an unbelievable *fallback* |
> | `751d1cd` | 26f | elapsed measured on `SystemClock.elapsedRealtime` |
> | `f09412a` | 26b, 26c | two-term cost model, measured setup, real thread count, new preference prefix |
> | `f9d680e` | UX | "The first one may take a while" until the phone has measured a run |
>
> 1158 unit tests pass (was 1151). Assembles clean.
>
> **📐 CALCULATED — what the estimate now says** (turbo-q8_0 seed, before any measurement):
>
> | call length | now | before |
> |---|---|---|
> | 10 s | 37 s | 11 s (real cost ≈ 40 s) |
> | 2 min | 2 min 16 s | 2 min 12 s |
> | 5 min | 5 min 34 s | 5 min 30 s |
>
> Long calls barely move, which is the point — they were never the broken case. Short ones stop
> being fiction, and the absurd-value path is gone entirely.
>
> **What is NOT established:** no run has been timed on a real phone since the change. **To settle
> it:** transcribe something on a phone that has never transcribed with the current model — the
> confirmation should say "the first one may take a while" — then transcribe a second recording and
> check the quoted figure is in the right neighbourhood of what it actually takes. A short clip
> (~10 s) and a longer call (~2 min) should now agree about how fast the phone is; that agreement is
> the thing the fix is really about.

> "times of < 10 seconds to hours estimated… for recordings in the range of 10 seconds to 2
> minutes; all calls have transcribed within minutes"

There is exactly one user-facing ETA. It is a pure product computed *before* the run
(`TranscriptionEstimate.kt:61`, `estimateMs = audioMs * rtf`), shown by
`TranscribeConfirmDialog.kt:78`. `rtf` is learned per model from finished runs.

**Two theories are ruled out:** there is no early-progress extrapolation (nothing divides by
`fractionDone`), and no seconds/ms confusion. Published seeds are sane (0.99 / 2.16 / 1.10), so a
fresh install quotes correctly. **"Hours" can only come from a poisoned learned value.**

### 26a — The believability clamp returns the value it just rejected (ROOT CAUSE)

`TranscriptionEstimate.kt:49-58` — note the documented contract: *"@param fallback the model's
published factor"*:

```kotlin
fun blend(stored: Double?, measured: Double?, fallback: Double): Double {
    val believable = measured?.takeIf { it in BELIEVABLE }   // BELIEVABLE = 0.05..50.0
    return when {
        believable == null -> stored ?: fallback              // <-- the hole
        stored == null -> believable
        else -> stored * (1 - SMOOTHING) + believable * SMOOTHING
    }
}
```

`TranscriptionRunner.kt:199-204` violates that contract:

```kotlin
val blended = TranscriptionEstimate.blend(
    stored = prefs.getTranscriptionRtf(modelId),
    measured = measured,
    fallback = measured            // <-- passes the measurement as its own fallback
)
prefs.setTranscriptionRtf(modelId, blended)
```

When `stored == null` and `measured` is outside `0.05..50.0`, the clamp rejects it as a
"measurement bug" and then **returns it anyway** — and line 204 writes it to prefs. A stored rtf
of 90 quotes a 2-minute call at `120_000 × 90` = **3 hours**. That is the reported symptom exactly.

**The tests prove a protection production never gets.** `TranscriptionEstimateTest.kt:53-54`
asserts the clamp only with `stored = 2.0`, never with `stored = null` — so the hole is untested.
But the sharper point is that *every* test passes `fallback = published`, i.e. the contract the
docstring describes, while **the only production caller passes `fallback = measured`**. The suite is
green, the clamp genuinely works as tested, and none of it applies to the single real call site.

### 26b — The calibration wipe re-opens the hole repeatedly

`AppPreferences.kt:707-715` deletes **every** `transcription_rtf_*` key when the thread count
differs from the stored one — and `TranscriptionRunner.kt:198` calls it **before** `stored` is read
on line 200. So after each wipe `stored` is null on that very run, 26a fires, and one unclamped
sample becomes the phone's permanent opinion.

The plausible trigger (⚠️ hypothesis, cheap to falsify by logging): `preferredThreadCount()` derives
from `Runtime.getRuntime().availableProcessors()`, which on Android reports **online** cores.
Samsung kernels vary these with hotplug and thermal state. 8 on one run and 6 on the next wipes
everything each time — matching "wildly varying, no correlation with content."

Distinct and verified: `recordSpeed` reads `preferredThreadCount()` a *second* time at `:198`,
after the run and on another thread, instead of reusing what the run actually used
(`TranscriptionEngine.kt:238`) — so it can record a policy no run ever used.

### 26c — The cost model is structurally wrong (the reporter's own diagnosis, and correct)

`measure` divides elapsed by audio length with no floor (`TranscriptionEstimate.kt:37-40`), but the
elapsed window spans a large fixed cost independent of audio length: an **874 MB** model mmap+init
per run, VAD extraction on first run, decode, and whisper's mandatory **one full 30-second encoder
window** whatever the clip length.

📐 CALCULATED from the project's own measured 0.722 rtf on an OP9 Pro (`TranscriptionModel.kt:99-103`),
with an S20 FE slower still — fixed floor ≈ 30-50 s:

| clip transcribed | measured rtf becomes | then quotes a 2-min call at |
|---|---|---|
| 10 s | ~4 | 8 min |
| 2 s | ~20 | 40 min |
| 0.5 s | ~80 | **2 h 40 min** (and 26a stores it) |

The cost is `load + rtf × audio`; the code models `rtf × audio`. The reporter's suggested fix —
length-aware history — is the right one.

### 26d — Recovery is slow, and other gaps

`SMOOTHING = 0.3` means ~13 completed runs to crawl from a stored 90 back to a true 1.1. The first
measurement also replaces the published seed **outright** (`:55`), so one unaveraged sample becomes
the device's opinion. Queue position is ignored — `TranscriptionScheduler.kt:125-129` chains work,
but the dialog estimates only the tapped file. Storage is **one** `Double` per model
(`AppPreferences.kt:689-694`): no bucketing, no sample count, no variance, no outlier rejection
beyond the defeated range check. With VAD on, whisper processes only kept speech while `measure`
divides by full container duration, adding variance the other way.

### What was done, and two things deliberately not

Done, in the four commits above. One design note worth keeping: the plan said "refuse to learn from
runs under ~60 s of audio", and that turned out to be both unnecessary and worse than the
alternative. whisper pads anything under 30 s up to 30 s and does a full window of work regardless,
so dividing by `max(audio, 30s)` makes a short clip a **valid** sample rather than one to discard.
The arbitrary threshold disappeared and every run now teaches the estimate something.

The stored figures moved to new preference prefixes (`transcription_rate_v2_`,
`transcription_load_v2_`) rather than being migrated. What is stored changed meaning — work time over
billable audio, with the load charged separately — and on an affected phone the old value is absurd
and would take about thirteen runs to average away. A new prefix retires those quietly with no
migration code, which is why an already-affected user is fixed by updating rather than by waiting.

**Deliberately not done:**

- **Queue position is still ignored.** `TranscriptionScheduler` chains work one run at a time, but
  the dialog estimates only the tapped recording — tap three and each is promised its own duration
  while the third waits for all three. Real, but unreported, and it needs the dialog to know about
  the queue rather than only about the file. Its own change.
- **The estimate is not mentioned in the in-app "What's new" card.** The card stays headline-led on
  merging, which is how 2.3.0 already treats its other fixes; the changelog carries the detail. A
  sentence is easy to add if wanted.

---

## Issue #25 — incorrect timestamps on longer pauses

> "a timestamp that got placed in a break of speech between two voices during a transfer…
> there was a noticeable pause between them."

### Root cause: whisper.cpp's VAD compresses every pause into a hardcoded 100 ms bridge

VAD is **on by default** (`DecodeSettings.kt:174`, `useVad = true`), so this affects every
transcription. whisper.cpp's VAD does not merely trim silence — it **rebuilds the audio buffer**
and returns timestamps through a linear-interpolation table that is degenerate at pauses.

`third_party/whisper.cpp/src/whisper.cpp:6731`:

```cpp
int silence_samples = 0.1 * WHISPER_SAMPLE_RATE;   // 1600 samples = 100 ms, hardcoded
```

`:6785-6793` — for the gap between segments *i* and *i+1*:

```cpp
int64_t silence_start_vad = samples_to_cs(offset);
int64_t silence_end_vad   = samples_to_cs(offset + silence_samples);
int64_t orig_silence_start = segment.orig_end;
int64_t orig_silence_end   = vad_segments->data[i+1].start;
state->vad_mapping_table.push_back({silence_start_vad, orig_silence_start});
state->vad_mapping_table.push_back({silence_end_vad,   orig_silence_end});
```

So **100 ms of processed time stands for the entire real gap**, however long it is, and lookups
interpolate linearly across it (`:7995`). Two bridges span every pause:

| processed span | original span | effect |
|---|---|---|
| overlap copy (100 ms) | zero width | slope 0 — everything collapses onto `orig_end_i` |
| injected silence (100 ms) | the whole real pause | slope = gap / 100 ms |

**Why post-pause starts land there by construction:** `whisper.cpp:7699` sets `t0 = t1` — each
segment starts where the previous ended. In the compacted buffer those are ~200 ms apart, so the
post-pause segment's start almost always falls inside one of the two bridges. Worst and most
likely case: it lands in the flat overlap bridge and maps to **the instant the previous speaker
stopped**, while the words themselves begin seconds later. That is issue #25 verbatim.

**Our own VAD params set the threshold** (`app/src/main/cpp/whispercv.cpp:323-338`):
`speech_pad_ms = 400`, `min_silence_duration_ms = 500`, `threshold = 0.4`. With the padding logic
at `whisper.cpp:5410-5442`, a pause **< 800 ms** distorts nothing; a pause **≥ 800 ms** is cut and
its residual compressed to 100 ms.

📐 CALCULATED: a 3 s transfer pause yields up to **2.2 s** of error in ~220 ms steps; a 10 s hold
yields up to **9.2 s**. The error is always **early**, matching the report.

### ✅ CONFIRMED FROM THE REPORTER'S RECORDING (2026-09-05)

Measured against the attached call, the prediction holds to the second:

| Reporter's description | Measured (20 ms RMS envelope) |
|---|---|
| robocall's last sentence ends ~1:16 | speech ends **76.68 s** (1:16.7) |
| "slight ring at 1:18" | isolated blip **78.30–78.44 s** (1:18.3) |
| dialog marked 1:16 really starts 1:28 | speech resumes **88.30 s** (1:28.3) |

The pause is **9.86 s** — far above the 800 ms distortion threshold — and whisper stamps the
post-pause dialog at ~1:16, i.e. **exactly `orig_end_i`, the instant the previous speaker
stopped.** That is the zero-slope overlap bridge, predicted from source before the audio was
available. Error ≈ **12.3 s**.

It also confirms what was previously only a hypothesis: the 1.62 s gap, then the short ring, then
the 9.86 s gap means **the ring passed Silero VAD and became its own segment**, which is why the
reporter saw the mark land near it.

The reporter's own words — *"blank space needs to be appended to end of last time instead of
beginning of this one"* — describe fix direction 1 below.

**This file is ground truth for a regression test**: any fix must move that segment's start from
~76 s to ~88 s.

The source comment at `whispercv.cpp:301-306` — *"it does NOT re-segment, and that distinction is
the whole reason this is safe"* — is correct about text quality and **wrong about timestamps**.
Concatenation is precisely what makes timestamps unrecoverable at pauses.

### Knock-on and secondary findings

- **No sanity check anywhere.** `TranscriptionEngine.kt:296-308` stores segment times verbatim: no
  monotonicity check, no gap clamp, no reconciliation. `SpeakerLabeller.kt:50-81` consumes them, so
  **speaker labels around transfers are wrong too** — the reporter has not noticed this yet.
- **Chunking is NOT at fault.** The classic bug (offsets from trimmed length) is absent:
  `ChunkPlan.stitch` adds `decodeFromMs`, and `TranscriptionEngine.kt:308` passes the *measured*
  decode start, so container seek drift is handled. Chunking only engages above 5 min anyway.
- **Missing "Uh"/"Umm" — suspected causes ruled out.** `suppress_non_speech_tokens` is **false**
  (`whisper.cpp:5985`, never set by us) and `suppress_blank` only affects sampling start. The real
  contributors are the model's training (inherent) and VAD deleting short low-energy turns — a
  failure mode already measured and recorded in `DecodeSettings.kt:161-170`.
- **Trailing hallucinated word — mechanism confirmed.** Rolling conditioning is left at whisper's
  224-token default (`whispercv.cpp:289` with `maxTextCtx = -1`); `no_context = true` does *not*
  disable it. `initial_prompt` is set to the contact name (`TranscriptionPrompt.kt:63-67`), a known
  minor source of prompt echo.
- **Diagnosability gap.** whisper.cpp logs the whole mapping table (`:6774`, `:6818`) but there is
  **no Android log callback** — `whispercv.cpp` never calls `whisper_log_set`, so it goes to stderr
  and is invisible in a debug report. Wiring it to `AppLogger` would make this class of bug readable
  from a user report.

### ✅ RESEARCHED 2026-09-06 — and the answer changes the fix

**Upstream knows, and has NOT fixed it at the segment level.** `ggml-org/whisper.cpp` issue **#3634**,
*"whisper.cpp produces continuous timestamps and removes silence gaps, even when VAD segments already
contain gaps"* — our symptom exactly, with a second reporter saying *"Same issue here, even big issue
when vad is enabled"* and a cross-reference from *"wrong timing in subtitles"*. It was **closed by the
stale bot on 2026-09-06**, not by a fix. (#3174 and #3584 are also closed without one; **#3754**, VAD
wrecking token timestamps when audio opens with music, is still open — and is the same pairing that
[[transcription-quality-ceiling]] measured with beam.)

**But upstream fixed the identical problem one level down, and we already ship that code.** PR **#3910**,
*"whisper : map token timestamps to original time when VAD is enabled"*, merged **2026-07-01**; our pin
`371b5a75` is **v1.9.3 of 2026-08-20**, so it is **in our tree today**. It added
`whisper_full_get_token_t0/t1` and, the part that matters, `whisper_map_token_time_segment_aware()`
(`src/whisper.cpp:8099`), which:

- interpolates a time **inside** a speech stretch using that stretch's REAL duration — no 100 ms
  bridge, no degenerate slope; and
- **snaps a time that lands in removed silence to the nearer real boundary**
  (`src/whisper.cpp:8121-8124`).

That is fix direction 1, written and maintained upstream, with tests in `tests/test-vad-full.cpp`. The
PR even says out loud that a token *"that falls in the silence removed between two segments is snapped
to the nearest boundary, so it doesn't end up in the middle of a gap that isn't in the original audio."*

🚨 **Process lesson worth keeping.** The earlier check — *"77 commits since our pin, none touch the VAD
mapping"* — was true and still missed this, because the fix landed **before** the pin. Checking commits
*after* a pin can only ever find what we have not got; it cannot tell you what you already have.

### ✅ FIXED AND PROVEN ON A DEVICE 2026-09-06 — the measurement moved the target twice first

Branch `fix/issue-25-vad-timestamps` (`570d58a`). `SpeechGapSnap` + JNI getters for the VAD stretches.

**Running the reporter's own recording through whisper.cpp with our exact settings killed the plan
below before a line of app code was written:**

| what the library actually reports | value |
|---|---|
| VAD stretch 6 | 74.54 – 77.04 s |
| VAD stretch 7 (speech resumes) | **88.53** – 93.07 s |
| whisper's segment start for "Thank you so much for connecting…" | **76.52 s** |
| the same line's RAW token time (concatenated timeline) | **65.96 s** |
| `whisper_full_get_token_t0` on it, with `token_timestamps` on | **76.84 s** |

1. **"Snap a start that lands in a removed pause" does nothing here.** 76.52 s is *inside* stretch 6,
   not in the gap. Fix direction 1 — ours, and the reporter's — would have shipped and changed nothing.
2. **Upstream's token mapping does not rescue it either.** With `token_timestamps` on, the token times
   are recomputed against the segment's own already-wrong window, so the getter returns 76.84 s. The
   raw token time (65.96 s) *does* hold the right information, but enabling the flag destroys it.
3. **The real mechanism:** whisper's timestamp for that line sits ~0.4 s *before* the seam in the
   concatenated timeline — in the tail of the previous stretch. Removing an 11.5 s pause **amplifies
   0.4 s into 12 s**. A tiny error, made enormous by the missing gap.

**So the rule that shipped** moves a start when it lands in a removed pause **or** when it lands in the
last second of a stretch while the line runs on past the following pause; ends move back to where
speech stopped; nothing moves when the VAD kept nothing. Eight unit tests, every number from the
reporter's file.

⚠️ **It is a heuristic, deliberately.** Whisper's timestamps across a seam are not trustworthy to
better than a few hundred milliseconds. A line that genuinely begins in the last second of a stretch
and continues past a pause loses up to a second at its front — against lines no longer stamped a dozen
seconds early.

**✅ Verified end to end on the OP9** (`Issue25TimestampTest`, `99fef7d`): the reporter's own recording
through the app's own `transcribeBuffer`, shipped `DecodeSettings`, `large-v3-turbo-q8_0`, VAD on:

```
66.85s -> 76.48s  "to finalize your record right now. Okay? Hold on…"
88.53s -> 99.75s  "Thank you so much for connecting. My name is Adam…"   ← was 76.52s
```

**88.53 s is exactly where speech resumes.** The remaining unknown is only whether the reporter agrees
on his own device.

Two things that run taught, both worth keeping:

- **ColorOS froze the instrumented app** in the background — RSS retained, zero CPU ticks, every thread
  sleeping, no progress for eight minutes. `cmd deviceidle whitelist +<pkg>`, `RUN_ANY_IN_BACKGROUND`
  and `svc power stayon usb` are what make a long instrumented test possible on the OP9.
- **The first run failed against a working fix** because the selector matched "connecting" and an
  earlier line says *"I'm connecting…"*. A loose match in a test is how a good fix gets reverted.

The desktop harness that produced the table above is `scratchpad/t25.cpp` — a 40-line program linked
against the vendored library, worth rebuilding rather than guessing next time.

### Fix directions, re-ranked by that research (superseded by the above, kept for the reasoning)

1. **RECOMMENDED — snap segment starts ourselves, using public API already in our tree.** For each
   segment, if its start falls in a removed gap, move it forward to the next speech stretch's original
   start. `whisper_full_get_vad_segment_t0/t1` return exactly `orig_start`/`orig_end`
   (`src/whisper.cpp:8166-8178`, verified) and are still unused by us. No submodule patch, no decode
   change, and it mirrors upstream's own rule for tokens. It is also literally what the reporter asked
   for: *"blank space needs to be appended to end of last time instead of beginning of this one."*
2. **Enable `params.token_timestamps` and take each segment's start/end from its first/last token**, via
   upstream's mapping. Tempting, because the mapping is then maintained by upstream — but token times
   are only computed when that flag is on (`src/whisper.cpp:7686`), it adds per-segment work, and
   whisper's non-DTW token timing is a heuristic. More moving parts for the same answer.
3. ~~Make the injected silence proportional to the real gap~~ / ~~keep the true gap in a side table~~ —
   both submodule patches, both now unnecessary.

**Cross-check against a proven implementation:** `faster-whisper` never interpolates across removed
silence either. It carries a per-chunk `offset` through `collect_chunks` and adds it back, so a
timestamp is reconstructed by addition against a real boundary. Upstream's token fix, faster-whisper,
and the reporter all describe the same shape.

---

## Issue #27 — rotation closes expanded entry and scrolls to top

> ### ✅ VERIFIED 2026-09-05 — fixed on branch `fix/rotation-state-issue-27`
>
> Confirmed by the maintainer on the OP12 (build `2.3.0-rot27`, versionCode 20316): the open
> recording stays open, the list keeps its place, multi-selection survives, open dialogs survive,
> the app lock does not re-prompt on rotation — **and both negative lock cases still lock**
> (background-and-return, and kill-from-recents). Those two were the security-relevant checks.
>
> | commit | covers | change | state |
> |---|---|---|---|
> | `ab7a055` | 27a + 27c | `playbackFor` → `rememberSaveable` | ✅ VERIFIED |
> | `67aa104` | 27d | the rest of Home's screen state, + `ui/common/StateSavers.kt` and 12 tests | ✅ VERIFIED |
> | `a3341d1` | 27e | App Lock carried across recreation, guarded on `isChangingConfigurations` | ✅ VERIFIED |
> | `cf31f5d` | 27g | the unlock card no longer flashes on open | 🧪 VERIFYING |
>
> 1151 unit tests pass (was 1132). Assembles clean.
>
> **Still VERIFYING:** the flash fix (`cf31f5d`) was written after the device pass and has not been
> installed. **To settle it:** open the app with App Lock on — no "locked / Unlock" card should
> appear before or after the fingerprint prompt. Then dismiss the prompt by tapping outside it: the
> card **must** appear, because it is the only way back in without force-stopping the app.

**Why any state is lost:** `MainActivity` declares no `android:configChanges` and no
`android:screenOrientation` (`AndroidManifest.xml:84-87`), so rotation fully destroys and recreates
the Activity. Only saveable state survives. `HomeScreen.kt` contains **25** `by remember` and
**zero** `rememberSaveable`.

### 27a — The expanded entry closing (still open, one-word fix)

Tapping a row does not expand inline — it sets `playbackFor`, which swaps the whole list out for
`PlaybackScreen`. `HomeScreen.kt:247`:

```kotlin
var playbackFor by remember { mutableStateOf<String?>(null) }
```

On rotation this resets to `null`, the detail view closes, and the user lands back on the list.
It is a `String?`, so `rememberSaveable` needs no `Saver`.

### 27b — The scroll-to-top (ALREADY FIXED on HEAD, unreleased)

In v2.2.0 the `LazyColumn` created its `LazyListState` internally, *inside* the
`if (playbackFor == null)` block. `rememberSaveable` unregisters when it leaves composition, so
while a recording was open there was nothing to save — rotation restored index 0.

Fixed after v2.2.0 by commit `9276d53`, which hoisted it (`HomeScreen.kt:251`) with a comment
describing this exact bug. **So half of #27 is already fixed and merely awaiting a release** —
worth confirming on-device before telling the reporter.

### 27c — Rotation bypasses `closePlayback` (invariant leak) — RESOLVED BY 27a

Closing the detail view deliberately stops audio (`closePlayback` = `stopPlayback(); playbackFor = null`).
Rotation just nulled the field, so **audio kept playing after its screen was gone.** The reporter
charitably read this as a silver lining ("isn't actively interrupting playback"); it was really a
leak of the stop-on-leave invariant.

Making `playbackFor` saveable resolves it without a second decision: the screen is no longer *left*
on rotation, so the invariant it guards is no longer crossed. Audio continues, and the screen that
owns it comes back with it — which is also the behaviour the reporter wanted.

### 27d — Seven more unreported symptoms of the same class — FIXED, with two deliberate exclusions

Converted to `rememberSaveable`: `selection`, `showSupport`, `showSupportAppeal`, `showBulkDelete`,
`transcriptFor`, `deleteTranscriptFor`, `confirmDeleteFor`, `showTranscriptSearch`,
`showTranscribingSheet`, `showModelMissing`, `tooLongMinutes`, `confirmTranscribe`,
`askLanguageFor`, and the bulk-delete dialog's `scope`.

`selection` (`Set<Uri>`), `confirmTranscribe` (`Triple<String, Long?, String?>`) and `scope`
(`DeleteScope`) needed Savers. Those live in `app/src/main/java/com/baba/callvault/ui/common/StateSavers.kt`
as pure encode/decode functions with 12 tests, on the reasoning that `rememberSaveable` restoring a
value is the framework's guarantee but *what we hand it* is ours — and a selection that decodes to
the wrong set is a multi-select whose next action is a delete. Everything encodes to a **list of
strings, never a delimited string**: recording names carry spaces, plus signs, percent-encoding and
at least one real `|`. `RecordingItem` is deliberately never saved — it is a 16-field non-Parcelable
data class, so where an item is needed it is re-resolved from the ViewModel's surviving list.

**Deliberately left resetting**, with the reasons in code comments:

- **The merge dialogs** (`mergeFor`, `unMergeFor`, `unMergeLabels`, `mergeProgress`) — see the new
  finding below. Restoring them would show a dialog for an operation that no longer exists.
- **`deleteTarget`**, the per-row delete confirmation — a rotation that dismisses a destructive
  prompt has destroyed nothing, whereas restoring one bound to a row the refreshed list no longer
  contains leaves a Delete button pointed at something invisible. Dismissing is the safe failure.
- Dropdown menus (`expanded`, `open`) — closing on rotation is conventional.

### 27g — NEW, found on the device: the unlock card flashed on every open

Not in the report and not visible in any screenshot — the maintainer saw it while checking 27e.
Opening the app with App Lock on flashed a card reading "CallVault / locked / Unlock" before the
fingerprint prompt appeared, and again as it tore down.

Cause is lifecycle order, not the lock logic: `onCreate` composes before `onStart` asks for the
prompt, so the locked window drew its full card for a frame with nothing yet on top of it. Fixed in
`cf31f5d` by making the window a function of state — `appLockUi(lockEnabled, isUnlocked,
promptDismissed)` returning `APP` / `WAITING` / `DOOR` — so the card is raised by exactly one thing,
a prompt returning unauthenticated, and the locked window otherwise draws only its background.

The card is deliberately kept for that one case: a prompt dismissed by a tap outside it would
otherwise leave force-stopping the app as the only way back in. The decision is a pure function with
7 tests rather than lifecycle order, because a timing bug like this cannot be seen in a screenshot
and would come back the moment someone reordered a lifecycle call.

### 27f — NEW FINDING: a merge in flight is killed by rotation

Found while fixing 27d, not in the original report and **not fixed here**. `mergeScope` is a
`rememberCoroutineScope()`, so an Activity recreation cancels a running merge or un-merge part-way.
The user sees the progress dialog vanish; what state the recordings are left in depends on where it
was interrupted.

This is arguably more serious than #27 itself, and its fix is different in kind: hoist the merge
into `HomeViewModel` (which survives recreation) rather than making its dialog saveable. Restoring
the dialog without that would be worse than the current behaviour — it would offer to re-run a merge
over recordings that may already be partly merged. Worth its own issue.

### 27e — App Lock re-prompts on rotation — FIXED

`isUnlocked` was an Activity field cleared in `onStop`, so a recreated Activity started locked and
`onStart` re-prompted. Because `AppLockScreen` replaces the nav host, this tore `HomeScreen` out of
composition entirely — which would have discarded even the state 27a and 27d just made saveable.
Not what #27 reports, but it had to be fixed for the rest to hold.

Now carried in the instance state, guarded on `isChangingConfigurations` in **both** directions
(written only during a config change, and `onStop` no longer clears during one).

⚠️ **The security-relevant detail, for review:** the same bundle is also delivered after process
death and restore-from-recents, which *is* leaving the app. Because nothing is written unless
`isChangingConfigurations` is true, `onCreate` reads `false` in that case and the app stays locked.
That distinction is the whole safety argument for this change and is worth confirming on-device —
both negative cases are in the verification list at the top of this section.

### Ruled out (do not spend time here)

`items(key = { it.uri.toString() })` is present and stable (`HomeScreen.kt:693`). The ViewModel and
its list survive recreation, and `refresh()` deliberately does not clear `recordings`, so the
"empty list on first frame" theory does not apply. Playback survives because
`RecordingPlaybackController` is a ViewModel field (`HomeViewModel.kt:84`) — that is the exact
contrast the reporter noticed.

---

## Cross-cutting themes

These matter more than any individual fix.

1. **Parallel implementations drift.** #28a is a fix that landed in one of three encoders. The
   memory entry *"three capture paths… anything touching captured audio must be added to all three
   or it silently never runs"* predicted this precisely. Consider a shared feed helper so the three
   cannot diverge again.
2. **Failures that produce no evidence.** #28c (start failure invisible), #25 (whisper's own
   diagnostics unreachable), #26 (a poisoned rtf is never logged as implausible). This is the same
   blindness that cost five rounds on the stuck-mic investigation.
3. **A guard whose test does not exercise its own failure branch** (#26a) is worse than no guard —
   it reads as protection while providing none.
4. **Our test devices are both OnePlus.** #28a is a timing race we structurally could not observe.
   Samsung reports deserve extra weight.

---

## Suggested order of work

| Order | Item | Cost | Why first |
|---|---|---|---|
| 1 | #28a — no-drop retry loop | ~5 lines | Corrupts the core artefact on the default path; fix already written elsewhere |
| 2 | #26a — `fallback` = published rtf | 1 word | Removes the absurd magnitude immediately |
| 3 | #26b — wipe ordering | small | Stops the hole reopening |
| ~~4~~ | ~~#27a — `rememberSaveable`~~ | done | ✅ **DONE 2026-09-05**, all of #27 — branch `fix/rotation-state-issue-27` |
| 5 | #28e — gate the USB nag on mode | small | Verify the premise first |
| 6 | #28c — make start failure reportable | medium | Unblocks all future diagnosis |
| 7 | #25 — VAD timestamp post-pass | medium | Check upstream first |
| 8 | #26c/#26e — proper cost model | larger | The real fix |
| 9 | #27d — saveable sweep | medium | Prevents seven future reports |

## What needs a device or a decision

- ~~**Reproduce #28a** on a Samsung before and after the fix.~~ **No longer needed** — the
  reporter's attached recording confirms the mechanism statistically (p = 5.4 × 10⁻¹⁵ against the
  1024-frame chunk boundary, uniform against Opus's 960). A post-fix recording re-analysed the
  same way is a sufficient check; a Samsung A/B is now a nice-to-have, not a blocker.
- ~~**Confirm all of #27**~~ — **done on device 2026-09-05**, including both App Lock negative cases.
  Only the follow-up flash fix (`cf31f5d`, 27g) is still unconfirmed; see the top of the #27 section.
- **Release notes for #27 are written** — `CHANGELOG.md` under 2.3.0 → Fixed, and the in-app note
  (`whatsnew_230_body`) in all ten languages. The in-app note stays headline-led on merging, with
  rotation as one closing sentence; the changelog carries the detail.
- **Decide whether #27f** (a merge in flight dies on rotation) gets its own issue. Not fixed here.
- **Falsify or confirm #26b's trigger** by logging `availableProcessors()` per run.
- **Establish whether the screen-lock risk is real under Shizuku** before silencing that warning (#28e).
- ~~**Check upstream whisper.cpp** for a VAD-mapping fix~~ — **done 2026-09-05: there isn't one.**
  We are pinned at `371b5a75` (v1.9.3) and `origin/master` is 77 commits ahead; none of its changes to
  `src/whisper.cpp` touch the VAD mapping (mel initialisation, null guards, a VitisAI plugin, an
  OpenVINO path). The one commit matching "vad" adds a nullptr check to a test. **We write this fix
  ourselves.** Re-fetch before implementing, in case these refs are stale.
- **Decide the intended rotation behaviour for audio** (#27c).

## Replying to the reporter

Four things we can tell them straight away:
- #28 crackling: root cause found, fix identified, their Shizuku comparison is what localised it.
- #27: **fixed** — both halves, plus the multi-selection, open dialogs and transcript sheet, which
  they had not reported. Worth mentioning that rotating no longer re-asks for the app lock either.
  Note back that a merge interrupted by rotation is a separate bug we found from their report.
- #26: confirmed, and their proposed fix (length-aware history) is the correct one.
- #25: the missing "Uh"/"Umm" is model behaviour plus VAD, not something we suppress — and yes,
  the same audio file transcribes the same way on another device, so sharing one would help.
  The timestamp error itself is ours (inherited from whisper.cpp's VAD) and is actionable.
