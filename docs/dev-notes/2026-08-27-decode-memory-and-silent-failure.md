# ✅ VERIFIED 2026-08-27 — decode peak memory, and silent-failure reporting

Three fixes on branch `fix/decode-peak-memory`. **Confirmed by the maintainer on the OP12,
2026-08-27**, on a real carrier call, a real 14:41 transcription and a real VoIP call. What was
actually observed is recorded below — including the one thing that remains untested.

| Commit | Change | Status |
|---|---|---|
| `cca1ee5` | Stop widening the whole call to float32 at 48 kHz | ✅ VERIFIED 2026-08-27 |
| `6836aff` | Decode straight into shorts instead of staging bytes | ✅ VERIFIED 2026-08-27 |
| `892509e` | A header-only file is no longer reported as a successful call | ✅ VERIFIED 2026-08-27 (no regression) |

## What is claimed, and how strongly

**📐 CALCULATED, not measured — peak decode memory ~259 MB → ~144 MB at fifteen minutes, 48 kHz.**
This is arithmetic over the allocations, not a heap dump. The two allocations removed:

1. `pcm16ToMonoFloat` widened the entire call to float32 *while still at the input rate*, so 86.4 MB of
   `ShortArray` sat alongside 172.8 MB of `FloatArray` — 259 MB against a 256 MB heap.
2. `decodePcm` staged the call as bytes in a `ByteArrayOutputStream`, called `toByteArray()`, then
   copied that into a `ShortArray` — three full-length buffers at overlapping moments, peaking near
   220 MB on its own. Fixing only (1) would have left the ceiling exactly where it was.

**✅ established, because the compiler and tests enforce it — output is unchanged.** Six equivalence
tests pin the new single-pass path to the old two-step one across mono, stereo, 44.1 kHz, the target
rate, the aliasing guarantee and the edge cases. The anti-alias filter was tuned against thirteen real
recordings and must not shift.

**The silent-failure fix is a narrower claim than it sounds.** `sizeBytes > 0` was the entire test for
"did this call record", so all seven of the 98-byte files from August were classified `Verified`. There
is now a 1 KiB floor and a distinct `FailureReason.NO_AUDIO`. **It catches a file with no samples. It
does not catch a full-length file of silence** — that needs a PCM peak check on the capture path, the
same follow-on that would let carrier capture answer `farPartyHeard` instead of passing null.

## What has NOT changed, on purpose

`TranscriptionLengthLimit.MAX_MINUTES` is still **15**. The arithmetic says it can roughly double, but
this limit was *already* set once on an empirical wall nobody understood, and setting it again on a
calculation would repeat the mistake. It moves only after a long call is measured on a device.

## What the maintainer observed, 2026-08-27

1. **Ordinary carrier call — fine.** This was the real regression risk: the new 1 KiB floor could have
   started rejecting genuine recordings. It did not. `892509e` is clear.
2. **A 14:41 recording transcribed fine.** Hebrew quality is unchanged and still poor, which is the
   known ceiling rather than anything these commits touch — see the transcription-quality research. What
   matters here is that a long real call decoded and transcribed with no garbling, which is the
   behavioural proof that the single-pass resampler is equivalent to the two-step path it replaced.
3. **VoIP call recorded fine.** The other caller of the outcome classifier, also clear.

### ✅ VERIFIED 2026-08-27 — the 20-minute limit holds, and now it is measured

14:41 sat *below* the old 15-minute guard, so nothing had exercised the freed memory — and the guard
was the only thing preventing the test. The maintainer chose to raise it rather than add a test-only
override, on the grounds that a real long call is the only way to find the honest ceiling.

📐 Peak is about **9.6 MB per minute** — 5.76 MB of interleaved PCM plus 3.84 MB of 16 kHz float
output. 20 minutes is 192 MB against a 256 MB heap; 25 would be 240 MB and leave nothing for the app,
which is why it is 20 and not higher.

**MEASURED on the OP12, an 18:58 call transcribed successfully.** Java heap sampled every 30 s
through the run: a peak of **132 MB observed**, settling to 95–111 MB while whisper worked, then
released to ~9 MB on completion. Against a 256 MB ceiling that is comfortable, and the decode spike
that precedes it got through unobserved but evidently survived. The arithmetic predicted ~182 MB for
this length and reality came in below it, so the estimate is conservative in the safe direction.

