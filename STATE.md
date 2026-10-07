# CallSage — STATE (live checkpoint)
_Last updated: 2026-10-05 IST_

## What this is
Fork of **CallVault** (madkongo/CallVault, GPL-3.0) → **CallSage**: record calls (phone + WhatsApp) both sides → on-device transcription (whisper.cpp) → on-device AI summary (llama.cpp + Gemma).
- Fork path: `~/dev/callvault-fork` (branch `fork/ai-call-summary`)
- Originals kept for reference: `/tmp/repo-study/callvault`, `/tmp/repo-study/shizu`

## Key architecture facts (verified in code, not guessed)
- Capture: privileged process (shell UID 2000) via **built-in ADB** (wireless-debugging pairing, default) OR **Shizuku** (optional mode). Scrcpy-server fallback for audio source `mic-voice-communication`.
- Phone calls: InCallService detection (Android 12+; verified working on AOSP android-16 r4) + PhoneState fallback.
- VoIP (WhatsApp/Signal/Telegram): shell-side reflection of hidden `AudioMixingRule` → loopback-render sink for far party (source usage `USAGE_VOICE_COMMUNICATION`) + plain MIC for near party → stereo interleave (L=near, R=far) → mono downmix → Opus/AAC mux. (server/VoipCaptureSession.kt, server/VoipAudioPolicy.kt)
- R8 shrinking MUST stay off (privileged recorder launched by string class name via app_process).
- Speaker labels: from the call's 2 channels, NOT a voice model (server/speakers/).
- Transcription: whisper.cpp, models 190MB / 574MB, chunked passes for long calls.
- Summary: llama.cpp + Gemma (2.6GB), structured JSON {intent, keyPoints, decisions, actionItems} with [m:ss] timestamps (summary/SummaryPrompt.kt).
- Trap list (from their issues, all handled in code): VOICE_COMMUNICATION mic source zero-filled during calls; arrival-based stream pairing caused 6.4s far-party lag (fixed slot pairing); OPPO/OnePlus/Realme need "Disable system optimization" switch; vivo AudioRecord public ctor crash (reflective fallback); OEM background kills need battery-exemption guidance.

## Rebrand done (Phase 0)
- `applicationId` = `com.vishal.callsage` (app/build.gradle.kts:144) — INTERNAL namespace `com.baba.callvault` kept for build stability; full package rename is Phase 2.
- `app_name` string = "CallSage" (res/values/strings.xml:13). User-facing "CallVault" mentions in other strings remain — bulk string rename pending (Phase 1, cosmetic).
- GPL-3.0 + §7: fork attribution to kitsumed (original) + madkongo/CallVault authors MUST be kept (NOTICE.md, README credits).

## Phases
- [x] P0: Fork + rebrand (applicationId, app_name)
- [x] P1a: Build env — SDK 36 + NDK 27.2.12479018 + CMake 3.22.1 installed (~/Library/Android/sdk)
- [x] P1b: First `./gradlew assembleDebug` GREEN — 4m36s. APK verified via aapt:
      package com.vishal.callsage v2.4.5 (code 20451), label "CallSage", minSdk 30 / targetSdk 36,
      84MB, all native libs present: libwhisper.so, libllama.so, libggml-*, libparakeet.so (VAD),
      libaudiohandoff.so, libllamacv.so, libwhispercv.so
- [ ] P1: Full "CallVault"→"CallSage" string sweep + icon (cosmetic)
- [ ] P2: Full package rename com.baba.callvault → com.vishal.callsage
- [ ] P3: Customize summary prompts for Vishal's business use (decisions/action-items focus, Hinglish)
- [ ] P4: Release keystore + signed APK; E2E on Android 16 phone (phone call + WhatsApp call → transcript + summary)
- NOTE: debug APK IS installable (debug-signed) — usable for E2E test now, no release keystore needed first.

## Open / risks
- Signing keystore does not exist yet (release builds unsigned without it).
- 27GB free disk on Mac — NDK+SDK+build outputs are big; watch it.
- VoWiFi/VoLTE calls not covered (upstream limitation).
- Play Store not an option (call-recording ban) → side-load / F-Droid / Obtainium only.
