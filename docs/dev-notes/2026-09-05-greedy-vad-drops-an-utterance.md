# A whole utterance goes missing from a transcript — greedy + VAD, not capture

**2026-09-05, on 2.3.0-rc2.** Reported as a suspected regression: *"speaker labels in cell call work,
but it didn't transcribe properly, it's missing words entirely… the VoIP call recognised all the words
and I used the same sentences for both."*

**Verdict: not a regression, and not a capture fault.** The audio is complete. The decoder drops the
words, it has done so since before this build, and which call it hits is close to a coin flip.

## What the evidence says

Four recordings from the maintainer's OP12, all Hebrew, all saying a near-identical test phrase twice
("בדיקה, בדיקה, בדיקה, זה OnePlus 12 / OnePlus 9"):

| file | capture path | app-config transcript |
|---|---|---|
| `20:40 out_Feroza` (cell) | handoff | ❌ **first utterance gone** |
| `20:41 voip-WhatsApp` | VoIP | ✅ both |
| `21:03 out_Feroza` (cell) | handoff | ✅ both |
| `21:04 voip-WhatsApp` | VoIP | ❌ **first utterance gone** |

**The failure flipped sides between the two rounds.** That alone rules out "the carrier path is broken":
the same configuration loses an utterance on a VoIP recording too.

**The audio is intact, proven per utterance.** Cutting each speech window out of the 20:40 cell file and
transcribing it alone returns both sentences cleanly — "זה 1.12" and "זה וואן פלוס תשע". Nothing was
lost in capture, and the capture's own accounting agrees: the handoff drain reported
`dropped=0 frames in 0 overruns` over 14 612 ms, and the VoIP capture `0 silence-filled chunks,
farPartyHeard=true`.

**It predates the build.** A cell recording made at 19:46, *before* rc2 was installed at 20:14, loses
words the same way: the app's config yields "חכה" where beam-5 and `-mc 0` both recover
"רגע גבריא, חכה".

**Nothing about the audio changed.** Every file — before and after rc2 — is mono Opus 48 kHz from the
same encoder (`libopus`, preskip 312) at 27–34 kbps, and peak levels are within 1.5 dB. The 28c encoder
change did not alter what encodes calls on this phone.

## What actually causes it

One variable at a time, on the 20:40 cell file, with the app's exact decode settings:

| config | result |
|---|---|
| app config: greedy (`beamSize=1`), VAD on, `entropy_thold` 2.8, prompt | ❌ one utterance |
| …but beam 5 | ✅ both |
| …but VAD off | ✅ both |
| …but rolling conditioning off (`-mc 0`) | ❌ still one (but ✅ on the 19:46 file) |
| …but `entropy_thold` 2.4, or no prompt | ❌ no change |

So it is the **greedy + VAD pair**, exactly the interaction `DecodeSettings.beamSize` already documents
from the other direction. VAD concatenates the kept speech, which packs two near-identical sentences
against each other, and greedy then collapses the near-repeat into one.

**Beam-5 is not the fix.** It repairs both files here, and the existing measurement on a real 8:46 call
has beam-5 + VAD deleting the first 94 seconds, emitting `*ערבית*` language tags and looping `כאילו`
49 times. One pair of 15-second test calls does not overturn that.

## The trap in the test material

**Saying the same sentence twice is the worst possible probe for this pipeline**, because near-repeats
are precisely what a greedy decoder collapses. A regression test that repeats a phrase will keep
producing this "missing words" symptom on healthy builds. Use two different sentences.

## Reproducing the app's decoder on the Mac

This is the part worth keeping — the whole diagnosis was done off-device in minutes.

```sh
brew install cmake                       # was missing
cd third_party/whisper.cpp && cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j 8 --target whisper-cli
ffmpeg -i call.ogg -ar 16000 -ac 1 call.wav

# the app's exact configuration (whispercv.cpp + DecodeSettings defaults)
third_party/whisper.cpp/build/bin/whisper-cli \
  -m ~/.cache/whisper-models/ggml-ivrit-large-v3-turbo.bin -f call.wav \
  -l he -bs 1 -et 2.8 --prompt "<contact name>." \
  --vad --vad-model app/src/main/assets/ggml-silero-v5.1.2.bin \
  --vad-threshold 0.4 --vad-min-speech-duration-ms 100 \
  --vad-min-silence-duration-ms 500 --vad-speech-pad-ms 400
```

Ground truth is the same command with `-bs 5` and no `--vad`.

