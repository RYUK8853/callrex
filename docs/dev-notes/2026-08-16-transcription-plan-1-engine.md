# Transcription Plan 1 — Engine Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make CallVault able to turn a saved call recording into timestamped, correctly-languaged text on the device, and know how fast it actually runs on real hardware.

**Architecture:** whisper.cpp is added as a git submodule and compiled into the app's **existing** native CMake build (the one that already builds `audiohandoff.cpp`). A forked JNI layer exposes language selection and per-segment timestamps, which upstream's Android sample does not. A Kotlin facade decodes an Opus recording to 16 kHz mono float and returns typed segments.

**Tech Stack:** Kotlin, NDK r27 (`27.2.12479018`, already pinned), CMake ≥ 3.22.1, whisper.cpp (MIT), ggml models, Room 2.8.4, WorkManager 2.10.0, JUnit + Robolectric 4.14.

**Spec:** `docs/dev-notes/2026-08-16-on-device-transcription-design.md`

## Global Constraints

- **Zip the project folder before the first line of code** — `~/Desktop/CallVault-backup-<version>-<date>.zip`, including `.git` and the gitignored signing keystore. Standing rule.
- **Nothing is pushed to GitHub.** Branches and commits stay local until explicitly asked.
- `minSdk = 30`, `targetSdk = 36`, `compileSdk = 36`, `namespace = "com.baba.callvault"`.
- `ndkVersion = "27.2.12479018"` — do not change it.
- **`ndk { abiFilters += "arm64-v8a" }` is already set and must stay.** Do not add `armeabi-v7a`.
- JVM target 17 (`JavaVersion.VERSION_17`, `JvmTarget.JVM_17`).
- Gradle 9.4.1 / AGP 9.2.1. `dependencyResolutionManagement` uses `FAIL_ON_PROJECT_REPOS` — declare repositories in `settings.gradle.kts`, never in a module.
- **Transcription runs in the app process. Never in the recorder daemon** (`com.baba.callvault.server.*`). Do not touch the daemon in this plan.
- Every file carries the project's GPLv3 header comment — copy it verbatim from any existing `.kt` file.
- Release builds are local and signed with `signing/callvault-signing.keystore`; do not alter signing config.
- Java for Gradle: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` (the shell has no default JDK).

---

## EXECUTION STATUS — resume here (2026-08-16)

All five tasks are **code-complete on branch `feat/transcription-engine`** (9 commits, not merged,
never pushed). `main` is untouched. **330 unit tests pass; `assembleDebug` succeeds.**

| task | state |
|---|---|
| 1 — measure on-device RTF | ✅ done, numbers in *Results* below |
| 2 — vendor whisper.cpp | ✅ done, libs verified in the APK |
| 3 — JNI language + segments | ✅ done, symbols verified with `llvm-nm` |
| 4 — audio decode | ✅ done + bug found and fixed on device (see below) |
| 5 — engine facade | ✅ done and **verified on device 2026-08-17** |

## ✅ VERIFIED ON DEVICE — 2026-08-17

All three instrumented tests pass on the OP12: **3 tests, 0 failures, 0 skipped.** Plan 1 is done.

- the native library loads and resolves its JNI symbols
- the decoder fix (`956312c`) is confirmed: a 47.5 s clip decodes to 47.5 s of audio
- **the transcript comes back in Hebrew script**, which is what proves `params.language` actually
  reaches whisper — the whole reason this JNI is not upstream's

Verify the counts in the XML report, not the Gradle log. `assumeTrue` skips are reported as
`BUILD SUCCESSFUL`, so a run that staged no fixtures looks identical to a passing one:

```bash
grep -o 'tests="[0-9]*"\|skipped="[0-9]*"\|failures="[0-9]*"' \
  "app/build/outputs/androidTest-results/connected/debug/TEST-*.xml"
