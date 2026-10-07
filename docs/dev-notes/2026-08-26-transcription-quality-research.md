# Transcription and summary quality — what is fixable and what is not

**Researched 2026-08-26**, prompted by a side-by-side against the maintainer's `AIDashboard` on the same
call, where the dashboard's output was far better. Four independent research passes plus a read of both
pipelines. **Multilingual quality is the goal — Hebrew is the maintainer's own test case, not the target.**

Every number below is marked MEASURED (with its dataset) or INFERRED. Where a claim could not be
sourced, it says so.

---

## 0. The ceiling, before any plan

Telephone audio is not a tuning problem. Whisper's own paper (arXiv:2212.04356, Table 8) measures
`large-v2` at **17.6% WER on CallHome** and **13.8% on Switchboard** — both real telephone corpora —
against **2.7% on clean speech**. A ~6× penalty. Fine-tuning Whisper Medium on **7,500 hours** of real
contact-centre audio (arXiv:2506.03681) moved 14.8% → 12.3%.

The mechanism is spectral and matches this project's own spike: these calls carry 99% of their energy
below 2.0–2.8 kHz and **0.00% above 4 kHz**, while Whisper's mel filterbank spans 0–8000 Hz. The top
half of every frame is structurally empty. whisper.cpp's maintainer names the consequence — the failure
mode is **confident fabrication**, not fuzziness.

**Nobody in the surveyed field reports good on-device telephony ASR.** Hyprnote (9.2k★, local-first
meeting app) ships no on-device summariser at all and routes to Claude Sonnet; Meetily runs Ollama on
desktop GPUs and still carries open quality complaints. **CallVault shipping a working on-device
summariser is ahead of every comparable project** — which also means there is nobody to copy.

**Product consequence:** frame a transcript as a search index and a summary input, not a record. That is
accurate, and it is the honest answer to "why is the quality poor".

---

## 1. Verified defects in our own code

Each of these is a bug or an unintended divergence, not a tuning preference.

### 1.1 The prose-join fix is dead code

`SummaryPrompt.forChunk` joins unlabelled segments into flowing text, and its KDoc records why:

> *"Measured on a real Hebrew call: handed 176 separate lines, Gemma returned the **first four, copied
> word for word**, and ignored the rest."*

`forChunk` is **not called by the production path**. (An earlier version of this note said it was called only from `SummaryPromptTest` — that was wrong: `SummaryBenchmark` and `RealCallBenchmark` in androidTest call it too, which makes it the *unconstrained arm* of the on-device A/B the Constraint Tax finding asks for. It was kept for that reason, not deleted.) The live path is `SummaryRunner` →
`forChunkJson`, which joins with `\n` and stamps every line. A real 8:40 call produced **370 segments**
— more than twice the input already measured to break it.

The codebase names this drift one paragraph away, about a different line: *"The JSON prompt never said
this; only the prose one did, and the prose one is not the one in use."* It was fixed for that line
only. Four other lines born of measured failures are still missing from the JSON prompt.

**Fix:** port the conditional join. Join only where no speaker labels exist — where `SpeakerLabeller`
produced labels, line breaks carry turn-taking and must survive. **Never merge across a speaker change.**

### 1.2 `no_context = true` does not disable rolling conditioning

It clears carry-over from a *previous* call, once, on entry to `whisper_full`. Within a run,
`prompt_past` is rebuilt after every 30-second window and re-injected, gated on `n_max_text_ctx`.

**CORRECTED 2026-08-26 after measuring:** an earlier version of this section said the default is 16384
and therefore "full rolling conditioning". That overstates it — whisper.cpp clamps with
`min(n_max_text_ctx, n_text_ctx/2)` and `n_text_ctx` is 448 on every large model, so the effective
default has always been **224 tokens**.

**MEASURED on a real 4:05 Hebrew call, and the conclusion is to leave it alone.** `n_max_text_ctx = 0`
is disqualified badly: **79 segments** against 47, most of them 1–2 seconds, terminal punctuation gone
almost everywhere, and the least text of any variant — exactly the predicted cost of losing rolling
conditioning. `= 64` is faster (310 s vs 358 s) and tidier, but it was **the only variant to drop two
utterances that all four others captured** — invisible in the segment count, which is why segment count
alone is not a quality measure. **Ship the default.**

**This is a trade, not a free win:** losing rolling context costs proper-noun consistency, punctuation
and casing across windows, and may worsen fragmentation. Middle ground `n_max_text_ctx = 64` is
suggested upstream. **Measure both ways on one real call.**

### 1.3 Greedy decoding, where the reference uses beam-5

`whispercv.cpp` sets `WHISPER_SAMPLING_GREEDY`; `whisper-cli` defaults to `beam_size = 5`. The
divergence is unintentional.

