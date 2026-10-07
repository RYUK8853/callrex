# On-device call transcription — design

**Status 2026-08-16: designed, approved, NOT started.** No code written. Supersedes nothing; this is a
new subsystem. Engine choice is backed by measurements on real CallVault recordings — see *Evidence*,
which is the part of this document worth re-reading before changing any decision.

---

## What it is

Transcribe finished call recordings to searchable text, entirely on the device. No network at
transcription time, no third-party service, no audio leaving the phone.

**Decisions locked during brainstorming (2026-08-16):**

| Decision | Choice | Why |
|---|---|---|
| When | **After the call**, batch, on the saved file | Cannot endanger the recorder; works on the whole back-catalogue |
| Languages | **Multilingual**, user-selectable | The maintainer's own calls are Hebrew; the app ships 10 locales |
| Engine home | **Embedded in the app** | Self-contained; no dependency on a second app being installed |
| Models | **Downloaded on demand**, user picks a tier | Keeps the APK and F-Droid build lean |
| Monetisation | **None.** No entitlement seam | Explicitly chosen after weighing GPLv3 + F-Droid, where a code paywall is removable anyway |
| Speakers | **Plain text now**, schema ready for later | Diarization is deferred, not designed out |

---

## Evidence — measured, not assumed

A throwaway spike on three real CallVault recordings (faster-whisper/CTranslate2, int8, Apple M5).
**These numbers are why the engine choice is what it is.**

### The audio is narrowband telephony

| file | 99% energy rolloff | energy above 4 kHz |
|---|---|---|
| real call A (4 min) | 2,074 Hz | **0.00 %** |
| real call B (8 min) | 2,836 Hz | **0.00 %** |

Despite a 48 kHz container, there is nothing above 4 kHz. Published model benchmarks (FLEURS,
LibriSpeech) are clean wideband speech and **systematically overstate** what we will get. A third
sample was the opposite — 85 % of energy in 8–16 kHz, non-speech — so the corpus is not homogeneous.

### The calls are Hebrew, and that eliminates the fastest models

Whisper detected `he` at 0.82 → 1.00 confidence as model size grew.

- **NVIDIA Parakeet TDT 0.6B v3** — best-in-class and faster than Whisper, but its 25 languages are
  European. **No Hebrew.** Unusable here.
- **SenseVoice** — 5–15× faster than Whisper, but only zh/yue/en/ja/ko. **No Hebrew.**
- **Whisper** — ~99 languages including Hebrew. By elimination, the only viable family.

### Quality cliff is between `small` and `medium`

| model | RTF (M5, int8) | Hebrew result |
|---|---|---|
| tiny | 0.05 | unusable — invented words |
| base | 0.07 | unusable — repetition loops (`זה יודעת` ×3) |
| small | 0.20 | gist only — `פסיסים`, `הטקציב` wrong |
| medium | 0.66 | clean |
| **large-v3-turbo** | **0.54** | clean, and **faster than medium** |

`medium` is dominated on every axis and is not offered.

### Recording bitrate is irrelevant — do not "fix" it

Re-encoding the same audio to 32 kbps and 16 kbps mono and re-running `medium` produced
**essentially identical transcripts**. Because the source is band-limited, 16 kbps already carries
everything present. **Do not advise users to raise their bit rate for better transcripts** — that was
a plausible hypothesis, tested, and false. The wizard's "Opus at 16 kbps" recommendation stands.