🚨 **This shell is zsh, which does not word-split unquoted variables.** Putting the VAD flags in a
`$VAD` variable passes them as ONE argument; whisper-cli then prints its usage and exits, and the run
looks like "the model returned nothing". Three runs were misread that way before it was spotted. Write
the flags inline, or use `${=VAD}`.

## This is the same subsystem as issue #25

Not a coincidence, and worth stating plainly: **#25 and this are two faces of one design.**
whisper.cpp's VAD does not trim silence, it **rebuilds the audio buffer** from the kept speech.

- **#25 is the timestamp consequence.** The mapping back to real time bridges each pause with a
  hardcoded 100 ms, so text after a pause is stamped at the instant the previous speaker stopped —
  measured 12.3 s early on the reporter's call.
- **This is the content consequence.** With the pauses gone, two near-identical sentences sit
  back-to-back and the greedy decoder collapses the near-repeat.

Both appear in tonight's own recordings. The 20:40 cell file produced **one** segment stamped
`3.76 → 12.91` whose text is only the *second* utterance — words spoken at 9.4 s, stamped from 3.76 s,
≈5.6 s early. That is #25's signature and this note's symptom in a single line of output. The 21:04
VoIP call logged `VAD kept 4 speech stretches` → `Produced 1 segments`, over audio whose real speech
comes in six stretches separated by pauses of up to 2.6 s.

They are still **two fixes**: clamping segment starts (#25 fix direction 1) corrects timestamps and
would not bring back a lost utterance.

## 🚨 Retracted: do NOT decode each VAD segment separately

An earlier version of this note proposed exactly that as the leading candidate. **It is the
measured-worst architecture and the research note already says so**
(`2026-08-26-transcription-quality-research.md`, and [[transcription-quality-ceiling]]): below ~1 s of
speech, **81% of Whisper outputs are a single memorised word**. Chunk the *decode*, never the
transcription. The proposal is withdrawn.

## The bigger lever here is the MODEL, and it is already measured

The app logged `Transcribing with 6 threads, lang=he, beam=1 ctx=-1 vad=on` on
**`large-v3-turbo-q8_0`** — its best tier, and the app ships no Hebrew-adapted option at all
(`TranscriptionModel` offers `small-q5_1`, `large-v3-turbo-q5_0`, `large-v3-turbo-q8_0`).

The research note left one action open: *"the official ivrit ggml is Apache-2.0 and ungated — quantise
it ourselves to q8_0 → ~874 MB, same footprint as the shipping tier… but ivrit is trained on ~4,700 h
of Knesset plenum, so it is domain-adapted away from phone calls. **Measure first.**"* **This is that
measurement**, on four real calls, both models quantised to q8_0 so size and quantisation are held
equal (833 MB each), decoded with the app's exact settings. Ground truth is known: the maintainer said
"OnePlus 12" and "OnePlus 9".

| call | `large-v3-turbo-q8_0` (ships) | `ivrit-large-v3-turbo-q8_0` |
|---|---|---|
| 20:40 cell | **word salad** — "זה בהתבתל שתם א" plus a stray Cyrillic token | both utterances, second number wrong |
| 20:41 VoIP | both utterances, first number wrong ("OnePlus 10") | **both correct** |
| 21:03 cell | both utterances, first number wrong ("1 + 10") | **both correct** |
| 21:04 VoIP | one utterance lost | first utterance mangled, second correct |

Roughly 3 of 8 utterances right for the shipping model against 5–6 of 8 for ivrit — and the shipping
model's failure on the quietest call is total, not marginal. One thing the shipping model does
**better**: it writes "OnePlus" and "WhatsApp" in Latin as product names, where ivrit renders the same
audio as arithmetic ("1+9").

⚠️ **This does not clear the change.** Four short test calls, all repeating a near-identical phrase —
the worst material for this pipeline, per the section above — and a Hebrew test can only falsify, never
confirm ([[hebrew-cannot-clear-a-quality-change]]). What it establishes is that ivrit is **not**
disqualified by the Knesset-domain worry, which is what "measure first" was asking. A real corpus of
Hebrew calls is still the bar before shipping it as a narrow he-only override — never a catalogue,
which the research note found is not populatable.

## Left open, deliberately

1. **A Hebrew-adapted model as a narrow override**, per above. Biggest measured lever for this user.
2. **`maxTextCtx = 0`** (no rolling conditioning), which the research note already lists as a verified
   defect. Recovered the words on the 19:46 file and split the VoIP one correctly, but did not save the
   20:40 file — a partial mitigation.
3. Beam-5 **only when VAD is off**, the one quadrant the earlier 2×2 found safe. Costs ~17% more
   wall-clock and gives up VAD's larger gain.
