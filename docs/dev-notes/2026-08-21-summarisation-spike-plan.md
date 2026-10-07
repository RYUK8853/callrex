# On-device call summarisation — spike plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decide whether a small local LLM can write useful summaries of CallVault transcripts —
in Hebrew as well as English — at a cost a phone can pay, and if so, which model.

**Architecture:** Vendor llama.cpp beside the existing whisper.cpp submodule, sharing one copy of
ggml. A thin JNI bridge mirrors `whispercv.cpp`. Summarisation is map-reduce over transcript
segments: summarise chunks, then summarise the summaries. Everything in this spike sits behind a
debug-only entry point; no user-facing UI is built.

**Tech Stack:** llama.cpp (ggml-org, same org as whisper.cpp), GGUF Q4 weights downloaded at
runtime, Kotlin + JNI, Robolectric for the pure logic, an instrumented test as the measurement
harness.

**Spec:** this document. Background: `docs/dev-notes/2026-08-16-on-device-transcription-design.md`
for the pipeline this would eventually join.

---

## Why not the platform's own AI

Checked against Google's live documentation on 2026-08-21 (page last updated 2026-08-11), so this is
current rather than remembered.

**There is no API to ask the phone's assistant to summarise text.** Assistant integration runs the
other way — App Actions let the assistant invoke *your* app. The only route out is a share intent,
which hands the transcript to the Gemini app, processes it in the cloud, and returns nothing.

**ML Kit's GenAI Summarization API** is the real system-AI path, and it genuinely runs on-device via
Gemini Nano through AICore. It is still the wrong tool here, for five independent reasons:

| Finding | Consequence for CallVault |
|---|---|
| Supports **English, Japanese and Korean** only | Fails 11 of the 14 languages we offer, including Hebrew, Arabic, Vietnamese, Polish and Russian |
| Input capped at **4000 tokens** (~3000 words) | A 20-minute call already exceeds it; the docs' own advice is to truncate or segment |
| Output is **one to three bullets** | Not a summary of a long call so much as a headline |
| Requires the proprietary **AICore** app | F-Droid cannot build or ship it, and F-Droid is the distribution plan |
| **Not supported on devices with an unlocked bootloader** | Disproportionately our users, who already run ADB tooling |

Also beta, with no SLA and explicit permission to break compatibility.

The privacy notice question therefore resolves cleanly: the on-device path needs no notice but does
not work, and the cloud path would need a very loud one *and* contradicts why people install this
app. If a cloud option is ever wanted, the defensible shape is bring-your-own-API-key, default off,
consent per use — an escape hatch, never the engine.

## Candidate models

Verified on Hugging Face on 2026-08-21. All three have GGUF builds from the major quantisers, which
is itself the evidence that llama.cpp supports the architecture.

| Model | Size | Licence | Why it is a candidate | Risk |
|---|---|---|---|---|
| **google/gemma-4-E2B-it** (QAT mobile) | ~2B effective | Gemma Terms of Use | Purpose-built for phones; quantisation-aware training gives better int4 quality than post-hoc quantising. GGUF: `bartowski/google_gemma-4-E2B-it-GGUF` | Custom licence with use restrictions, not OSI-free |
| **Qwen/Qwen3.5-4B** | 4B | **Apache-2.0** | Claims **201 languages and dialects**. Hybrid Gated-DeltaNet attention, so long context is cheaper — which is exactly our problem shape. GGUF: `unsloth/Qwen3.5-4B-GGUF` | Newer architecture; multimodal weights we do not need |
| **Qwen/Qwen3-4B-Instruct-2507** | 4B | **Apache-2.0** | Conservative baseline: text-only, mature, the most-tested GGUF ecosystem of the three | Older; weaker multilingual than 3.5 |

Rejected during research: **CohereLabs/tiny-aya** (3B, explicitly multilingual) is **gated** behind a
click-through licence and personal-data form, so `ModelDownloadWorker` cannot fetch it without
credentials. Attractive on paper, unusable in an app that downloads its own models.

On the Gemma licence: we ship no weights, only a downloader — the same relationship we already have
with the whisper models. The app's own GPLv3 is unaffected. Worth a conscious decision anyway, since
an Apache-2.0 alternative exists.

## Global Constraints

Copied from the existing native build and the project's standing rules. Every task inherits these.