```

### Finding: a debug build runs inference ~13x too slow (fixed)

The first verified run took **10m26s** for a 47.5 s clip. Nothing was wrong with the code — AGP
compiles debug variants with `CMAKE_BUILD_TYPE=Debug`, and the NDK's Debug flags are `-O0`. For ggml
that is not a mild penalty.

Fixed by `add_compile_options($<$<CONFIG:Debug>:-O3>)` in `app/src/main/cpp/CMakeLists.txt`, placed
before `add_subdirectory` so whisper, ggml and `whispercv` inherit it while `audiohandoff` keeps its
debuggable `-O0`. Re-measured immediately after:

| native build | wall clock, 47.5 s clip | speedup |
|---|---|---|
| `-O0` (AGP default for debug) | 10m 26s | — |
| `-O3` | **49 s** (RTF ≈ 0.85) | **12.8x** |

The `-O3` number agrees with the 0.99 RTF measured in Task 1 with a release-built `whisper-cli`, so
release performance was never affected — only on-device debug runs were, and they were the runs
being used to judge speed. **Do not remove that line**, or every future device test costs 13x.

### Gotcha: `connectedAndroidTest` uninstalls the app, wiping the fixtures

Gradle uninstalls the package when the run finishes, and uninstalling deletes
`/sdcard/Android/data/<pkg>/`. Fixtures therefore do **not** survive a run, and pushing them while
the package is absent silently lands in an orphaned directory. Stage them *after* install, *every*
time:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SERIAL=6011b07e
D=/sdcard/Android/data/com.baba.callvault.instrtest/files

./gradlew installDebug -PisolateTestApp        # 1. install (side by side; never replaces v1.5.7)
adb push cv-test-audio.ogg $D/cv-test-audio.ogg # 2. stage AFTER install
adb push ggml-small-q5_1.bin $D/cv-test-model.bin
./gradlew connectedDebugAndroidTest -PisolateTestApp   # 3. run (uninstalls itself at the end)
```

### The Hebrew fixture is synthetic, on purpose

Real call recordings live in app-private storage and are not readable over `adb`, and the maintainer's
calls must not be copied around to make a test pass. The fixture is generated locally instead:

```bash
say -v Carmit -f hebrew.txt -o hebrew.aiff        # macOS Hebrew TTS
ffmpeg -i hebrew.aiff -af "lowpass=f=3800,highpass=f=250" \
       -c:a libopus -b:a 16k -ac 1 -ar 48000 cv-test-audio.ogg
```

The band-pass mimics the narrowband telephony audio the spec measured, and the encode matches
CallVault's own Opus 16 kbps mono. It must land in the 40–50 s window the decode test asserts; `say`
ignores `-r` for Carmit, so adjust the *text length*, not the rate. This validates the language
plumbing, not recognition accuracy on real speech — accuracy was already assessed in Task 1.

### Historical: the original 15x report

The first ever device run transcribed a 45 s clip for eleven minutes. That was read as one bug; it
was two, stacked. The decoder read the *extractor's input* format and ignored
`INFO_OUTPUT_FORMAT_CHANGED` (fixed in `956312c`, with `isPlausibleDuration()` to fail loudly next
time), **and** the `-O0` build above. Fixing only the first would have left an 11-minute run looking
barely improved, which is why the decode check is split out to run in a second without a model.

Keep the decode check split out for that reason: it needs no model and finishes in about a second, so
a decode fault never again costs eleven minutes of borrowed device time to discover.

**Next:** Plan 2 — model download and verification, the WorkManager pipeline, and Room v1→v2 with
transcripts, FTS and cascade delete.

---

## Task 1: Measure real on-device speed before building anything — ✅ DONE 2026-08-16

