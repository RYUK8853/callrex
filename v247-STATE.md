# Callrex v2.4.7 — Pipeline fixes (2026-10-08)

User report (verbatim): recording fails on WhatsApp + sometimes normal call; "transcription on
after call ends" needs manual action; finished transcription not making summaries; speakers not
recognised (2–3 speakers, background voice); wants production-grade, free, crisp audio + best
summaries.

## Verified root causes (code, not guess)
1. **Auto-transcribe silent no-op**: `TRANSCRIPTION_REQUIRES_CHARGING` default was **true**
   (AppPreferences.kt:189 old). AFTER_EACH_CALL queued the work, but it waited for a charger.
   Fixed: default now **false**.
2. **Summaries never automatic**: `SummaryScheduler` had no auto path — only `runNow` from a tap
   (HomeScreen). Fork's SummaryWorker is runner-based (SummaryRunner), not the original text-check
   shape. Added `SummaryScheduler.auto()` + `KEY_AUTO` flag + no-op semantics (already-summarised
   / no-finished-transcript / empty-result → Result.success, not failure) + hook in
   `TranscriptionRunner.runBatch` (only for batches ≤ 3 = the per-call flow; skips when local
   model not downloaded or cloud not configured — logged, never red rows) + Settings toggle
   "Summarise after transcription" (default ON, key `summary_auto_after_transcription`, in
   EXPORTABLE_KEYS).
3. **Speakers (PENDING LOG)**: `OfflineSpeakerLabeller` needs true stereo (both channels
   different); `DirectAudioRecorderSession` encodes MONO by design → stereo info lost → "mixed"
   verdict → no labels. Real fix needs the phone's log to see what the route actually delivers
   (`CV:DirectAudioRecorder ch=` line). **User will send log after next call.** Do NOT ship the
   stereo-encode change blind.
4. **VoIP fail (PENDING LOG)**: same log needed. User: "i havent recorded the log for those call
   we have to wait for another call ill sned the logs after then".

## Build state
- v2.4.7 = versionName 2.4.7, versionCode 20453 (bump NOT yet applied — pending build success).
- Changed files: AppPreferences.kt, SummaryScheduler.kt, SummaryWorker.kt,
  TranscriptionRunner.kt, SettingsViewModel.kt, SettingsScreen.kt, strings.xml.
- compileReleaseKotlin running (proc_efa09463eba7). Then: testDebugUnitTest, assembleRelease,
  emulator smoke, GitHub push (branch + tag v2.4.7 + Callrex.apk release asset), catbox upload,
  in-chat delivery.
- Keystore: signing/callvault-signing.keystore alias androiddebugkey storepass android (debug default).
- Mandatory update: asset name MUST be `Callrex.apk` (GitHubReleasesTest pins it).

## User constraints (still binding)
- Free tooling; user's APIs never published/committed; no new hosted model upload.
- Dono tarof recording mandatory. Device = user's Android 16 phone (NOT adb-reachable;
  only emulator-5554).
- Delivery: in-chat MEDIA + catbox fallback.

## Next owed to user
- v2.4.7 build + delivery with what's fixed vs pending-log.
- Then: user's call log → VoIP + speaker fix (v2.4.8).