MEASURED (Whisper paper Appendix D, `large-v2`): **CallHome 17.6 → 16.4 (−6.8% rel)**, Switchboard
13.8 → 13.6, CORAAL 16.2 → 14.2 (−12.3% rel). Twelve of fourteen sets improve — **but the two noisiest
far-field sets get worse** (AMI-SDM1 36.4 → 39.9).

COUNTER-EVIDENCE (arXiv:2501.11378, 8,272 non-speech files): higher beam sizes produce **more**
hallucination on non-speech, lowest at beam 1. Calls are full of hold music, ringback and line noise.
**So VAD must land before or with beam search, never after.**

**MEASURED 2026-08-26, and the architectural argument held: beam-5 cost +5.0% wall-clock, not 3–5×.**
Turbo has 32 encoder layers and only 4 decoder layers, and beam multiplies decoder work only. It also
allocates **no extra decoders** — `n_decoders = max(greedy.best_of, beam_search.beam_size)` and greedy's
`best_of` is already 5, so memory is unchanged.

On the same call it repaired an inverted phrase (`את לא הגדרנו` → `עוד לא הגדרנו`), recovered a spoken
figure VAD had mangled, and produced a coherent sign-off, at the cost of two minor word corruptions.
**Shipped, behind VAD.**

### 1.4 VAD is vendored and unused

`whisper.h` exposes `params.vad` / `vad_model_path` in our v1.9.3. The Silero ggml model is **864 KB** —
small enough to bundle rather than download.

MEASURED: Whisper paper Table 7, 10.6 → 10.2 avg (−3.8% rel), helping all seven datasets. WhisperX
(arXiv:2303.00747): TED-LIUM 10.5 → 9.7 WER, **5-gram repetitions 131 → 75 (−43%)**. arXiv:2501.11378:
baseline **40.3% non-speech hallucination rate**; VAD alone does most of the reduction. Careless Whisper
(arXiv:2402.08021): ~1% of transcriptions carry wholly hallucinated phrases, driven by **long non-vocal
durations** — the defining profile of a phone call.