> **Executed.** See *Results* at the bottom for the numbers, the method deviation (cross-compiled
> `whisper-cli` over `adb shell` instead of upstream's tap-driven sample app), and the two design
> claims it disproved. The steps below are kept as the record of what was intended; follow *Results*
> for what actually happened. Nothing was left on the phone or in scratch.

This is **risk #1 in the spec** and it is deliberately first. Desktop RTF was 0.54 on an Apple M5; a phone may be 3–6× slower. If a 10-minute call takes 30 minutes, the scheduler in Plan 2 must be opportunistic (charging + idle) rather than "runs when the call ends". Measuring first stops that assumption being baked in.

**This task writes no CallVault code.** It builds *upstream's own sample app* and throws it away.

**Files:**
- Create: nothing in this repo
- Scratch only: `~/whisper-rtf-spike/` (delete when done)

**Interfaces:**
- Consumes: nothing
- Produces: two numbers recorded in this file's *Results* section — RTF for `ggml-small-q5_1` and `ggml-large-v3-turbo-q5_0` on the OP12 — plus a go/no-go on whether the Best tier is usable at all.

- [ ] **Step 1: Confirm the phone is connected**

The OP12 is a daily driver and is not always tethered. **Ask the user before assuming.** Then:

```bash
adb devices -l
```
Expected: one device listed (serial `6011b07e`). If not, stop and ask.

- [ ] **Step 2: Clone whisper.cpp and build the sample app**

```bash
mkdir -p ~/whisper-rtf-spike && cd ~/whisper-rtf-spike
git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git
cd whisper.cpp/examples/whisper.android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL. If the NDK is missing, install `27.2.12479018` via Android Studio's SDK Manager.

- [ ] **Step 3: Download both model tiers**

```bash
cd ~/whisper-rtf-spike
curl -L -o ggml-small-q5_1.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
curl -L -o ggml-large-v3-turbo-q5_0.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin
ls -lh *.bin
```
Expected: 190 MB and 574 MB respectively.

- [ ] **Step 4: Put a real recording and the models on the phone**

Use a genuine call of known length (a 5–10 minute Hebrew call is the realistic case). **These are private recordings — keep them on the phone and the local machine only.**

```bash
adb push ggml-small-q5_1.bin /sdcard/Download/
adb push ggml-large-v3-turbo-q5_0.bin /sdcard/Download/
adb push <real-recording>.ogg /sdcard/Download/
```

- [ ] **Step 5: Install the sample app and run each tier**

```bash
adb install -r examples/whisper.android/app/build/outputs/apk/debug/app-debug.apk
```

Then on the phone, load each model, transcribe the recording, and record wall-clock time. Watch logcat for whisper's own timings:

```bash
adb logcat -s LibWhisper:* whisper:* | tee ~/whisper-rtf-spike/rtf.log
```

- [ ] **Step 6: Record the results in this document**

Fill in the *Results* table at the bottom of this file with: model, audio duration, wall-clock, computed RTF (`wall_clock / audio_duration`), and peak memory (`adb shell dumpsys meminfo <pkg>`). Commit that edit.

```bash
git add docs/dev-notes/2026-08-16-transcription-plan-1-engine.md
git commit -m "docs(transcription): record measured on-device RTF for both model tiers"
```

- [ ] **Step 7: Decide and state the consequence**

Write one sentence in *Results* answering: does the Best tier finish a typical call in acceptable time, or does Plan 2's scheduler need charging-and-idle constraints? **Note:** upstream's sample decodes English only (see Task 3), so treat its *text* as meaningless here — this task measures **speed and memory**, not quality. Quality was already established by the desktop spike.

- [ ] **Step 8: Delete the scratch directory**

```bash
rm -rf ~/whisper-rtf-spike
adb shell rm /sdcard/Download/ggml-small-q5_1.bin /sdcard/Download/ggml-large-v3-turbo-q5_0.bin
adb uninstall com.whispercppdemo
```

---

## Task 2: Vendor whisper.cpp into the existing native build

**Files:**
- Create: `.gitmodules` (or append if it exists)
- Create: `third_party/whisper.cpp` (submodule)
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/build.gradle.kts` (nothing structural — verify only)

**Interfaces:**
- Consumes: Task 1's go/no-go
- Produces: a `whispercv` shared library linked into the APK, loadable via `System.loadLibrary("whispercv")`.

- [ ] **Step 1: Take the safety snapshot (standing rule — do this before any code)**

```bash
cd ~/Desktop/Projects
zip -r ~/Desktop/CallVault-backup-1.5.7-2026-08-16.zip callrecorder -x '*/build/*' '*/.gradle/*'
ls -lh ~/Desktop/CallVault-backup-1.5.7-2026-08-16.zip
```
The `.git` directory and the gitignored keystore are included on purpose; only build output is excluded.

- [ ] **Step 2: Create the feature branch**

```bash
cd ~/Desktop/Projects/callrecorder
git checkout -b feat/transcription-engine
```

- [ ] **Step 3: Add whisper.cpp as a pinned submodule**

```bash
git submodule add https://github.com/ggml-org/whisper.cpp.git third_party/whisper.cpp
cd third_party/whisper.cpp && git checkout v1.7.4 && cd -
git add .gitmodules third_party/whisper.cpp
```
Pin to a tag, never a moving branch — F-Droid reproducibility depends on it.

- [ ] **Step 4: Extend the existing CMakeLists**

Replace `app/src/main/cpp/CMakeLists.txt` with:

```cmake
# Native half of "Resilient recording" (audio-capture handoff): extracts the IAudioRecord binder +
# cblk ashmem fd in the daemon, and drains the cblk ring in the app.
cmake_minimum_required(VERSION 3.22.1)
project(audiohandoff)

add_library(audiohandoff SHARED audiohandoff.cpp)

find_library(log-lib log)

target_link_libraries(audiohandoff ${log-lib})

# --- On-device transcription (whisper.cpp) ---
# Built from the pinned third_party/whisper.cpp submodule. arm64-v8a only, per the app's abiFilters.
set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(WHISPER_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whispercv SHARED whispercv.cpp)
target_link_libraries(whispercv whisper ${log-lib})
```

`GGML_OPENMP` is forced off because the Android NDK ships no OpenMP runtime; leaving it on fails the link.

- [ ] **Step 5: Add a minimal JNI file that only proves loading works**

Create `app/src/main/cpp/whispercv.cpp`:

```cpp
#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}
```

- [ ] **Step 6: Add the Kotlin loader**

Create `app/src/main/java/com/baba/callvault/transcription/WhisperNative.kt` (copy the GPLv3 header from an existing file):

```kotlin
package com.baba.callvault.transcription

/** JNI bridge to whisper.cpp. Loaded once; all calls are made off the main thread. */
object WhisperNative {
    init { System.loadLibrary("whispercv") }

    /** ggml build/CPU feature string. Used to confirm the native library loaded at all. */
    external fun systemInfo(): String
}
```

- [ ] **Step 7: Build and verify the library links**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "libwhispercv|libaudiohandoff"
```
Expected: both `lib/arm64-v8a/libwhispercv.so` and `lib/arm64-v8a/libaudiohandoff.so` present, and **no other ABI directory**.

- [ ] **Step 8: Commit**

```bash
git add .gitmodules third_party/whisper.cpp app/src/main/cpp/CMakeLists.txt \
        app/src/main/cpp/whispercv.cpp \
        app/src/main/java/com/baba/callvault/transcription/WhisperNative.kt
git commit -m "feat(transcription): build whisper.cpp into the existing native CMake target"
```

This commit is independently revertable and changes no app behaviour — it only adds a library.

---

## Task 3: JNI transcription with language selection and real segments

Upstream's Android sample **hardcodes `params.language = "en"`** (`examples/whisper.android/lib/src/main/jni/whisper/jni.c:179`) and returns one flat pre-formatted string. Both are wrong for us: Hebrew decoded as English produces garbage, and Plan 2's storage needs per-segment start/end times.

**Files:**
- Modify: `app/src/main/cpp/whispercv.cpp`
- Modify: `app/src/main/java/com/baba/callvault/transcription/WhisperNative.kt`
- Create: `app/src/main/java/com/baba/callvault/transcription/TranscriptSegment.kt`

**Interfaces:**
- Consumes: `WhisperNative` from Task 2.
- Produces:
  - `data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)`
  - `WhisperNative.initContext(modelPath: String): Long`
  - `WhisperNative.freeContext(ptr: Long)`
  - `WhisperNative.transcribe(ptr: Long, audio: FloatArray, threads: Int, language: String?): Unit`
  - `WhisperNative.segmentCount(ptr: Long): Int`
  - `WhisperNative.segmentStartMs/segmentEndMs/segmentText(ptr: Long, index: Int)`

  `language` is an ISO code such as `"he"`, or `null` for auto-detect.

- [ ] **Step 1: Add the segment type**

Create `app/src/main/java/com/baba/callvault/transcription/TranscriptSegment.kt`:

```kotlin
package com.baba.callvault.transcription

