# Callrex — STATE (live checkpoint)
_Last updated: 2026-10-08 IST_

## What this is
Fork of **CallVault** (madkongo/CallVault, GPL-3.0) → **Callrex**: record calls (phone +
WhatsApp/VoIP) both sides → transcription (whisper.cpp) → AI summary (llama.cpp + Gemma).
- Fork path: `~/dev/callvault-fork` (local branch `fork/ai-call-summary`)
- applicationId = `com.vishal.callrex` (internal namespace `com.baba.callvault` kept)
- app_name = "Callrex", Nothing-style dark UI. Repo pushed as clean snapshot → RYUK8853/callrex:main

## ACTIVE TASK: hybrid transcription/summary + mandatory update
User complaint: on-device transcription "not good at all" → summary bad. Wants:
(1) better transcription using the "Hermes method", (2) hosted Qwen for summary,
(3) user's own API optional + NOT publicized, (4) mandatory in-app update (no data loss).

## VERIFIED FACTS (real runs — trust these over memory)
- **Hermes STT = `faster-whisper large-v3` int8, beam 5, on the Mac** (skill local-audio-transcription).
  Model cache: Systran/faster-whisper-large-v3. THIS is the "perfect, no-error" method the user references.
- **onetapretain server = TEXT ONLY.** Real runs: `Qwen/Qwen3.8-27B` + audio → 502;
  `/v1/audio/transcriptions` with user key → 403 (key limited to gemma-4-31b-it, Qwen3.8-27B).
  Both are text LLMs. => user's server CANNOT transcribe audio; CAN do text→summary.
- **whisper.cpp model digests** (HF `x-linked-etag`, verified by matching known turbo-q8_0):
    ggml-large-v3.bin       3095033483  64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2
    ggml-large-v3-q5_0.bin  1081140203  d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1
  (large-v3 q8_0 / q4_0 / q3_0 do NOT exist on HF → 404. q5_0 = 1.08GB is the smallest FULL large-v3.)

## WHY the phone transcription is "not good"
Phone default = `large-v3-turbo-q8_0` (809M params, distilled) — fast but lower accuracy.
Hermes uses full `large-v3` (1550M). Adding full large-v3 as the phone's top tier closes most of
the gap. Residual gap vs Hermes = int8 beam-5 on M4 CPU vs phone quant; the rest needs cloud ASR.

## PLAN (build after each phase)
- [ ] **P1 transcription quality**: add full `large-v3` (q5_0, 1.08GB) as new top on-device tier,
      make it DEFAULT (transcription never released → no migration). Touch: TranscriptionModel.kt
      (enum + DEFAULT L164), TranscriptionLabels.titleOf (when L122), strings.xml (L593-603).
- [ ] **P2 cloud transcription (optional)**: Settings → user's own OpenAI-compatible base URL + key
      + model; POST audio to /v1/audio/transcriptions, parse to TranscriptSegments. Nothing hardcoded.
      Injection at TranscriptionRunner.kt (model→transcribe).
- [ ] **P3 cloud summary (optional)**: user's LLM URL+key as alternate SummaryModelHost (text→summary,
      their Qwen). Their API stays on-device only. Injection at summary/SummaryRunner.kt host.
- [ ] **P4 mandatory update**: release keystore + pin Callrex cert + re-point to RYUK8853/callrex
      + asset name Callrex.apk + remove "Later" option (blocking dialog).
- [ ] **P5**: build debug, install on emulator, verify UI (model dropdown has new tier, settings
      cloud section, update dialog). Build release-signed. GitHub Release v1.0. Push commits.

## KEY FILES
- transcription/model/TranscriptionModel.kt   enum + DEFAULT
- ui/common/TranscriptionLabels.kt            titleOf when (exhaustive → forces new tier entry)
- res/values/strings.xml                      transcription_model_*
- transcription/TranscriptionRunner.kt        P2 cloud injection
- summary/SummaryRunner.kt (+ SummaryModelHost / SummarySession)   P3 cloud injection
- system/updates/UpdateManager.kt             repo/asset/cert pin (P4)
- app/build.gradle.kts                        applicationId, signing (P4)
- ui/screens/SettingsScreen.kt                add cloud settings section (P2/P3)

## Open / risks
- Release keystore NOT generated yet (only debug-signed). P4 creates it — MUST back it up.
- Phone brand unknown (Pixel/Samsung/Xiaomi/OnePlus) → OEM notes pending.
- E2E real-phone transcription+summary not yet verified.
- 27GB free on Mac (tight). Shallow clone → use orphan/snapshot for pushes.
- Cloud endpoint entered by user only; app ships on-device defaults. No user secret committed.