**The heap ceiling is the ROM's, not ours.** `dalvik.vm.heapgrowthlimit` is **256m on the OP12** and
**384m on the OP9 Pro** — the weaker phone has the larger ceiling, because the limit is an OEM policy
value and has nothing to do with installed RAM. For heap-cap questions the **OP12 is the stricter
device**. `dalvik.vm.heapsize` is 512m on both, which is what `android:largeHeap="true"` would buy;
we deliberately do not set it, because a larger heap makes the app a fatter target for the low-memory
killer at exactly the moment a call is being recorded.

### Fixed after this round — VoIP now confirms a recording (`031079e`)

The gap below was real and is closed: `showRecordingEnded()` is now shared, and the VoIP path calls it
on a Verified outcome only. 🧪 VERIFYING — needs one app call to confirm the toast and vibration land.

### Found while checking, not caused by these commits

**A VoIP recording ends with no confirmation of any kind.** The maintainer noticed the end-of-recording
toast and vibration were missing after a VoIP call and asked whether these changes broke it. They did
not: `handleStateChangeToasts` is called from exactly one place, `RecordingForegroundService`, and
`VoipRecordingCoordinator` carries an explicit comment that the VoIP path *deliberately* does not go
through it. VoIP can raise an error notification and nothing else, so it has never produced that
feedback. Pre-existing, and it lands squarely on the silent-failure theme — logged in the backlog.

## Related

- Root cause and the dead ends: `2026-08-27-competitive-research-synthesis.md` (Tier 0), on branch
  `docs/competitive-research-2026-08-27`. **Its Tier 0 entries need the same 🧪 VERIFYING marker when
  these branches meet.**
- 🚨 `offset_ms`/`duration_ms` are **not** a route to chunked decoding — confirmed twice at the source.
  `whisper_full` builds the mel for the whole file before those parameters are read.

---

## ✅ VERIFIED 2026-08-27 — Batch 1 (A2, A5, A6, A8)

Confirmed by the maintainer on the OP12: an ordinary carrier call was fine, transcription showed the
new foreground notification, and a VoIP call produced speaker labels for the first time.

| Commit | Change | Status |
|---|---|---|
| `1590acc` | A6 — a call with no number no longer throws through the ignore check | ⚠️ ships unverified |
| `5c09077` | A5 — `IntentCompat` for the Android 13 parcelable bug | ⚠️ **untestable here** |
| `2288d59` | A2 — transcription and summarisation run in the foreground | ✅ VERIFIED 2026-08-27 |
| `a475f17` | A8 — VoIP keeps the speaker separation it already had | ✅ VERIFIED 2026-08-27 |

**Two are honestly unverified and must not be described otherwise.** A5 fixes a platform bug that only
exists on Android 13; both test devices are newer, so no test on this hardware can exercise it — it
ships on code review. A6 needs a call that arrives with no number (Teams, a withheld number, a short
code), which cannot be summoned on demand; it may stay unverified indefinitely.

**A8 was larger than the research described.** Feeding the detector was only half: nothing *collected*
the turns for VoIP, because `SpeakerTurnsRepository.collectAfterCall` is called only from
`RecordingForegroundService`, which the VoIP path deliberately bypasses. Both halves were needed, and
either alone would have looked correct while producing nothing.

## ✅ VERIFIED 2026-08-27 — Batch 2 (A3, A4)

| Commit | Change | Status |
|---|---|---|
| `9227f80` | A3 — bound the strings and whitespace the grammar left open | ✅ VERIFIED 2026-08-27 |
| `3453e30` | A4 — say so when the grammar is being ignored | ✅ VERIFIED 2026-08-27 (no regression) |

**The trap worth carrying out of this one:** `llama-grammar.cpp` silently rewrites a repetition bound
**above 2000** to `UINT64_MAX` — unbounded. A future edit that raises a limit to be generous would
therefore restore the original defect invisibly. There is now a test that fails if any bound reaches
2000, because this is not something anyone would catch by reading.

A4's value is not that it changes output — it should not — but that a grammar which stops applying can
no longer do so in silence.

## ✅ VERIFIED 2026-08-27 — Batch 3 (A7)

| Commit | Change | Status |
|---|---|---|
| `f5d5063` | A7 — nothing appears in the user's folder until the recording is complete | ✅ VERIFIED 2026-08-27 |