It also **reduces** processed audio (~25% in upstream's example), so it pays for beam search.

**Critical nuance:** whisper.cpp's VAD removes silence and concatenates — it does **not** re-segment.
Use it to trim dead air, **never to slice speech into segments** (see §2).

### 1.5 The resampler drops samples with no anti-aliasing

`AudioDecoder.resampleTo16k` is documented as linear interpolation. But 48000/16000 is **exactly 3**, so
the interpolation fraction is identically zero: it is keep-one-discard-two with **zero stopband
rejection**. Everything from 8–24 kHz folds into 0–8 kHz at full amplitude.

The docstring's defence ("telephony carries no energy above ~3.4 kHz") holds for carrier audio and is
**false for the VoIP path**, which mixes a full-band 48 kHz `MIC` capture.

Upstream whisper.cpp uses miniaudio, whose linear resampler applies a **4th-order anti-aliasing
low-pass**. We don't.

**Measure before fixing:** FFT one captured buffer from each of the three capture paths and look above
8 kHz. If only VoIP is dirty, fix only that path.

#### ✅ MEASURED 2026-08-26 — and the guess above was backwards

13 real OP9 recordings, decoded at 48 kHz, speech-active frames only. Reported as the energy that folds
into **0–4 kHz** relative to the genuine signal already there — the exact quantity decimate-by-3
produces, since 12–16 kHz mirrors down and 16–20 kHz maps down directly.

| path | files | mean | worst |
|---|---|---|---|
| VoIP (`voip-`) | 5 | **−28.9 dB** | −23.6 dB |
| carrier incoming (`_in_`) | 5 | **−27.3 dB** | −26.2 dB |
| carrier **outgoing** (`_out_`) | 3 | **−19.2 dB** | **−17.5 dB** |

**All three paths are dirty, and VoIP is the *cleanest*.** The reason the guess inverted: `VOICE_CALL`
carries the **uplink** — the local microphone, which the network never band-limits — alongside the
band-limited downlink. Outgoing calls are worst simply because the local speaker dominates them. The
"no energy above 3.4 kHz" measurement was true, but it was taken on the PCM *before* encoding; it was
never true of the files the transcriber opens.

Ruled out as explanations: it is not an Opus artifact (a synthetic source brick-walled at 3.4 kHz comes
back from a 28 kbps round-trip at −74 dB, i.e. nothing), and not a decoder artifact (ffmpeg's native
Opus decoder and libopus agree to within 0.02 pp on the same file). Demo recordings under
`/sdcard/CallVaultDemo` are synthetic and were excluded — they are full-band TTS and skew the result.

**Fixed** in `AudioDecoder.resampleTo16k`: Hamming-windowed sinc low-pass at 0.9 × the 8 kHz output
Nyquist, evaluated only at output positions (53 taps at 48 kHz, tap count derived from the input rate).
Residual contamination **−94 dB mean / −85 dB worst**, against −45 dB for miniaudio's 4th-order
reference, costing 0.01 dB below 3.4 kHz and 0.24 dB from 3.4–7 kHz.

Expect the WER effect to be **small** — the ceiling was ~18 dB of added in-band noise on the worst path
only, and §1's own citations warn that resampler quality is a minor term. This was worth doing because
it is cheap and provably correct, not because it will move a number.

### 1.6 The initial prompt may be in the harmful regime

We pass a bare contact name, frequently in Latin script on a non-Latin call.

MEASURED (arXiv:2309.07081, Chinese dialects, `large`): in-context prompting gives **−41.1%** relative
WER on Chongqing dialect and **−66.0%** on Guangzhou. Two findings transfer directly: gains **scale with
model size**, and gains are **largest where the model is weakest** — which is the non-English case.

Same paper measures the failure: **out-of-domain examples degraded performance below baseline.**

**Fix:** one short, fluent, correctly-punctuated sentence **in the target language and script**, with the
contact name spelled natively. Must be re-validated against segment counts — `TranscriptionPrompt.kt`
already records that a *long* prompt collapses segments (9 → 3 on a 30-second call) and destroys speaker
labels. Also check for **dropped speech**, which is invisible in segment counts: Meetily measured prompt
carry-over causing 5 of 52 windows to echo the previous window and lose ~30 s of real speech each.

---

## 2. Segment fragmentation — merge for the summariser, never for the transcript

A whisper segment boundary is **only a timestamp token** — there is no pause logic. Timestamps were
trained on scraped subtitle cue boundaries, so "segment" means "subtitle cue". Any moment the model is
unsure which word comes next — a backchannel, overlap, band-limited audio, an inhale — flattens the text
distribution and the aggregated timestamp mass wins. That is the structural reason conversational
telephone audio over-segments. Whisper's co-author confirms there is no parameter for it and recommends
post-hoc merging.

MEASURED, that segmentation alone moves downstream quality with words held constant:

| Finding | Number | Source |
|---|---|---|
| Re-cutting an error-free transcript with ASR segmentation | **−1.6 BLEU** | Cho et al., IWSLT 2012 |
| Quality vs segment length | peaks at **8–9 words**, drops sharply below | Rao/Lane/Schultz, Interspeech 2007 |
| Intra-sentence re-segmentation, Arabic broadcast **conversation** | **+11% BLEU** | same |
| Segmentation choice alone, 5 language pairs | **5–13% relative** | SHAS, arXiv:2202.04774 |
| "Poor segmentation degrades performance almost twice as much as lexical errors" | — | Li et al. 2021 |

Our ~1.4-second segments are 3–4 words — below the cliff. **Caveat: the numbers with error bars are
machine translation, not LLM summarisation. Extrapolating is INFERRED.** The only in-domain measurement
is our own 176-lines result, n=1 but exactly this model and failure.

**Design point:** keep fine segments in `transcripts.db` — speaker labels, tappable seek and resumability
all depend on them — and merge as a **projection** when building the summariser prompt. Convergent
thresholds from the ecosystem: merge when the gap is **< 0.5 s**, stop at ~20–30 s or ~200 characters or
on sentence-final punctuation, treat anything under 3–5 s as a merge candidate regardless of gap.

### 🔴 This contradicts a plan in our own design doc

`2026-08-16-on-device-transcription-design.md` specifies *"VAD-segment first, transcribe segment-by-
segment, persist each as it completes"* for resumability. **That is the measured-worst architecture.**

Meetily A/B'd it on a real 26-minute **Arabic** meeting — same audio, same model, varying only chunk size:

| | VAD-fragmented | 30-second windows |
|---|---|---|
| ASR requests | 322 | 52 |
| hallucination segments | 17 | 11 |
| standalone "thank you" | 14 | **0** |
| code-switched words kept | 160 | **204** |

Below ~1 s of speech, **81%** of outputs were a single memorised word. Whisper zero-pads short windows
and leans on a prior trained on web subtitles.

**Our shipped code correctly feeds the whole file to one `whisper_full`. Keep it.** When the 15-minute
limit is lifted, chunk the **decode** into contiguous multi-minute PCM spans — never the transcription,
never VAD-sized pieces.

---

## 3. The multilingual traps

These are the ones that would silently make the product worse for everyone who is not the maintainer.

### 3.1 A chars-per-second quality gate would break CJK entirely

Recomputed from Coupé et al., *Science Advances* 2019 (raw data reproduced: mean 6.63 syll/s vs the
paper's 6.63) combined with orthographic density:

| Language | expected chars/s | | Language | expected chars/s |
|---|---|---|---|---|
| French | ~20.6 | | Arabic | ~13.0 |
| Spanish | ~18.8 | | Hebrew | ~12.2 |
| English | ~18.0 | | Korean | ~7.1 |
| Italian | ~17.2 | | Japanese | ~6.5 |
| German | ~16.8 | | **Chinese** | **~4.3** |

**A 4.75× spread.** A floor calibrated on Spanish or French rejects essentially **every valid Chinese,
Japanese and Korean transcript** as garbage. The same applies to gzip/entropy thresholds — tokenizer
fertility differs by script, so "2.4" means different things per language.

**Gate on script-neutral signals instead:** per-segment `no_speech_prob` and mean token probability (both
exposed by whisper.cpp and currently unbound), n-gram repetition ratio, and **words per *voiced* second**
rather than wall-clock — `SpeakerTurn` already gives voiced time per channel.

### 3.2 A hallucination denylist must be grouped by language from day one

Meetily's was English-only, so it cleaned English meetings and silently skipped everything else — their
Arabic meeting stored "subscribe to the channel" and a subtitler's credit unfiltered. Their conclusion:
*"an English meeting got cleaned and a non-English meeting did not."* We currently have no denylist at
all. Match the **whole trimmed segment**, not a substring, and group patterns by language so more are
additive.

### 3.3 Smaller traps

- **`split_on_word`** is literally `txt[0] == ' '` — always false for Chinese, Japanese and Thai, so it
  silently disables splitting on those languages.
- **`max_len`** is a no-op unless `token_timestamps=true`, and only ever *splits*. It is **not** the
  cause of our 370 segments. Rule it out.
- **Merge join character** must be `""` for CJK/Thai and `" "` elsewhere.
- **Punctuation re-chunking** on `[.?!]` no-ops on Chinese (`。？`) and Arabic (`؟ ،`).
- **Quantisation hurts non-Latin scripts more.** EMNLP Findings 2024: automatic metrics understate the
  damage by **~10×** (−1.7% automatic vs −16.0% human on Japanese). arXiv:2608.09941: Arabic 11.4%,
  Russian **16.1%** tax, *"no safe quantization level identified"*. **A WER benchmark will not detect
  this — it needs native-speaker ears.** Keep q8_0; q5_0 measured +4.95 WER on code-switched audio.
- **turbo itself** — OpenAI documents *larger degradation on Thai and Cantonese* for large-v3-turbo. See
  §5.

---

## 4. Do not do these — measured negative

- **Denoising / speech enhancement.** arXiv:2512.17562: MetricGAN+ across 4 ASR systems × 10 conditions,
  **noisy beat enhanced in all 40 configurations**. arXiv:2603.04710: large-v3 WER 0.658 → 0.774 after
  denoising, and *"the errors worsen as the Whisper model size increases"* — we run the biggest one —
  and *"the degradation is not language-specific."* Mechanism (arXiv:2201.06685): **artefacts, not
  residual noise**. If ever needed, **blend, never replace** (ω ≈ 0.3–0.8 recovers ~20% relative).
- **Neural bandwidth extension** (VoiceFixer/AudioSR class). arXiv:2606.09335: *"neural restoration
  methods paradoxically worsen WER; classical resampling more reliable for telephony."*
- **An LLM transcript-repair pass.** HyPoradise (NeurIPS 2023) Table 2: at sub-1B scale correction made
  WER **worse** on SwitchBoard (+1%) and CORAAL (+8%) — conversational telephone and accented speech,
  i.e. exactly this domain. Gains need 13B. The multilingual result needs N-best lists plus audio-derived
  noise embeddings; whisper.cpp gives 1-best only. **The input the technique needs does not exist here.**
- **Letting an LLM rewrite stored transcript text.** Meetily's "Enhance" inverted facts — *"abnormal
  outliers" → "normal outliers"*, *"May data" → "main data"*. On a record of a real conversation a
  plausible negation is worse than a garbled word.
- **Switching to a non-Whisper model.** See §5.
- **Switching runtime to sherpa-onnx.** Prebuilt `libonnxruntime.so` from Maven, no F-Droid precedent
  for building ORT from source, plus the mel-before-padding bug that disqualified it before (closed
  without a documented fix). **If a runtime move is ever needed, llama.cpp is the cheap one** — already
  vendored, same ggml, same NDK build.

---

## 5. The model landscape — everything else is disqualified

| Family | Disqualifier |
|---|---|
| **Qwen3-ASR 0.6B/1.7B** | Apache-2.0, ungated, official llama.cpp support — **no Hebrew**. And the size-viable 0.6B loses to large-v3 on every multilingual set (FLEURS 21.80 vs 8.16). |
| **Parakeet TDT v3** | CC-BY-4.0, ungated, clean MIT ggml path via `parakeet.cpp` — but **25 European languages only: 9 of our 13, no Hebrew, Arabic, Chinese or Vietnamese.** Can only ever be an *addition*. |
| **Canary** | Best multilingual accuracy in the family; **no arm64 CPU runtime exists at all.** |
| **Moonshine** | Non-English weights are **non-commercial**, registration required. Fails F-Droid and fails automated download. |
| **distil-whisper** | English only, explicitly. |
| **MMS / SeamlessM4T v2** | **CC-BY-NC-4.0.** |
| **SenseVoice-Small** | `license: other`, and zh/en/ja/ko/yue only — the "50+ languages" figure in comparison tables is wrong. |
| **Voxtral, Granite Speech, Phi-4-multimodal, Kyutai** | Language coverage; several are 3–6× over budget. |

**Two structural findings worth keeping:**

- **Hebrew is the binding constraint on the entire search space** — it eliminated the strongest technical
  contender outright. Any future scan should filter on Hebrew *first*.
- **All 13 of our languages are head languages**, which is exactly the regime where Whisper large-v3
  remains state of the art among redistributable open models. Models that beat it on massively-
  multilingual averages do so on low-resource languages where Whisper is simply broken.

### One model change worth testing: large-v3 over turbo

`large-v3-turbo` is `large-v3` with the **decoder pruned 32 → 4 layers**. The decoder holds the language-
model prior, so pruning costs most on low-resource and non-Latin-orthography languages — OpenAI names
**Thai and Cantonese** as the casualties, and unpointed Hebrew sits in the same bucket.

`ggml-large-v3-q5_0.bin` is **1.08 GB** against our turbo-q8_0 at 874 MB. **+204 MB, no runtime change,
no licence question, and it restores the 28 decoder layers turbo discarded.** Cost is decode speed.

**Important correction: OpenAI published no per-language turbo-vs-v3 table.** The linked discussion
contains a figure, not numbers; the only numeric values are for Urdu. There are **no measured deltas for
any of our 13 languages** — the absence of that data is itself the finding, and no third-party source
fills it. INFERRED, worth an A/B, not a certainty.

Also unexplored and cheap: **Q6_K** quantisation. whisper.cpp's vendored `quantize` accepts it,
k-quants allocate more precision to outlier-heavy rows — the mechanism that protects rare multilingual
tokens — and it lands between 574 MB and 874 MB. Upstream ships no k-quant whisper files, so we would
build it, which we are already set up to do.

---

## 6. The per-language catalogue — answered: **no, not as a catalogue**

Criteria: open licence, **ungated**, ≤ ~1.1 GB quantised, whisper-architecture, measurably better.

| Lang | Candidate | Licence | Measured | Verdict |
|---|---|---|---|---|
| **he** | `ivrit-ai/whisper-large-v3-turbo-ggml` | **Apache-2.0, ungated** | 0.128 → 0.071 WER (−45%), WhatsApp voice notes | ✅ strong |
| **zh** | `BELLE-2/Belle-whisper-large-v3-turbo-zh` | **Apache-2.0, ungated** | **HKUST (telephone Mandarin) CER 18.94% vs 37.32% — −49%** | ✅ strong |
| **fr** | `bofenghuang/whisper-large-v3-french` | MIT | no head-to-head published | 🟡 moderate |
| **de** | `primeline/whisper-large-v3-turbo-german` | Apache-2.0 | 2.628% vs 3.649% — modest | 🟡 moderate |
| **vi** | `vinai/PhoWhisper-large` | BSD-3 | "SOTA" claimed, **no numbers** | 🟡 moderate |
| **ru** | `antony66/whisper-large-v3-russian` | **no licence stated** | CommonVoice only | ❌ blocked |
| **ar, pl, pt, it, es, hu** | top results have **under 50 downloads** | — | — | ❌ nothing usable |

**2 strong, 3 moderate, 8 nothing.** A catalogue advertises a promise it cannot keep for eight of
thirteen. **Ship a narrow per-language override that exists where a credible model does and is simply
absent elsewhere** — not a catalogue UI with mostly-empty rows.

Speech Note's maintainer reached the same conclusion after shipping the catalogue version: *"The user is
confused because there are too many choices. If they make the wrong choice, the result will be
'rubbish'."* anti-vocale's tracker carries the same complaint.

**The acceptance rule that matters:** only take a fine-tune with a measured result on **conversational or
telephone** audio. Reject CommonVoice-only claims. That rule alone eliminates Russian and Hungarian, and
it is why BELLE-2's HKUST number is the most valuable data point in this whole survey.

**Caveat on ivrit specifically:** it is trained on ~4,700 h of **Knesset plenum** against ~350 h of
everything else, so it is domain-adapted *away* from phone conversation. Measure before shipping.

### The parked licensing dilemma is already solved

The backlog asks whether to depend on an unlicensed 574 MB re-quant or pay 1.62 GB for the official fp16.
**Neither.** The official ggml is Apache-2.0 and ungated, `quantize` is in our tree, and the design doc
already plans to mirror models on our own release tag. Quantise it ourselves to q8_0 → **~874 MB,
byte-identical to the tier already shipping**, under a licence permitting redistribution, with a SHA-256
we control. The two existing constraints stand and are correct: **choose the model at download time**,
and **gate it on the language pin**, since ivrit's detection head is degraded by fine-tuning.

---

## 7. Ranked plan

### Verify first — could reframe everything

0. **Check whisper.cpp's mel-preprocessing against discussion #1035**, which reports whisper.cpp scoring
   **30–50% relative worse WER than OpenAI's Python Whisper with the same model**, traced to wrong mel
   padding, missing stage-2 reflective padding, wrong frame count and incorrect symmetric-FFT-bin
   aggregation. A grep against vendored v1.9.3. **If any of it is live, it dwarfs every item below.**
0b. ~~**Find what annotates a segment with a different language.**~~ **ANSWERED 2026-08-26.** It is
   neither our code nor code-switching: whisper emits the *string* `*ערבית*` as segment **text** — it
   transcribes a language tag rather than the speech. It happened only with **VAD and beam-5 together**,
   at 0 s, 34 s and 64 s of a call that opens with music. VAD packs the music against the first words,
   beam makes the wrong hypothesis more confident than greedy would, and rolling conditioning re-injects
   the tag as context for the next window. Beam is now reverted; see §1.3.

### Free, do now — verified defects

1. **Port the prose-join to `forChunkJson`** (§1.1), conditional on absent speaker labels.
2. **Port the other four measured lines** from `forChunk` to `forChunkJson`.
3. **Stamp only paragraph starts**, not every segment — falls out of (1). Currently ~370 markers ≈
   2,200–3,000 tokens of `[m:ss]` per call, a third of the chunk budget, and every marker is an
   invitation to fabricate one (measured: 11 fabricated, 3 past the end of the recording).
4. **Bind `whisper_full_get_segment_no_speech_prob`** — exposed, unbound. whisper.cpp's own drop rule is
   *conjunctive*, so a **confident** hallucination over silence is emitted and poisons the rolling
   prompt. Use it to exclude segments from summariser input and flag them in the UI — **never to delete
   stored text**.

### Measure, then decide — one real call each

5. **Ship Silero VAD** (864 KB, bundle it). Trim dead air only, never slice speech.
6. **Beam-5**, landing with or after VAD, with wall-clock measured on device.
7. **`n_max_text_ctx`** — 0 vs 64 vs default, both directions measured.
8. **Rewrite the initial prompt** into the target language and script; validate on segment counts **and
   dropped speech**.
9. **Merge segments for the summariser view** — 0.5 s gap, 200-char cap, never across a speaker change.
10. **FFT the three capture paths above 8 kHz**; fix the decimator only where it is dirty.

### Worth a spike

11. **ivrit for Hebrew, self-quantised and self-hosted** — a decision, not an investigation.
12. **BELLE-2 for Chinese** — the only telephone-domain evidence in the survey.
13. **large-v3-q5_0 vs turbo-q8_0** A/B (§5).
14. **Q6_K** vs q8_0 on Hebrew and Arabic.
15. **A WER harness.** `RealCallBenchmark` already transcribes on device; it needs scoring against a
    reference. **Without it every item above is unfalsifiable** — and because automatic metrics
    understate multilingual damage ~10×, it needs native-speaker spot checks, not just a number.

### Known, large, and not currently actionable

16. **Two speakers mixed to mono is probably our largest single error source.** Vanilla Whisper scores
    **54.2% WER on Libri2Mix** and *"consistently predicts identical results for different speakers"*.
    Masking or separating first makes it dramatically **worse** (79.1 vs 16.5). The fix is
    diarization-conditioned decoding, which has no ggml implementation. Research-grade.

---

## 8. On the summariser gap specifically

The reference pipeline uses **Claude Sonnet** — whole transcript in one call, no output cap. We run
**Gemma 4 E2B at Q4_K_M**: roughly **2B effective active parameters at 4 bits**, greedy, GBNF-constrained,
capped at **900 output tokens** and instructed *"at most 5 items in each list, one short line each"*.

Part of that gap is self-inflicted and part is capability. What a phone-sized model **can** do: extract
things stated literally, produce a serviceable topical summary, hold the right language, emit valid
structure — and our grammar plus citation-stripping plus de-duplication force the structural half to be
right in a way the reference pipeline does not bother with. What it will **not** match: inference,
nuance, judgement about what mattered, and prose that reads like a person wrote it.

**Set the product expectation there:** a good structured extract of a call, not what Claude wrote.

One architecture worth an A/B, taken from Meetily: they **force English reasoning and then translate as a
separate mechanical pass**, rather than asking the model to reason in the target language. A 2B-class
model reasons measurably better in English. Both paths end in Hebrew; the difference is where the
thinking happens.

---

## Sources

Whisper paper arXiv:2212.04356 (Tables 7, 8, Appendix D) · arXiv:2506.03681 (call-centre fine-tuning) ·
WhisperX arXiv:2303.00747 · arXiv:2501.11378 (non-speech hallucination) · Careless Whisper
arXiv:2402.08021 · arXiv:2309.07081 (in-context prompting) · HyPoradise arXiv:2309.15701 · RobustGER
arXiv:2401.10446 · arXiv:2512.17562, arXiv:2603.04710, arXiv:2607.11157, arXiv:2201.06685 (enhancement) ·
arXiv:2606.09335 (resampling) · Cho IWSLT 2012 · Rao/Lane/Schultz Interspeech 2007 · SHAS
arXiv:2202.04774 · Coupé et al. *Science Advances* 2019 + data · EMNLP Findings 2024 (quantisation) ·
arXiv:2608.09941 · arXiv:2412.05589, arXiv:2409.09543 (overlapped speech) · whisper.cpp #1035, #1017,
#3744, #2286, PR #3065, PR #3592 · openai/whisper #223, #1260, #2363 · Meetily PR #679, #592, #602 ·
Hyprnote/Anarlog docs · dsnote #311 · anti-vocale #49, #60 · BCR #264 · ShizuCallRecorder #73

---

# Appendix — the summariser, researched the same day

Four hypotheses were **disproven by direct code inspection**, and they are recorded here so nobody
re-investigates them:

| Hypothesis | Status |
|---|---|
| The GBNF grammar blocks non-Latin scripts | **Disproven.** `char ::= [^"\\]` is a *negated* class; Hebrew, Arabic and CJK pass. (Hyprnote ships the broken `[A-Z]` form. We do not.) |
| Missing BOS / wrong Gemma chat template | **Disproven.** Both tokenize calls pass `add_special=true`, and we correctly send one `user` message — Gemma has no system role. |
| A Latin-calibrated token estimate overflowing `n_ctx` | **Disproven.** `n_ctx` comes from the real tokeniser, not `chars/4`. |
| The chunker silently dropping text | **Disproven.** It accumulates whole segments; no emit/advance desync. |

## The five actual causes

**1. Greedy decoding with unbounded arrays is the textbook repetition setup.** Holtzman et al.
(ICLR 2020, Table 1) measured **greedy producing a repetition loop in 73.66% of generations** — the
worst configuration in the paper, against 28.94% for beam and 0.28% for humans. The mechanism is
self-reinforcing: each repetition makes the next more likely.

`SummaryGrammar`'s arrays are **unbounded** — `array ::= "[" ws (string (ws "," ws string)*)? ws "]"`.
"At most 5 items" is a prompt *request*; the grammar permits infinitely many. That is precisely the
documented trap, and it matches our own recorded symptom: *"four decisions, three of them the same
sentence."*

**2. Hard schema constraint costs accuracy below 3B.** *The Constraint Tax* (arXiv:2605.26128; 15,000
generations across Qwen2.5-0.5B/1.5B/3B, SmolLM2, Phi-2) measured hard schema decoding taking validity
61.5% → 100% while answer accuracy fell **19.7% → 11.0%** and wrong-but-schema-valid outputs rose
**49.5% → 88.9%**. Their words: *"the error is semantic, not structural"*, and their prescription is
**"reason free, constrain late."** Scale-dependent — JSONSchemaBench found constrained decoding *helps*
by 3–4 points at 8B. We are on the wrong side of that line.

**3. The thinness is largely self-inflicted.** Zero-Shot Length-Controllable Summarization (NAACL 2025)
found models show *"near-perfect compliance for structural measures"* such as item counts while failing
word-count targets — so *"at most 5 items, one short line each"* is being obeyed faithfully, against real
content. Chain of Density (2023, 100 articles, expert annotators): the entity-sparsest summary won
**8.3% of first-place votes, dead last**; 61% went to summaries with ≥3 densification steps.

**4. The map-reduce merge is where specifics die.** Pratapa & Mitamura (NAACL 2025): *"the best
intermediate recall is significantly higher than the final summary recall, even outperforming
full-context"*, and hierarchical merging *"often skips details such as entities and numerals."*
Enlarging chunks gave *"minimal improvements"* — so it is the merge, not the chunk size. We cap each
chunk at 5 items and then cap the merge at 5 again.

**5. The model is below the multilingual competence floor.** Gemma 4 E2B is *effective 2B*, and 2B is
where multilingual generation collapses. Same family, same recipe: Gemma 3 1B scores **24.9 on
Global-MMLU-Lite — at chance** — while sitting 13.8 points above chance on English MMLU. On the Hebrew
LLM leaderboard, **gemma-4-E2B 50.0 vs gemma-4-E4B 60.7**. On SEA-HELM Vietnamese, E2B 49.85 vs Gemma 4
31B 77.09.

## Free fixes — hours, no download

1. **Bound the arrays in the grammar.** GBNF supports `{m,n}` (verified in the pinned tree). `{0,7}`
   guarantees termination and kills the unbounded-array loop **without touching the sampler**.
2. **Delete "keep each item to one short line"; raise the cap 5 → 8.** Keep the anti-repetition line.
3. **Put a free-text field first in the schema.** Reason-free-then-constrain; field order is
   load-bearing (one reported case lost 15 points to reordering alone).
4. **Stop re-capping at the merge.** `CallSummary.concatenate` already exists — A/B it against the LLM
   merge.
5. **Make truncation non-fatal.** `keyFacts` is REQUIRED and our fixed key order puts it **last**, so a
   Hebrew answer that hits the 900-token cap loses the **entire chunk**. Close the object
   programmatically instead.
6. **Keep greedy; do NOT enable `repeat_penalty`.** A token-count penalty cannot distinguish "looping"
   from "listing the eighth item" and produces schema-valid-but-empty output. Use DRY, or an external
   n-gram detector plus regenerate. Do **not** raise temperature globally — measured word-level language
   pass rate falls to 72.0% (Japanese) / 69.5% (Chinese) at T=1.

## Worth a spike

7. **Gemma 4 E4B QAT** — `unsloth/gemma-4-E4B-it-qat-GGUF` UD-Q4_K_XL is **4.22 GB**, Apache-2.0 and
   ungated, +0.76 GB over the current file. Ship as a RAM-gated tier, as PocketPal does.
8. **Draft-then-constrain.** Unconstrained prose pass, then constrained re-serialisation. DCCD measured
   1B GSM8K **15.24% → 39.0%**, gains largest for the smallest models. **`SummaryPrompt.forChunk`
   already exists and is unused — this is mostly wiring.**
9. **One few-shot example in the target language.** 5-shot took a model 86.2 → 99.0 language pass rate.
   **Trap:** cross-language demos regressed an instruct model **98.6 → 68.3**, so it needs 13 example
   sets — real work.

## Not worth it — measured

- **LLM transcript repair.** MEDSAGE: *"denoising consistently harmed summarization performance across
  all experiments"*; sub-8B denoisers both ineffective and prone to injecting entities. Below 7B,
  CoVoGER measured it *"noticeably degrades performance."*
- **Self-refine.** Vicuna-13B failed *with oracle feedback*, and failed **by repeating its own output**.
  Llama-2-70B went 62.0% → 36.5% on GSM8K after two rounds.
- **A 7–9B model.** ~5 GB of weights lands near 6 GB peak against a measured ~3.85 GB projection today,
  on a 7.4 GB phone.
- **Beam search** (cross-lingual language pass rate 73.9 → 65.6) and **language-specialist summarisers**
  (specialisation buys +3–8 points; a size tier buys 10–25, and no specialist covers 13 languages).

## The ceiling — what to promise

**A phone-sized model cannot produce actionable minutes in mid-resource languages and should not be sold
as doing so.** The best on-device-class datapoint available is gemma-3-**12b** winning 39.48% of Hebrew
summarisation comparisons against a frontier reference — and that is a 12B on a desktop. For calibration,
GPT-4 zero-shot scores ROUGE-1 **13.59** on Hebrew news summarisation; the task is hard for everything.

Defensible scope: **intent, a 2–4 sentence gist, and verified timestamp anchors.** Not reliable:
action-item *ownership*, exact figures, and decisions in he/hu/vi/ar/pl. Expect a visible split between
en/es/fr/de/pt/it and the rest — that is the shape of the technology, not a bug.

**The property that must never regress is faithfulness.** The existing selection bar — Gemma invented
nothing in 4 of 4 runs — is the right one.

## Caveats on all of the above

- **The model choice rests on 4 runs, 2 invented ~500-character transcripts, 2 languages, clean text.**
  Noisy real ASR — the actual input distribution — was never in the selection, and Hungarian,
  Vietnamese, Arabic and Polish were never tested at all.
- **Nobody has measured whether JSON constraining hurts non-English more than English.** The mechanism is
  coherent; the joint claim is inference.
- **Do not build the eval on ROUGE** — it *anti-correlates* with human judgement in Hebrew
  (r ≈ −0.16, p < 2.4e-5). Global PIQA covers all 13 languages. A per-response Unicode-block ratio
  (script leakage) is trivial to compute and is the real production metric.
- **`SummaryModel.kt`'s licence comment was wrong and is now fixed:** Gemma 4 is **Apache-2.0 and
  ungated**, not the Gemma Terms of Use. The licensing objection to the model never existed; the 3.46 GB
  objection to *bundling* stands. That error had already propagated into the README's credits.