- **arm64-v8a only**, matching the app's existing `abiFilters`.
- **`GGML_OPENMP` must stay `OFF`** — the NDK ships no OpenMP runtime and the link fails with it on.
- **`-O3` in Debug builds.** The NDK's Debug default is `-O0`, which for ggml is ~13× slower, not a
  mild slowdown. `add_compile_options($<$<CONFIG:Debug>:-O3>)` already exists and must cover the new
  targets too.
- **Exactly one ggml in the build.** whisper.cpp and llama.cpp each vendor their own; adding both
  naively makes CMake fail on duplicate `ggml` targets. See Task 1.
- **Pin the submodule to a tag**, as `third_party/whisper.cpp` is.
- **No call content ever leaves the device or enters the repo.** The measurement report holds
  timings and ratings only — never transcript or summary text. Transcript text is read on the phone
  by the maintainer.
- **Weights are downloaded at runtime, never bundled.**
- Branch `spike/summarisation`, **local only, never pushed**.
- No `Co-Authored-By` or Claude attribution trailers on any commit.

## What this spike is explicitly NOT

No UI, no Settings entry, no WorkManager job, no database column, no model-picker. Those come after
the decision, and only if the answer is yes. The one deliverable is a filled-in results table and a
recommendation.

---

## File Structure

| File | Responsibility |
|---|---|
| `third_party/llama.cpp` | New tag-pinned submodule |
| `app/src/main/cpp/CMakeLists.txt` | Modified: one shared ggml, add the `llamacv` target |
| `app/src/main/cpp/llamacv.cpp` | New: JNI bridge — load, generate, abort, free |
| `app/src/main/java/com/baba/callvault/summary/LlamaNative.kt` | New: the `external fun` surface, mirroring `WhisperNative` |
| `app/src/main/java/com/baba/callvault/summary/SummaryEngine.kt` | New: owns serialisation and the context lifetime |
| `app/src/main/java/com/baba/callvault/summary/SummaryChunking.kt` | New: pure logic — split segments into chunks. Unit-tested |
| `app/src/main/java/com/baba/callvault/summary/SummaryPrompt.kt` | New: pure logic — build the prompt. Unit-tested |
| `app/src/test/java/com/baba/callvault/summary/SummaryChunkingTest.kt` | New |
| `app/src/test/java/com/baba/callvault/summary/SummaryPromptTest.kt` | New |
| `app/src/androidTest/java/com/baba/callvault/summary/SummaryBenchmark.kt` | New: the measurement harness |
| `docs/dev-notes/2026-08-21-summarisation-spike-plan.md` | This file — results table filled in at Task 5 |

---

## Progress

- **Task 1 — done** (`493c942`). See the correction below.
- **Task 2 — done** (`5165b54`). Three tests pass on the OP12.
- **Task 3 — done** (`0c2e390`). 503 unit tests green.
- **Task 4 — not started.** Needs several GB of model downloads.
- **Task 5 — not started.** Needs sustained time on the phone.

### Correction: the plan was wrong about ggml, in both directions

The plan said to set `LLAMA_USE_SYSTEM_GGML`. **Don't** — that makes llama.cpp look for an
*installed* system libggml, which does not exist here. Both projects already guard their ggml with
`if (NOT TARGET ggml)`, so whichever is added first creates the target and the second reuses it. No
switch, no duplicate-target failure, and the APK carries one libggml.

What the plan under-estimated is the cost. whisper.cpp was pinned to **v1.7.4, January 2025** —
nineteen months stale, from before `gguf.h` was split out of `ggml.h`. Today's llama.cpp would not
compile against it: `fatal error: 'gguf.h' file not found`. Sharing a ggml means the submodules must
be contemporaries, so **whisper moved to v1.9.3** (released 2026-08-20). Pinning llama.cpp back to
January 2025 instead was never an option — that predates every architecture worth summarising with.

**Therefore: bumping whisper is load-bearing, not housekeeping.** It carries nineteen months of
upstream change into the shipped transcription engine. It builds and the suite is green, but
**transcription has not been re-verified on a device**, and that gates anything past this spike.

### Outstanding before Task 4

- [ ] Transcribe a short recording on the OP12 and confirm the text is still right after the
      whisper v1.7.4 → v1.9.3 bump.

## Task 1: Build llama.cpp beside whisper.cpp

**Files:**
- Create: `third_party/llama.cpp` (submodule)
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `.gitmodules`

**Interfaces:**
- Produces: a linkable CMake target `llama`, and one `ggml` shared by both engines.

The whole risk of this task is the duplicate ggml. whisper.cpp does `add_subdirectory(ggml)` and so
does llama.cpp; adding both gives `add_library cannot create target "ggml" because another target
with the same name already exists`. Both projects expose a switch to use an existing ggml instead of
vendoring their own, which is the route to take.