/** One contiguous piece of recognised speech, with timings relative to the start of the recording. */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
```

- [ ] **Step 2: Replace the JNI with the real implementation**

Replace `app/src/main/cpp/whispercv.cpp`:

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

static inline whisper_context *ctx_of(jlong p) { return reinterpret_cast<whisper_context *>(p); }

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    whisper_context *c = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(ctx_of(ptr));
}

JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray audio, jint threads, jstring language) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;   // upstream sets true; it floods logcat
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;   // transcribe, never translate to English
    params.n_threads        = threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;

    const char *lang = nullptr;
    if (language != nullptr) lang = env->GetStringUTFChars(language, nullptr);
    params.language      = lang;        // nullptr => auto-detect
    params.detect_language = (lang == nullptr);

    const jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    whisper_full(ctx_of(ptr), params, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
}

JNIEXPORT jint JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentCount(JNIEnv *, jobject, jlong ptr) {
    return whisper_full_n_segments(ctx_of(ptr));
}

JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentStartMs(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t0(ctx_of(ptr), i) * 10; // whisper t0/t1 are centiseconds
}

JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentEndMs(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t1(ctx_of(ptr), i) * 10;
}

JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentText(JNIEnv *env, jobject, jlong ptr, jint i) {
    return env->NewStringUTF(whisper_full_get_segment_text(ctx_of(ptr), i));
}

}
```