Confirmed on the OP12 across both capture paths: a carrier call and a VoIP call each recorded, landed
in the folder, and **appeared only after the call ended** — which is the behaviour the change exists to
produce, not merely a sign that nothing broke. Google Drive upload still works.

Two things this pass taught that the research description did not contain:

- **"Record private, publish complete" was not enough on its own.** Creating the destination up front
  and filling it at the end still leaves a **0-byte file** in the folder for the whole call, and a sync
  tool that uploads once takes the empty one. The destination had to not exist at all until there was a
  finished recording. Truncated and empty are the same bug wearing different clothes.
- **The dangerous part compiled cleanly.** `RecordingForegroundService` read `currentRecordingUri`
  *before* `release()`, and publishing now happens inside release. Every test passed and it would have
  silently skipped the call-log rename fallback. Isolating A7 into its own cycle is what made it
  findable.

## Where A0–A8 finished

| | Change | Status |
|---|---|---|
| A0 | A header-only file is not a successful call | ✅ VERIFIED |
| A1 | Decode peak memory 259 MB → 144 MB, limit 15 → 20 min | ✅ VERIFIED (measured: 132 MB peak on an 18:58 call) |
| A2 | Workers run in the foreground | ✅ VERIFIED |
| A3 | Grammar strings and whitespace bounded | ✅ VERIFIED |
| A4 | An ignored grammar says so | ✅ VERIFIED |
| A5 | `IntentCompat` for the Android 13 parcelable bug | ⚠️ **UNVERIFIED — untestable on this hardware** |
| A6 | A call with no number does not throw | ⚠️ **UNVERIFIED — needs a no-number call** |
| A7 | Nothing appears in the folder until complete | ✅ VERIFIED |
| A8 | VoIP keeps its speaker separation | ✅ VERIFIED |

A5 and A6 stay unverified rather than being rounded up. A5 fixes a platform bug that exists only on
Android 13 and both test devices are newer; A6 needs a call that arrives with no number, which cannot be
summoned on demand.

---

## 🚦 A Hebrew test cannot clear a quality change — open items

Stated by the maintainer on 2026-08-27: *"in Hebrew this test isn't good enough, I will have to wait for
feedbacks from people."*

Hebrew sits at the bottom of what the model does well and is explicitly **not** the product target. A
Hebrew transcript that "looks ok" says only that nothing exploded — real quality movement is invisible
there, in either direction. So a Hebrew result can **falsify** a change (which is exactly how the
beam-search regression was caught) but can never **confirm** one.

**Quality changes therefore have two gates**: the maintainer confirms nothing broke, and then a native
speaker of a target language reports. Do not close on the first, and do not let an item drift into
"verified" because time passed. If feedback never arrives, it stays open — that is the honest state.

| Change | Shipped | Still needs judging |
|---|---|---|
| **B3 — `entropy_thold` 2.4 → 2.8** (`3148337`) | 2026-08-27 | Whether the stricter repetition test helps or hurts. It discards and re-decodes more often; the risk is a dense but genuine passage thrown away and re-decoded worse. Hebrew showed "ok", which settles nothing. **One number to revert.** |
| **B1 — QAT summariser** (`42f9f10`) | 2026-08-27 | Same model, better quantisation, 842 MB smaller. Should be equal or better; nobody has judged output in a target language. |

---

## ❌ NOT WORKING 2026-08-28 — B5, chunked transcription (`67f177f`, reverted in `4801144`)

> **Superseded — see the 2026-08-28 entry at the end of this section.** The entry stays because the
> failure was real and the reasoning below is what eliminated four wrong explanations. What it got
> wrong is recorded there.

Shipped, tested on a real call, and **made the transcript worse**: lines that did not match the audio,
and repeated lines. Reverted immediately and the working build reinstalled.

**Do not retry this by tweaking the offsets.** Three cheap explanations were checked and are all dead:

- whisper does **not** accumulate results across calls — `result_all.clear()` runs at the top of
  `whisper_full` (`whisper.cpp:6838`), so chunk *n* cannot inherit chunk *n−1*'s segments.
- whisper **does** map VAD timestamps back to the original timeline (`vad_mapping_table`), so silence
  trimming is not what shifted them.
- our own `segmentCount` reads `whisper_full_n_segments(ctx)` live and caches nothing.

**Live candidates, none yet distinguished:**