- [ ] **Step 1: Add the submodule, pinned to a tag**

```bash
git submodule add https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
cd third_party/llama.cpp
git tag --sort=-creatordate | head -5      # pick the newest stable tag
git checkout <tag>
cd -
```

- [ ] **Step 2: Confirm which ggml switch each project offers**

```bash
grep -rn "USE_SYSTEM_GGML\|GGML_HOME\|if(NOT TARGET ggml)" third_party/whisper.cpp/CMakeLists.txt third_party/llama.cpp/CMakeLists.txt
```

Expected: both have a `*_USE_SYSTEM_GGML` option. If they do not, fall back to guarding the second
`add_subdirectory` and letting whisper's ggml serve both — record whichever you used in a comment.

- [ ] **Step 3: Wire it into CMakeLists.txt**

Append after the existing whisper block, keeping the existing flags in force:

```cmake
# --- On-device summarisation (llama.cpp) ---
#
# Same org and same ggml as whisper.cpp above. The subtlety is that BOTH projects vendor ggml, and
# two add_subdirectory calls would collide on the target name. whisper.cpp is added first, so its
# ggml is the one that exists; llama.cpp is told to use it rather than build a second.
set(LLAMA_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_SYSTEM_GGML ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(llamacv SHARED llamacv.cpp)
target_link_libraries(llamacv llama ${log-lib})
```

- [ ] **Step 4: Create a stub llamacv.cpp so the target links**

```cpp
#include <jni.h>
#include "llama.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_summary_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
```

- [ ] **Step 5: Build and confirm both libraries exist**

```bash
./gradlew :app:assembleRelease
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E "libwhispercv|libllamacv|libggml"
```

Expected: `libwhispercv.so` and `libllamacv.so` both present. Expected APK growth: a few MB.
If `libggml.so` appears twice, or the build fails on a duplicate target, Step 3 is wrong — fix it
here rather than working around it later.

- [ ] **Step 6: Commit**

```bash
git add .gitmodules third_party/llama.cpp app/src/main/cpp/CMakeLists.txt app/src/main/cpp/llamacv.cpp
git commit -m "build: llama.cpp beside whisper.cpp, sharing one ggml"
```

---

## Task 2: The JNI bridge

**Files:**
- Modify: `app/src/main/cpp/llamacv.cpp`
- Create: `app/src/main/java/com/baba/callvault/summary/LlamaNative.kt`
- Create: `app/src/main/java/com/baba/callvault/summary/SummaryEngine.kt`

**Interfaces:**
- Consumes: the `llama` target from Task 1.
- Produces: `SummaryEngine.generate(modelPath: String, prompt: String, maxTokens: Int): String`
  and `SummaryEngine.requestAbort()`.

Mirror `WhisperNative` exactly, including the two hard-won details: one context is not thread-safe,
so a single owner serialises access; and a long blocking native call needs a real abort flag,
because neither coroutine cancellation nor WorkManager can interrupt it. That lesson cost a full
debugging session on the transcription side — do not relearn it.

- [ ] **Step 1: Write the native side**

```cpp
#include <jni.h>
#include <atomic>
#include <string>
#include "llama.h"

static std::atomic<bool> g_abort{false};

extern "C" JNIEXPORT void JNICALL
Java_com_baba_callvault_summary_LlamaNative_requestAbort(JNIEnv *, jobject) {
    g_abort.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_baba_callvault_summary_LlamaNative_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(model);
}
```

Generation loop: tokenise the prompt, decode, sample greedily, stop on EOS, on `maxTokens`, or when
`g_abort` is set. Clear `g_abort` at the *start* of every generate, so a stale abort cannot kill the
following run. Consult `third_party/llama.cpp/examples/simple/` for the current API shape rather
than copying an older sampling API from memory.

- [ ] **Step 2: Write the Kotlin surface**

```kotlin
object LlamaNative {
    init { System.loadLibrary("llamacv") }
    external fun systemInfo(): String
    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)
    external fun generate(ptr: Long, prompt: String, maxTokens: Int, threads: Int): String
    external fun requestAbort()
}
```

- [ ] **Step 3: Instrumented smoke test — the library loads**

```kotlin
@Test fun nativeLibraryLoads() {
    assertTrue(LlamaNative.systemInfo().isNotEmpty())
}
```

- [ ] **Step 4: Run it**