The `* 10` is not arbitrary: whisper reports segment times in **centiseconds**, and every consumer in this codebase works in milliseconds.

- [ ] **Step 3: Extend the Kotlin bridge**

Replace the body of `WhisperNative.kt`:

```kotlin
package com.baba.callvault.transcription

/**
 * JNI bridge to whisper.cpp.
 *
 * whisper.cpp forbids touching one context from more than one thread at a time; [TranscriptionEngine]
 * owns that serialisation. Nothing else may call these functions directly.
 */
object WhisperNative {
    init { System.loadLibrary("whispercv") }

    external fun systemInfo(): String
    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)

    /** @param language ISO code (e.g. "he"), or null to auto-detect. */
    external fun transcribe(ptr: Long, audio: FloatArray, threads: Int, language: String?)

    external fun segmentCount(ptr: Long): Int
    external fun segmentStartMs(ptr: Long, index: Int): Long
    external fun segmentEndMs(ptr: Long, index: Int): Long
    external fun segmentText(ptr: Long, index: Int): String
}
```

- [ ] **Step 4: Build**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/whispercv.cpp \
        app/src/main/java/com/baba/callvault/transcription/WhisperNative.kt \
        app/src/main/java/com/baba/callvault/transcription/TranscriptSegment.kt
git commit -m "feat(transcription): JNI with language selection and per-segment timings"
```

---

## Task 4: Decode a recording to the audio whisper expects

whisper.cpp requires **16 kHz mono PCM float in [-1, 1]**. CallVault stores 48 kHz mono Opus in an Ogg container. This conversion is pure, deterministic, and the only part of the engine that is unit-testable without a phone — so it gets real tests.

**Files:**
- Create: `app/src/main/java/com/baba/callvault/transcription/AudioDecoder.kt`
- Create: `app/src/test/java/com/baba/callvault/transcription/AudioDecoderTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `AudioDecoder.pcm16ToMonoFloat(pcm: ShortArray, channels: Int): FloatArray`
  - `AudioDecoder.resampleTo16k(input: FloatArray, inputRate: Int): FloatArray`
  - `AudioDecoder.decodeToMono16k(context: Context, uri: Uri): FloatArray` — **blocking, not `suspend`**; `TranscriptionEngine` is what moves it off the main thread.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/baba/callvault/transcription/AudioDecoderTest.kt` (with the GPLv3 header):

```kotlin
package com.baba.callvault.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDecoderTest {

    @Test
    fun `mono pcm16 is scaled into the minus one to one range`() {
        val pcm = shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE)
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 1)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f), out, 0.001f)
    }

    @Test
    fun `stereo pcm16 is averaged down to mono`() {
        // L=+full R=-full must average to silence, not to a doubled sample.
        val pcm = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 1000, 1000)
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(1000f / 32767f, out[1], 0.001f)
    }

    @Test
    fun `an odd trailing frame in stereo input is dropped rather than read out of bounds`() {
        val pcm = shortArrayOf(100, 200, 300) // 1.5 stereo frames
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 2)
        assertEquals(1, out.size)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest --tests '*AudioDecoderTest*'