Current builds write **mono 48 kHz** (verified from an Aug-2026 recording's `OpusHead`); the June
files used in the spike were stereo, so the measurements above are a fair upper bound.

---

## Engine: whisper.cpp, not sherpa-onnx

Hebrew forces Whisper. sherpa-onnx was the ergonomic favourite (prebuilt AAR, no NDK) until two facts
landed: its Whisper path carries an **unfixed preprocessing bug** — [issue #2900](https://github.com/k2-fsa/sherpa-onnx/issues/2900),
log-mel computed before padding to 30 s instead of after, so frame counts differ from reference
Whisper — and its best models don't speak Hebrew anyway.

So: **whisper.cpp**, following [Transcribro](https://github.com/soupslurpr/Transcribro) (ISC, ships on
F-Droid) which carries `whisper.cpp` as a submodule with a thin JNI `lib` module. Licence flow is
clean: MIT/ISC/Apache-2.0 all move one-way into our GPLv3.

### Model tiers

| tier | model | download | notes |
|---|---|---|---|
| Light | `ggml-small-q5_1` | **190 MB** | gist-only Hebrew; visible word errors |
| Best, smaller download | `ggml-large-v3-turbo-q5_0` | **574 MB** | clean Hebrew; still the stored default |
| **Best** | `ggml-large-v3-turbo-q8_0` | **874 MB** | clean Hebrew, and **~2x faster than q5_0** |

> The q8_0 row said "marginal gain over q5_0" until 2026-08-26, when it was measured rather than
> assumed and turned out to be backwards. q8_0 is roughly **twice as fast** on the same audio, not
> marginally better, because ggml's ARM backend gives Q8_0 a repacked GEMM kernel and gives Q5_0 and
> Q5_1 nothing — see the KDoc on `TranscriptionModel.LARGE_V3_TURBO_Q8_0` for the measurements and
> the ggml source that explains them. It costs ~240 MB more RAM while running.
> Ranking a tier by its file size is what produced the wrong note; nothing here was benchmarked.

`tiny` and `base` are **not offered** — the spike proved they emit garbage for Hebrew, and shipping
them would only generate bad transcripts and support load.

---

## Architecture

### 1. Pipeline and job lifecycle

- Finalising and cataloguing a recording enqueues a `TranscriptionWorker` — WorkManager **unique work
  keyed by recording id**, so repeat triggers coalesce. Same pattern as `SyncScheduler` and the
  retention sweep.
- **Never runs during a call.** Reuses the existing call-in-progress logic (`CallInProgressGate`)
  rather than adding a second call detector. Transcription saturates the CPU; running it mid-call is
  exactly how we would damage the thing the app exists to do.
- **Serial queue.** whisper.cpp takes all cores it is given; two concurrent jobs thrash for no gain.
- Constraints: battery-not-low by default, with a user toggle for charging-only. Long-running worker
  with a foreground progress notification and a cancel action (expedited work is not available for
  jobs measured in tens of minutes).
- **Resumable**: VAD-segment first, transcribe segment-by-segment, persist each as it completes. A
  25-minute job dying at minute 20 must not restart. Same segment structure later carries speakers.
- The same worker, enqueued from the UI, transcribes the back-catalogue.

### 2. Model management

- **Hosting**: mirror the ggml models on a dedicated CallVault release tag (`models-v1`) rather than
  hot-linking HuggingFace — stable URLs, same host as the updater, no third-party rate limiting.
- **Integrity**: a hardcoded `{model id → SHA-256, size, URL}` table compiled into the app, verified
  with the existing `ApkVerifier.sha256Hex()`. Note this is *not* the APK path's cert pinning — we do
  not sign OpenAI's weights. A reproducible FOSS build makes that table publicly auditable, which is
  the honest substitute for a signature.
- **Download**: reuse `GitHubReleases.downloadApk`'s shape (resumable, backoff on a flapping
  connection, refuses cleartext). **Wi-Fi-only by default**, user-overridable.
- **Storage**: `noBackupFilesDir`. **Never the SAF recordings folder** — that folder is Drive-synced,
  so a model there would upload 574 MB and then collide with the retention sweep.
- **Space**: require ~2× model size free before starting; fail clearly instead of dying mid-write.
- **Eviction**: one active model. On a tier switch the new model must download *and verify* before the
  old one is deleted. Explicit "delete model" action to reclaim space.
- **Failure**: hash mismatch deletes the partial and retries with backoff; an unverified model is never
  loaded. Verify once at download and record a marker — re-hashing 574 MB per job is pointless. If
  whisper.cpp fails to init, re-verify then.

### 3. Native layer and build

- **Runs in the app process, never the daemon.** Transcription needs no privilege; going near the
  uid-2000 shell daemon would risk the most fragile component for zero benefit.
- `whisper.cpp` as a **git submodule pinned to a tag** at `third_party/whisper.cpp`, pulled into the
  **existing** `app/src/main/cpp/CMakeLists.txt` with `add_subdirectory`. **No separate Gradle module
  is needed** — an earlier draft proposed a `:whisper` module before it was clear the app already
  compiles native code there. `GGML_OPENMP` must be forced OFF; the NDK ships no OpenMP runtime.
- We do not fork whisper.cpp itself, but we **do write our own JNI** rather than reusing upstream's
  Android sample, because that sample **hardcodes `params.language = "en"`**
  (`examples/whisper.android/lib/src/main/jni/whisper/jni.c:179`) and returns one flat pre-formatted
  string. Both are disqualifying: Hebrew decoded as English is garbage, and storage needs per-segment
  start/end times. Our JNI takes a language code (or null to auto-detect) and exposes segments.
- **ABI: `arm64-v8a` only** for release — **already configured** in `app/build.gradle.kts`
  (`ndk { abiFilters += "arm64-v8a" }`), so this needs no change. `minSdk` 30 means effectively every
  target is 64-bit.
- **Threads — measured 2026-08-16, and the original guidance was wrong.** This document first said to
  prefer the performance-core count because big.LITTLE scheduling onto little cores "measurably
  hurts". On the OP12 (SM8650) with `large-v3-turbo-q5_0` the opposite held: 4 threads 140.4 s,
  6 threads 99.2 s, **8 threads 95.9 s**. Use **every available core**. Do not reintroduce the
  performance-core heuristic without new measurements.
- **Memory**: `large-v3-turbo-q5_0` needs roughly 1 GB resident. Check `ActivityManager.MemoryInfo`
  before loading and fail with a clear message rather than taking an OOM kill; that is the cue to
  suggest the Light tier.

**What this costs — corrected 2026-08-16 after reading the build.** An earlier draft of this document
claimed "the NDK and CMake become build requirements". **That was wrong: they already are.** The app
already runs `externalNativeBuild { cmake }` over `app/src/main/cpp/CMakeLists.txt` to build
`audiohandoff.cpp` for resilient recording, `ndkVersion = "27.2.12479018"` is already pinned, and
`ndk { abiFilters += "arm64-v8a" }` is already the policy this design independently arrived at.

So whisper.cpp adds an `add_subdirectory` to an existing native build rather than introducing a
toolchain. The real remaining costs are smaller: **F-Droid must fetch a git submodule**, and first-time
native compilation is slower (incremental Kotlin work is unaffected). This makes whisper.cpp
meaningfully cheaper than the sherpa-onnx comparison assumed.

### 4. Transcript storage

> **⚠️ CORRECTED 2026-08-17 — do not build what this section originally said.** It specified the same
> `RecordingDatabase` bumped v1 → v2 with an `ON DELETE CASCADE` FK. Reading the code afterwards showed
> that database is built with `fallbackToDestructiveMigration(dropAllTables = true)` on purpose: the
> catalog is a *rebuildable cache*, re-seeded from the SAF folders. Transcripts are **not** rebuildable
> — each costs minutes of device CPU — so any future schema bump would silently destroy them all.
>
> **Transcripts now live in their own `transcripts.db`** with real migrations and no destructive
> fallback, keyed by the same natural key (`displayName`), which is what makes them survive the catalog
> being dropped and re-seeded. The cross-database FK is impossible, so **the cascade below becomes an
> explicit call in the deletion path** — still non-negotiable, just enforced by code and a test instead
> of by SQLite. See `2026-08-17-transcription-plan-2-pipeline.md`, Task 1.

Same Room database (`RecordingDatabase`), bumped **v1 → v2**.

- `TranscriptEntry` — per recording: `displayName` (FK), `status`
  (PENDING/RUNNING/PARTIAL/DONE/FAILED), `modelId`, `language`, `progressMs`, `updatedAt`.
- `TranscriptSegment` — per segment: `startMs`, `endMs`, `text`, and a **nullable `speaker`** column
  reserved now so diarization needs no migration later.

Keyed on `displayName`, matching the existing convention (`RecordingCatalog.removeName`).

- **`ON DELETE CASCADE` is non-negotiable.** When retention deletes a recording its transcript must die
  with it. Otherwise the app promises "deleted after 7 days" while retaining a searchable text of the
  conversation — more exposing than the audio, and a genuine privacy defect. Retention fault #2 was
  precisely "anything missing from the catalog was exempt", so this gets an explicit test.
- **Search**: a Room FTS table over segment text — the real payoff, making years of calls searchable.
  `unicode61` handles Hebrew for whole-word matching; there is no stemming for Hebrew morphology, so
  inflected forms will not match. Known, not solved in v1.
- **No Drive sync of transcripts in v1.** A sidecar `.txt` would drag transcripts into
  `RetentionPolicy.isEligible` and the Drive catalog — the machinery whose four faults cost a day on
  2026-08-04. DB-only plus share/export on demand. Sidecars can come later.
- **Migration**: `exportSchema = false`, so there is no schema JSON to diff. The v1→v2 migration must be
  hand-written and tested against a real v1 database.
- **Not encrypted**, because recordings are not either; encrypting one alone would be theatre. Worth
  revisiting only as part of a whole-app at-rest decision.

### 5. UI, errors, onboarding

- **Recordings list**: a transcript affordance per row. There is no detail screen today, so tapping
  opens a transcript sheet — timestamped segments, copy, share, re-transcribe.
- **Search**: slots into `HomeScreen`'s existing contact/date/direction/source filter model, querying
  FTS and showing matching snippets.
- **Settings ▸ Transcription**: auto-transcribe on/off, tier picker with sizes and download/delete,
  Wi-Fi-only, charging-only, language (auto-detect or pinned).
- **Onboarding: deliberately excluded.** The wizard cannot be re-run, so every new setting needs an
  explicit decision — and a 574 MB download during first-run setup, for an optional feature, would be
  hostile to someone who just wants recording to work. A one-time explainer appears the first time the
  user taps "Transcribe" instead.
- **Errors name cause and fix**: no model → offer download; low space → state how much; hash mismatch →
  delete, retry once, surface; OOM → FAILED plus a Light-tier suggestion; call starts → defer, resume.
  When output is empty or language confidence is low, say **"no speech detected"** rather than showing
  garbage — the `voipFarPartyHeard` honesty pattern, reporting what was observed.

---

## Test plan

- **Unit**: model-table integrity (every entry has hash and size), space precondition, tier selection,
  call-in-progress gating, resume-from-partial, v1→v2 migration.
- **The cascade-delete test matters most** — retention deleting a recording must delete its transcript.
  That is a privacy guarantee, not a nicety.
- **Device (OP12)**: RTF for both tiers on a real 10-minute Hebrew call; no interference with an
  incoming call mid-job; retention verified end to end.
- Golden-file test uses a **public-domain clip**, never real recordings — those cannot enter a public repo.

**This feature is the strongest argument yet for adding `androidTest`, which does not exist at all
today.** Room migrations and cascade deletes corrupt data silently and cannot be checked by hand.

---

## Open risks

1. ~~**On-device RTF is unmeasured.**~~ **CLOSED 2026-08-16 — measured on the OP12** (SM8650):
   `small-q5_1` **RTF 0.99**, `large-v3-turbo-q5_0` **RTF 2.16**, peak RSS **1.03 GB** for the Best
   tier. The 3–6× slowdown prediction held (5× and 4×). Consequences, now settled rather than assumed:
   a 10-minute call takes ~10 min (Light) or ~22 min (Best), and a 30-minute call ~30 / ~65 min. The
   deferred background-job design stands, **resumability becomes mandatory** rather than a nicety, and
   charging-only should be the *recommended default* for the Best tier without being forced — Light
   runs fine unplugged. Full numbers in `2026-08-16-transcription-plan-1-engine.md`.
2. **F-Droid anti-feature label** for downloading binaries at runtime, even though the Whisper weights
   are MIT. Confirm against their policy — it affects listing, not function.
3. **Hebrew quality at the Light tier is poor.** Users who decline the 574 MB download get visibly
   wrong words. The tier picker must say so honestly rather than implying parity.
4. ~~**NDK in the release path**~~ — **retired 2026-08-16.** `assembleRelease` already runs a native
   CMake build (`audiohandoff.cpp`) with a pinned NDK, so this is not a new failure mode. What remains
   is narrower: the pinned whisper.cpp **submodule tag** must be fetched by F-Droid and must not drift.

## Rollback

Zip the project folder (including `.git` and the gitignored keystore) to
`~/Desktop/CallVault-backup-<version>-<date>.zip` **before the first line of code** — a standing rule.
Then: feature branch off `main`, one concern per commit, extract-then-switch so a single revert undoes
each step. The `:whisper` module and submodule land in their own commit, ahead of any wiring, so the
native build can be reverted independently of the Kotlin.