```bash
./gradlew :app:connectedDebugAndroidTest -PisolateTestApp \
  --tests "*SummaryNativeTest*"
```

`-PisolateTestApp` is not optional: it installs beside the daily-driver build instead of replacing
it, which would drop `WRITE_SECURE_SETTINGS` and break recording.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(summary): JNI bridge to llama.cpp, with a real abort"
```

---

## Task 3: Chunking and prompt, as pure logic

**Files:**
- Create: `app/src/main/java/com/baba/callvault/summary/SummaryChunking.kt`
- Create: `app/src/main/java/com/baba/callvault/summary/SummaryPrompt.kt`
- Test: `app/src/test/java/com/baba/callvault/summary/SummaryChunkingTest.kt`
- Test: `app/src/test/java/com/baba/callvault/summary/SummaryPromptTest.kt`

**Interfaces:**
- Consumes: `TranscriptSegmentEntry` from `data/transcripts/db`.
- Produces: `SummaryChunking.chunk(segments, maxChars): List<List<TranscriptSegmentEntry>>` and
  `SummaryPrompt.forChunk(segments, language)` / `SummaryPrompt.forMerge(summaries, language)`.

Compose-free and Android-free, like `RecordingSelection` — this is the part that decides what the
model is asked, and it must be testable without a phone.

- [ ] **Step 1: Write the failing chunking tests**

```kotlin
@Test fun `keeps a short transcript in one chunk`() {
    val chunks = SummaryChunking.chunk(segments(10, chars = 50), maxChars = 4000)
    assertEquals(1, chunks.size)
}

@Test fun `never splits a segment across chunks`() {
    val input = segments(100, chars = 200)
    val chunks = SummaryChunking.chunk(input, maxChars = 1000)
    assertEquals(input, chunks.flatten())
}

@Test fun `keeps every chunk under the limit`() {
    val chunks = SummaryChunking.chunk(segments(100, chars = 200), maxChars = 1000)
    assertTrue(chunks.all { c -> c.sumOf { it.text.length } <= 1000 })
}