```
Expected: FAIL — `Unresolved reference 'AudioDecoder'`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/baba/callvault/transcription/AudioDecoder.kt` (GPLv3 header first):

```kotlin
package com.baba.callvault.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns a stored recording into the audio whisper.cpp requires: 16 kHz mono float in [-1, 1].
 *
 * CallVault writes 48 kHz mono Opus, but this handles stereo too — recordings made before the
 * mandatory mono downmix (v1.4.4) are stereo, and the back-catalogue still contains them.
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000

    /** Interleaved PCM-16 to mono float. Channels beyond the first are averaged in, not dropped. */
    fun pcm16ToMonoFloat(pcm: ShortArray, channels: Int): FloatArray {
        require(channels >= 1) { "channels must be >= 1" }
        val frames = pcm.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += pcm[f * channels + c] / 32767f
            out[f] = (acc / channels).coerceIn(-1f, 1f)
        }
        return out
    }

    /** Linear resample to [TARGET_SAMPLE_RATE]. Adequate here: the source is band-limited telephony. */
    fun resampleTo16k(input: FloatArray, inputRate: Int): FloatArray {
        if (inputRate == TARGET_SAMPLE_RATE || input.isEmpty()) return input
        val ratio = inputRate.toDouble() / TARGET_SAMPLE_RATE
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = i * ratio
            val a = src.toInt()
            val b = (a + 1).coerceAtMost(input.size - 1)
            val frac = (src - a).toFloat()
            out[i] = input[a] * (1 - frac) + input[b] * frac
        }
        return out
    }

    /**
     * Decodes [uri] fully to 16 kHz mono float. Call off the main thread; a long call decodes to
     * tens of megabytes of float, which is why Plan 2 segments before transcribing.
     */
    fun decodeToMono16k(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            requireNotNull(pfd) { "Cannot open $uri" }
            extractor.setDataSource(pfd.fileDescriptor)
        }
        val track = (0 until extractor.trackCount).first { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcm = ArrayList<Short>()
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false

        while (!sawOutputEnd) {
            if (!sawInputEnd) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val buf = codec.getOutputBuffer(outIndex)!!
                val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (shorts.hasRemaining()) pcm.add(shorts.get())
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val mono = pcm16ToMonoFloat(pcm.toShortArray(), channels)
        return resampleTo16k(mono, sampleRate)
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
./gradlew testDebugUnitTest --tests '*AudioDecoderTest*'
```
Expected: PASS (3 tests).

- [ ] **Step 5: Run the whole suite so nothing regressed**

```bash
./gradlew testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, 308 pre-existing tests plus the 3 new ones.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/baba/callvault/transcription/AudioDecoder.kt \
        app/src/test/java/com/baba/callvault/transcription/AudioDecoderTest.kt
git commit -m "feat(transcription): decode recordings to 16 kHz mono float for whisper"
```

---

## Task 5: The engine facade

Ties Tasks 3 and 4 into the single entry point Plan 2 will call, and enforces whisper.cpp's one-thread-per-context rule in exactly one place.

**Files:**
- Create: `app/src/main/java/com/baba/callvault/transcription/TranscriptionEngine.kt`
- Create: `app/src/test/java/com/baba/callvault/transcription/TranscriptionEngineTest.kt`

**Interfaces:**
- Consumes: `WhisperNative`, `TranscriptSegment`, `AudioDecoder`.
- Produces: `suspend fun TranscriptionEngine.transcribe(context, uri, modelPath, language): List<TranscriptSegment>` and `TranscriptionEngine.preferredThreadCount(): Int` — both used by Plan 2's worker.