1. **`presentationTimeUs` may not be absolute after `seekTo`.** The design reports the decoder's true
   start and stitches from it; if that value is rebased to the seek point it is 0 for every chunk, and
   every timestamp after the first chunk is wrong. This alone explains "lines don't match voice".
2. **Codec priming after a seek.** Opus pre-skip and AAC priming samples are emitted after a seek and
   are not real audio, so each chunk may start a few tens of milliseconds off.
3. **Cold starts hallucinate.** Each chunk runs with `no_context = true`, so whisper begins the 10 s
   run-up with nothing to condition on — exactly the situation that produces repetition loops. A
   looping segment that extends past `keepFromMs` survives the overlap filter, which would explain
   "repetitive lines" while the drop rule is working as designed.
4. **The seams may simply cost more than predicted.** Our own `n_max_text_ctx = 0` result already
   showed that removing rolling conditioning fragments a call badly. Four seams may be four too many
   for conversational speech, in which case the whole approach needs prompt-token carry before it is
   worth anything.

**What would settle it, and what should happen before any further attempt:** an A/B on *one* recording
— whole-file versus chunked, same file, same settings, transcripts diffed. Every hypothesis above
predicts a different signature, and guessing between them from a description of the damage is how this
went wrong in the first place. `DecodeVariantBenchmark` already exists for exactly this shape of
question.

**Also note what this does NOT block.** The memory work in A1 stands on its own and is verified; B5 was
about removing the length limit entirely, not about fixing the OOM. `MAX_MINUTES` stays at 20, which is
measured.

> ⚠️ **That last sentence was true when written and is now wrong.** `MAX_MINUTES` is **60** as of
> `bf989c3`, because chunking bounds peak memory by one chunk rather than by call length. Left in place
> rather than edited away, so the arithmetic that produced 20 stays legible.

### ✅ 2026-08-28 — the seek probe eliminates two of the four hypotheses