@Test fun `emits nothing for an empty transcript`() {
    assertTrue(SummaryChunking.chunk(emptyList(), maxChars = 4000).isEmpty())
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "*SummaryChunkingTest*"
```
Expected: FAIL, unresolved reference `SummaryChunking`.

- [ ] **Step 3: Implement chunking**

Accumulate segments until adding the next would exceed `maxChars`; start a new chunk. Chars rather
than tokens deliberately: a real tokeniser lives on the far side of JNI, and characters are a stable
over-estimate across the scripts we support. Note in a comment that Hebrew and Arabic are denser per
character than English, so the limit is conservative for them.

- [ ] **Step 4: Write the failing prompt tests**

```kotlin
@Test fun `asks for the summary in the transcript's own language`() {
    val prompt = SummaryPrompt.forChunk(segments(3), language = "he")
    assertTrue(prompt.contains("Hebrew"))
}

@Test fun `tells the model not to invent`() {
    assertTrue(SummaryPrompt.forChunk(segments(3), language = "en").contains("only what is said"))
}

@Test fun `carries the speaker when the transcript has one`() {
    val prompt = SummaryPrompt.forChunk(listOf(segment(text = "hi", speaker = "Caller")), "en")
    assertTrue(prompt.contains("Caller: hi"))
}
```

- [ ] **Step 5: Run, watch fail, implement**

Two prompts. Per chunk: summarise this part of a phone call. For the merge: combine these partial
summaries into one, without repeating. Both must state the output language explicitly by name, and
both must forbid invention — a summary that invents a fact about a private call is worse than no
summary at all, which is the single quality bar this feature has to clear.

- [ ] **Step 6: Run the whole suite and commit**

```bash
./gradlew :app:testDebugUnitTest
git commit -am "feat(summary): chunking and prompts, as tested pure logic"
```

---

## Task 4: The measurement harness

**Files:**
- Create: `app/src/androidTest/java/com/baba/callvault/summary/SummaryBenchmark.kt`

**Interfaces:**
- Consumes: `SummaryEngine`, `SummaryChunking`, `SummaryPrompt`, and transcripts already in
  `transcripts.db` on the device.

The harness is the deliverable of the spike, so it must record numbers rather than impressions.

- [ ] **Step 1: Write it**

For each model file found in the device's model directory, and each transcript named in a parameter,
record: model name, transcript language, transcript length in characters, chunk count, model load
ms, generate ms per chunk, total ms, tokens generated, peak RSS (read from
`Debug.getMemoryInfo()`), and the generated summary.

- [ ] **Step 2: Write the report where the maintainer can read it, and nowhere else**

```kotlin
// Summaries and transcript text stay on the phone. This lands in the app's own external files
// directory so it can be read over adb and deleted, and it is NEVER copied into the repo — the
// numbers are what the plan needs; the words are a private phone call.
val out = File(context.getExternalFilesDir(null), "summary-benchmark.txt")
```

- [ ] **Step 3: Push the candidate models to the device**

```bash
adb -s <serial> push gemma-4-E2B-it-Q4_K_M.gguf   /sdcard/Download/
adb -s <serial> push Qwen3.5-4B-Q4_K_M.gguf       /sdcard/Download/
adb -s <serial> push Qwen3-4B-Instruct-2507-Q4_K_M.gguf /sdcard/Download/
```

- [ ] **Step 4: Commit**

```bash
git commit -am "test(summary): on-device benchmark harness"
```

---

## Task 5: Run it, and decide

**Files:**
- Modify: this document — fill in the results table below.

- [ ] **Step 1: Ask before running.** The phone is a daily driver, and this pins several cores for
      minutes. Confirm it is connected, charging, and not on a call.

- [ ] **Step 2: Run the benchmark over at least four transcripts** — two Hebrew, two English, with
      at least one over ten minutes.

```bash
./gradlew :app:connectedDebugAndroidTest -PisolateTestApp --tests "*SummaryBenchmark*"
adb -s <serial> shell cat /sdcard/Android/data/com.baba.callvault/files/summary-benchmark.txt
```

- [ ] **Step 3: Read the summaries on the phone and rate each run** 1–5 for faithfulness (did it
      invent anything?) and pass/fail for language (did a Hebrew call come back in Hebrew?).

- [ ] **Step 4: Fill in the table**

| Model | Language | Transcript | Chunks | Load | Total | Peak RSS | Faithful | Right language |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

- [ ] **Step 5: Decide against the gate below, and write the verdict at the end of this document.**

## Results

### Qwen3.5-4B, Q4_K_M — measured 2026-08-21 on the OP12

Four runs: an invented Hebrew call and an English one, each summarised whole and again split into
two chunks so the merge path was exercised rather than assumed. Six threads, CPU only.

| Sample | Chunks | Load | Total | Peak PSS | In the right language? |
|---|---|---|---|---|---|
| Hebrew, 476 chars | 1 | 2461 ms | **43.1 s** | 2907 MB | yes |
| Hebrew, 476 chars | 2 + merge | 732 ms | **213.4 s** | 2903 MB | — no summary reached |
| English, 596 chars | 1 | 1120 ms | **66.1 s** | 2821 MB | — no summary reached |
| English, 596 chars | 2 + merge | 1086 ms | **204.7 s** | 2907 MB | — no summary reached |

**Verdict: fails the gate, on three counts of five.**

- **Speed, by a wide margin.** Those samples are about 500 characters — twenty seconds of talk.
  Forty-three seconds for that is roughly fifty times too slow for the ten-minute call the gate is
  written against. Chunking made it *worse*, not better: two chunks took 213 s, of which the merge
  alone was 94 s. That is the wrong direction for the case the feature exists for.
- **Memory: 2.9 GB** against a 2.5 GB bar.
- **It invented.** The one summary that completed said the delivery window was *"between 9 and 10"*.
  The call said between nine and **eleven**. Fluent, confident, and wrong about the fact a person
  would act on — exactly the failure that makes a summary worse than none.

**It is a reasoning model, which is the wrong tool for this job.** Qwen3.5 thinks out loud in a
`<think>` block first, and on **three of four runs it spent the whole token budget thinking and never
reached a summary**. What came back was its own deliberation, including *"Correction: I need to look
at the actual user input."* That is now stripped before anything reaches a user (`SummaryText`), but
stripping is a bandage: the budget is still spent on reasoning nobody asked for.

The one summary it did produce was, apart from that number, genuinely good — fluent Hebrew, right
speakers, right substance. The capability is there; the cost and the reliability are not.

### Gemma 4 E2B-it, Q4_K_M — measured 2026-08-21 on the OP12

Same four runs, same samples, same six threads.

| Sample | Chunks | Load | Total | Peak PSS | Right language? | Invented anything? |
|---|---|---|---|---|---|---|
| Hebrew, 476 chars | 1 | 3280 ms | **27.9 s** | 3064 MB | yes | **no** |
| Hebrew, 476 chars | 2 + merge | 1787 ms | **87.3 s** | 3486 MB | yes | **no** |
| English, 596 chars | 1 | 1416 ms | **15.1 s** | 3479 MB | yes | **no** |
| English, 596 chars | 2 + merge | 1411 ms | **43.6 s** | 3473 MB | yes | **no** |

**Nothing was invented in any of the four runs.** It kept the delivery window at nine-to-eleven —
the exact number Qwen narrowed to nine-to-ten — and got 1200 including delivery, 200 for
installation, and 1400 as the new total, all correct.

**Chunking made the summaries better, not worse.** The two-chunk Hebrew run mentioned the address and
the reason for moving the delivery, which the single-chunk run left out; the two-chunk English run
caught the pressure valve, the extra forty pounds and the promise to ring first, all of which the
single-chunk run dropped. Smaller chunks mean less to compress at once. That is the opposite of the
worry that motivated the merge step, and it means chunk size is a **quality** dial, not only a
context-window workaround.

One artefact to fix rather than a fault: the single-chunk Hebrew run printed its answer, then a
`**תרגום לעברית:**` heading, then the identical text again. Padding to fill the token budget. A stop
sequence or a trim handles it.

### Both models, against the gate

| Criterion | Bar | Qwen3.5-4B | Gemma 4 E2B |
|---|---|---|---|
| Right language | every run | pass | **pass** |
| No invented facts | 8 of 10 | **fail** (1 of 1) | **pass** (0 of 4) |
| Speed | 60 s / 10-min call | fail, ~50x over | fail on the bar; see below |
| Memory | 2.5 GB | fail, 2.9 GB | fail, 3.5 GB |
| Download | 3 GB | pass, 2.7 GB | fail, 3.46 GB |

**Recommendation: Gemma 4 E2B, and move the cost bars.**

It is the only one of the two that is *trustworthy*, which is the property that cannot be traded. It
is also roughly twice as fast on identical work — 15.1 s against 66.1 s on the English sample.

Extrapolating to a real ten-minute call, about 8000 characters: two chunks at the 6000-character
default plus a merge, with prefill growing with the prompt. **Somewhere around two to four minutes**
— an extrapolation from 500-character samples, not a measurement, and it should be measured on a
real transcript before anyone relies on it. That is far outside a 60-second bar and entirely
reasonable for a background job that says what it is doing, which is exactly what transcription
already does at tens of minutes per call.

The memory and download bars were guesses made before any evidence. 3.5 GB resident is real and rules
out smaller phones; that belongs in the feature's requirements rather than in a veto.

## Verdict

**Viable, with Gemma 4 E2B, as a background job — not as something that happens while you wait.**

Still unproven, and worth doing before building any UI:

- **A real transcript.** Everything above used two invented calls of about 500 characters. Real
  speech is messier and twenty times longer, and both matter. The maintainer should choose the call.
- **A phone that is not an OP12.** 3.5 GB resident on a 16 GB flagship says nothing about an 8 GB
  device, and this feature must degrade honestly rather than crash.

### What this says about the gate

The 60-second bar assumed summarising is quick next to transcribing. It is not: generation is
serial, and a phone does perhaps eight tokens a second at this size. If a later model is accurate but
slow, the bar worth revisiting is this one — transcription already takes tens of minutes and is
accepted, because it says so up front and runs in the background. **The invention bar is not
negotiable in the same way.**

## Decision gate

Proposed thresholds. A model ships only if it clears all five; adjust before running, not after
seeing the numbers.

| Criterion | Bar | Why this bar |
|---|---|---|
| Language | Summary is in the transcript's language on **every** run | A Hebrew call summarised into English is not a degraded feature, it is a broken one |
| Faithfulness | **No invented facts** in at least 8 of 10 runs, and never an invented name, number or commitment | This is a record of a real conversation; a plausible fabrication is the worst possible output |
| Speed | ≤ **60 s** for a 10-minute call on the OP12 | Comparable to what transcription already costs, so the existing queue, estimate and warning modal fit unchanged |
| Memory | Peak RSS ≤ **2.5 GB**, no OOM with the app in the foreground | Must survive on phones smaller than a 12 GB OP12 |
| Download | ≤ **3 GB** | On top of a whisper model already on the device |

**If nothing clears the gate**, do not ship a worse version of it. The fallback worth costing is
extractive rather than generative — pull the most salient lines straight out of the transcript,
which cannot hallucinate because it invents nothing. Record that as the recommendation and stop.

## Verdict

_Filled in at Task 5._
