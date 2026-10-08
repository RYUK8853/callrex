# v2.4.10 STATE — 2026-10-08 (v2410-STATE.md)

## Field bug (v2.4.9 log se, 21:01:10 export — Nothing A001)
Transport ab THEK (20:40 pe WD auto-on → loopback :50838 → daemon binder → journal-end success).
Dono calls RECORD hui:
- 20:45 WhatsApp VoIP — 813 KB, 50s, farPartyHeard=true
- 20:59 normal call (Arsh) — 363 KB, 22s, DIRECT AudioRecord
PHIR dono gayab:
1. `Storage target: DRIVE` + **Drive folder = wahi Recordings folder** (dono `primary:Recordings`).
2. Publish ke **545ms baad** `RecordingCopyWorker: already in Drive; not uploading it again` —
   real cloud upload mein possible nahi → destination mein apni hi file mili (self-reference).
3. DRIVE mode `KEY_DELETE_LOCAL=true` → `finish()` ne **wahi original delete** kar di → kahin koi nahi.
4. Race: transcription local file se padhti hai (no Drive fallback) → `FileNotFoundException`
   → "transcription cant be done" → manual tap → "no longer in the catalog; skipping".
5. 21:00:40 `HomeViewModel: Recording gone (deleted outside the app); pruning stale entry` → 0 recordings.
NOTE: 20:45 + 20:59 ki dono recordings device se gayab hain (v2.4.9 ne delete ki) — recover karna
impossible; v2.4.10 future ko rokta hai.

## Changes (v2.4.10)
1. `SafHelper.kt` — NEW pure-URI predicates:
   - `isDocumentInTree(srcUri, treeUri)` — doc id, tree id prefix (percent-encoding-safe).
   - `isSameFolder(a, b)` — tree-vs-tree (picker guard).
2. `RecordingCopyWorker.kt` —
   - doWork start: source destFolder ke andar hai → refuse + error notification
     (`recording_error_drive_folder_is_recordings`) + `markDrive(src, deleteLocalAfter=false)`
     (row local + "drive" dono rakhti hai, file safe) + success.
   - `finish()`: delete se pehle re-check `isDocumentInTree` (settings mid-flight badal sakti hain)
     + `transcriptionSettled(name)`: sirf `TranscriptState.DONE` = delete OK; QUEUED/RUNNING/FAILED/
     no-row = file device pe rahegi; `Result.retry()` jab tak `DELETE_WAIT_ATTEMPTS`
     (= MAX_ATTEMPTS=10) na ho; phir terminal success with BOTH copies on the row.
3. `SyncSweepWorker.kt` —
   - same-folder (tree-vs-tree) → sweep skip (library self-delete se bacha).
   - per-file delete pe `transcriptSettled(name)` wahi semantics (DONE-only).
4. `SettingsScreen.kt` + `WizardScreen.kt` — Drive folder picker same-recordings-folder REFUSE
   karta hai (toast `folder_same_as_recording_rejected`).
5. `AppLogger.writeConfiguration` — export ab "Recordings folder: X" + "Drive folder: Y"
   naam dikhata hai (agla log first-read pe misconfiguration batayega).
6. strings.xml: 2 naye strings; build.gradle.kts 20455/2.4.9 → 20456/2.4.10; CHANGELOG [2.4.10].
7. NEW test: `SafHelperSameFolderTest.kt` — 8 cases (field-bug shape YES, cloud NO,
   percent-encoding, nulls, sibling trees).

## Build/test status
- [ ] assembleRelease + testDebugUnitTest
- [ ] APK copy /tmp/Callrex.apk + sha256
- [ ] Git commit + tag v2.4.10 + push (permission rule: user explicit permission)
- [ ] GitHub release v2.4.10 + Catbox
- [ ] User ko Hinglish mein deliver

## Device fix (user action — v2.4.10 install ke saath)
- Settings → Storage → Drive backup folder dobara select karo (waise toh v2.4.10 picker same-folder
  refuse karega; purani config rahe toh runtime guard + notification handle karega).
- Ya seedha storage target "Both" / "Local" pe le jao — tab koi delete nahi hota.