- [ ] **Step 1: Write the failing test for thread selection**

Thread count is the one piece of engine logic testable without a device. Create `app/src/test/java/com/baba/callvault/transcription/TranscriptionEngineTest.kt`:

```kotlin
package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionEngineTest {

    @Test
    fun `uses every available core`() {
        // Measured on the OP12 (SM8650, 6 performance + 2 efficiency cores), large-v3-turbo-q5_0:
        // 4 threads 140.4 s, 6 threads 99.2 s, 8 threads 95.9 s. All-cores won, so the
        // "prefer performance cores" heuristic was rejected on evidence rather than kept on intuition.
        assertEquals(8, TranscriptionEngine.threadCountFor(availableProcessors = 8))
    }

    @Test
    fun `never returns fewer than one thread`() {
        assertEquals(1, TranscriptionEngine.threadCountFor(availableProcessors = 1))
    }

    @Test
    fun `a nonsensical processor count still yields a usable thread count`() {
        // Runtime.availableProcessors() is documented to be able to change and has been seen to
        // return 0 on some devices; whisper.cpp would divide by it.
        assertEquals(1, TranscriptionEngine.threadCountFor(availableProcessors = 0))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew testDebugUnitTest --tests '*TranscriptionEngineTest*'
```
Expected: FAIL — `Unresolved reference 'TranscriptionEngine'`.

- [ ] **Step 3: Implement the engine**

Create `app/src/main/java/com/baba/callvault/transcription/TranscriptionEngine.kt` (GPLv3 header first):

```kotlin
package com.baba.callvault.transcription

import android.content.Context
import android.net.Uri
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The single entry point for turning a recording into text.
 *
 * whisper.cpp forbids concurrent access to one context, and a second concurrent job would only
 * thrash the cores the first is already saturating — so every transcription is serialised onto one
 * dedicated thread here rather than being left to callers.
 */
object TranscriptionEngine {

    private const val TAG = "CV:Transcribe"

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cv-transcribe").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Threads to hand whisper.cpp: every core.
     *
     * Measured on the OP12 (SM8650, 6 performance + 2 efficiency cores) with
     * `large-v3-turbo-q5_0` — 4 threads 140.4 s, 6 threads 99.2 s, 8 threads 95.9 s. Using the
     * efficiency cluster too was fastest, so the "prefer performance cores" rule this was originally
     * designed with is deliberately NOT implemented. Do not reintroduce it without new measurements.
     */
    fun threadCountFor(availableProcessors: Int): Int = availableProcessors.coerceAtLeast(1)

    fun preferredThreadCount(): Int =
        threadCountFor(Runtime.getRuntime().availableProcessors())

    /**
     * Transcribes [uri] with the model at [modelPath].
     *
     * @param language ISO code such as "he", or null to auto-detect.
     * @return segments in order; empty when the model recognised no speech — callers must surface
     *   that as "no speech detected" rather than as success with empty text.
     */
    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelPath: String,
        language: String?,
    ): List<TranscriptSegment> = withContext(dispatcher) {
        val audio = AudioDecoder.decodeToMono16k(context, uri)
        if (audio.isEmpty()) {
            AppLogger.w(TAG, "Decoded no audio from $uri")
            return@withContext emptyList()
        }
        val ptr = WhisperNative.initContext(modelPath)
        if (ptr == 0L) error("Could not load whisper model at $modelPath")
        try {
            WhisperNative.transcribe(ptr, audio, preferredThreadCount(), language)
            val count = WhisperNative.segmentCount(ptr)
            (0 until count).map { i ->
                TranscriptSegment(
                    startMs = WhisperNative.segmentStartMs(ptr, i),
                    endMs = WhisperNative.segmentEndMs(ptr, i),
                    text = WhisperNative.segmentText(ptr, i).trim(),
                )
            }.filter { it.text.isNotEmpty() }
        } finally {
            WhisperNative.freeContext(ptr)
        }
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
./gradlew testDebugUnitTest --tests '*TranscriptionEngineTest*'
```
Expected: PASS (4 tests).

- [ ] **Step 5: Full suite**