`ChunkSeamBenchmark#seekDrift`, OP12, a synthetic 20-minute Opus file (the question is about container
seeking, so real speech is not needed and the maintainer's recordings are not touched). 163 seconds.

| chunk | requested from | decoded from | drift |
|---|---|---|---|
| 0 | 0 | 0 | 0 ms |
| 1 | 290 000 | 289 993 | −7 ms |
| 2 | 590 000 | 590 993 | +993 ms |
| 3 | 890 000 | 888 993 | −1007 ms |

**Hypothesis 1 (rebased timestamp) is dead** — the seek reports an absolute position. **Hypothesis 2
(codec priming) is dead as a cause**, and the interesting part is that the drift is up to ±1 second,
which is Ogg page granularity and far larger than priming — yet harmless, because the design stitches
from the *reported* start rather than the requested one. That was the right call and it held.

Sample counts confirm the ranges: chunk 0 is 300.0 s, chunks 1–3 are ~310 s, i.e. the target plus the
10-second run-up.

**So the decode and stitch layers are sound, and the damage came from whisper.** What remains is
hypothesis 3 (cold-start hallucination, predicting repetition clustered near seams) versus hypothesis 4
(the seams simply cost more than predicted). Telling those apart needs the full
`wholeFileVersusChunked` arm, which needs **real speech** — a synthetic tone transcribes to nothing.

**Setup note for the next run:** the instrumented app cannot read `/sdcard` under scoped storage. Grant
it first, or every run silently reports OK while skipping on an assumption:

    adb shell appops set --uid com.baba.callvault.instrtest.test MANAGE_EXTERNAL_STORAGE allow

### ✅ 2026-08-28 — the desktop A/B clears the chunking algorithm, and finds a shipping crash instead

Run on the Mac against the maintainer's real 26:41 Hebrew call, using **whisper-cli built from our own
vendored submodule** (ggml `371b5a75`, the commit the app ships) so the decoder is the same code.
Whole-file arm: **73 seconds**. The same work on the phone had taken over thirty minutes and been
killed twice.

| | segments | chars | repeated lines |
|---|---|---|---|
| whole-file | 402 | 17 924 | 4 distinct, 5 extra |
| chunked (6 passes, 5 min + 10 s run-up) | 398 | 17 815 | 4 distinct, 5 extra |

Characters per five-minute bucket differ by −141 to +111 out of ~3 500, i.e. within ±4%, with **no
bucket losing content**. Duplicate lines are *not* clustered at seams (1 near, 8 away) and are the same
duplicates the whole-file arm produces.

**Hypotheses 3 and 4 are both dead.** Cold starts do not hallucinate at the seams here, and the seams
do not cost meaningful text. Combined with the seek probe, **all four original hypotheses are now
eliminated and the algorithm itself is sound** — plan, run-up, stitch and drop-rule all behave.

**So what broke on the phone was the Android implementation, not the design.** And the strongest
candidate is no longer speculative: the same run uncovered a **hard crash on the normal transcription
path** — `NewStringUTF` aborting the process on malformed UTF-8 from whisper (fixed in `3f1222e`). A
run that dies mid-transcription is a perfectly good explanation for a transcript that does not match
the audio, and Hebrew is exactly where that crash bites, because nearly every character is multi-byte.

**Next step for B5, when it is picked up again:** re-land the chunked path and retest on the phone *with
the UTF-8 fix in place*. If it is clean, the feature was only ever blocked by a bug in a different part
of the code.

### ✅ VERIFIED 2026-08-28 — the B5 regression is gone · 🧪 the feature is NOT settled

`bf989c3` on the OP12. The maintainer transcribed **the same 26:41 Hebrew call** the desktop A/B was run
against and reported the transcript **good** — none of the damage that killed `67f177f`: no lines that
failed to match the audio, no repeated lines.

**What is verified is narrow and worth stating precisely: the structural regression is gone.** That is
the one thing a single Hebrew read can establish, because the old failure was gross enough to be
visible without judging wording. It is *not* a verdict on transcription quality.

**🚦 HOLD — do not close B5, and do not write it into the README, on one test.** Decided with the
maintainer 2026-08-28: **one user, one call, one language is not enough**, and the whole point of the
marker convention is that a green result from a single observer is still a sample of one. B5 stays open
until several users have used it on real calls across more than one language. Specifically wanted:

- the **French tester** (2.1.2's audience) on a call long enough to cross a chunk seam — over five
  minutes, ideally over ten, since seams are the only thing chunking changes;
- at least one **long** call, over the old 20-minute ceiling, which nobody outside the maintainer has
  been able to attempt until now;
- a second Hebrew opinion, ideally a native speaker, per [[hebrew-cannot-clear-a-quality-change]].

**If a report comes back bad, ask for the per-pass drift log lines, not a description.** `bf989c3`
emits asked-vs-actual start, drift, and audio length for every pass. Four lines of numbers identify a
stitching fault immediately; a description of the damage is what sent the first investigation down four
dead ends.

**Compare any report against desktop ground truth for that same 26:41 call** — whole-file 675 segments
/ 17 876 chars / 25 repeated lines, chunked 534 / 17 644 / 14. Chunked is *cleaner* there, so a device
result markedly worse than that is an Android problem, not a chunking one.

**Consequences that are now live rather than pending:**

- `MAX_MINUTES` is **60**, up from 20. Peak memory no longer depends on call length — it is bounded by
  one ≈ 6-minute chunk — so the ceiling is now about when a *transcript* stops being the useful
  artefact, not about the heap. 📐 That 60 is a judgement, not a measurement: **if a long call ever
  dies rather than being refused, this is the number to lower**, and that failure would be worth more
  than the calculation.
- The guard that stopped the maintainer testing long calls at all is gone, which is what makes the
  long-call feedback above possible for the first time.

### 🛠 Use the desktop harness for decoding questions

`whisper-cli` builds from `third_party/whisper.cpp` with the Android SDK's own cmake
(`~/Library/Android/sdk/cmake/3.22.1/bin/cmake`, since Homebrew cmake is not installed) and runs a
26-minute call in **73 seconds** against **thirty-plus minutes on the phone**, with no ColorOS
foreground trap, no instrumentation, and no risk to a daily driver. The maintainer suggested it after
two failed device runs; it should have been the first move.

It answers anything about *decoding* — chunking, thresholds, prompts, models. It cannot answer anything
about Android: JNI, memory limits, the capture paths, or the crash above. Use the phone for those and
only those.

    ffmpeg -i call.ogg -ar 16000 -ac 1 -c:a pcm_s16le call.wav
    whisper-cli -m <model> -f call.wav -l he -bs 1 --entropy-thold 2.8 \
      --vad --vad-model app/src/main/assets/ggml-silero-v5.1.2.bin -oj -of out