```bash
./gradlew testDebugUnitTest && ./gradlew assembleDebug
```
Expected: BUILD SUCCESSFUL both times.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/baba/callvault/transcription/TranscriptionEngine.kt \
        app/src/test/java/com/baba/callvault/transcription/TranscriptionEngineTest.kt
git commit -m "feat(transcription): serialised engine facade returning typed segments"
```

- [ ] **Step 7: Prove it end to end on the phone**

**Ask first — the OP12 is a daily driver.** Then install, push a model and a real Hebrew recording, and transcribe it through the app's own engine (a temporary debug entry point is acceptable and must be removed before Plan 2 ends).

Expected: Hebrew text, not English transliteration. **This is the check that Task 3's language parameter actually took effect** — if the output is English-looking gibberish, `params.language` did not reach whisper.

---

## Results — Task 1 (MEASURED 2026-08-16 on the OP12)

Device: OnePlus 12 (`6011b07e`, CPH2581), **SM8650** / Snapdragon 8 Gen 3, 8 cores
(1× Cortex-X4 + 5× A720 performance, 2× A520 efficiency).

**Method deviation, deliberate.** The plan said to build upstream's `examples/whisper.android` sample.
That app needs manual taps to load a model and start a run, which cannot be driven headlessly, so
`whisper-cli` was cross-compiled for `arm64-v8a` with NDK 27.2.12479018 and run over `adb shell`
instead. Same engine, same models, scriptable, and it reports whisper's own timings. Input was a real
120.0 s Hebrew call excerpt decoded to 16 kHz mono WAV.

| model | audio | wall clock | **RTF** | peak RSS |
|---|---|---|---|---|
| `ggml-small-q5_1` | 120.0 s | 118.9 s | **0.99** | not reliably measured † |
| `ggml-large-v3-turbo-q5_0` | 120.0 s | 259.1 s | **2.16** | **1.03 GB** (1,057,268 kB) |

† Two attempts returned an implausible ~1.7 MB — the PID capture read the shell rather than
`whisper-cli`. Not fabricated here. Scaling turbo's measured 1.8× model-size overhead suggests roughly
350 MB, but treat that as an estimate, not a measurement.

**Desktop → phone slowdown was 5×** for `small` (0.20 → 0.99) and **4×** for turbo (0.54 → 2.16),
inside the 3–6× the spec predicted.

### Thread count: the "performance cores" heuristic was measured and REJECTED

`large-v3-turbo-q5_0`, 30 s slice:

| threads | total |
|---|---|
| 4 | 140.4 s |
| 6 | 99.2 s |
| **8** | **95.9 s** |

Using **all 8 cores**, including the efficiency cluster, was fastest. The spec claimed big.LITTLE
scheduling onto little cores "measurably hurts" — on SM8650 it does not. The 8-thread run also went
**last**, so thermal throttling worked against it and the result still held. Task 5 and the spec are
corrected accordingly.

### Consequence for Plan 2's scheduler

Transcription is **viable but slow enough that it must never block a user**:

| call length | Light (RTF 0.99) | Best (RTF 2.16) |
|---|---|---|
| 5 min | ~5 min | ~11 min |
| 10 min | ~10 min | ~22 min |
| 30 min | ~30 min | ~65 min |

So:
1. **Keep the deferred background-job design.** "Runs when the call ends" with someone waiting is not
   an option at the Best tier; the foreground-service-with-progress-and-cancel design stands.
2. **Resumability is now mandatory, not a nicety.** A 65-minute job that restarts from zero on
   interruption would effectively never finish.
3. **Charging-only should be the recommended default for the Best tier** — an hour at 8 cores is a real
   battery and thermal cost — but it must not be *forced*, since Light at ~1× real time is fine
   unplugged.
4. **The ~1 GB memory gate is justified** and must be enforced before loading the Best tier.

---

## What this plan deliberately does not do

Plan 2 (model download + WorkManager pipeline + Room v2 storage) and Plan 3 (transcript UI, FTS search, Settings) are **written after this plan lands**, because Task 1's measured RTF decides whether the scheduler needs charging-and-idle constraints. Writing them now would bake in the assumption this plan exists to test.
