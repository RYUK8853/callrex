# v2.4.8 — screen-off crash fix + log export fixes — STATE (2026-10-08)

## User reports (verbatim)
1. "i just had a call it it starts recording in the start then it crashes as the
   screen turn off and then i re record it mannually from the notification bar"
   → recording breaks exactly when the screen goes off.
2. "i cant send the logs cause we dont have any feature of that also its not
   copying in the clipboard"
   → no usable way to export/copy logs from the app.

## Root causes found (verified in code)
### A. Screen-off crash — NO CPU WAKE LOCK anywhere
- No `WAKE_LOCK` permission in `AndroidManifest.xml`.
- No `WakeLock` / `PARTIAL_WAKE_LOCK` in any recording code
  (`RecordingForegroundService.kt`, `DaemonKeepAliveService.kt`, `DirectAudioRecorderSession.kt`).
- Foreground service keeps the PROCESS alive, NOT the CPU awake. Screen off →
  CPU deep sleep → daemon capture thread suspended → ring overrun → mid-call break.
- Matches symptom exactly (breaks when screen turns off, restarts fine from
  notification while screen is on again).

### B. "No feature to send logs" — Share/Save buttons hidden while logging ON
- `SettingsScreen.kt` BugReportSection: `if (isLoggingEnabled) { only warning }
  else { Share/Save buttons }`.
- User flow: switch logging ON → bug happens → looks for share → buttons gone →
  "we don't have any feature of that".
- Clipboard copy: no copy button ever existed in DebugLogViewer → "not copying
  in the clipboard".

## Fixes applied (all in ~/dev/callvault-fork, branch callrex)
1. `app/src/main/AndroidManifest.xml` — added `WAKE_LOCK` permission.
2. `RecordingForegroundService.kt`:
   - `wakeLock` field + `holdWakeLock(reason)` / `releaseWakeLock()` (time-boxed
     1h lease `WAKE_LOCK_LEASE_MS` so a stuck release can't brown-out the phone).
   - Acquire in `startNewRecordingSession` right after `Active(...)` set +
     "pipeline started successfully" log.
   - Release at the very top of `stopRecordingSessionAndService()` — covers both
     the active path and the no-active-session early return, and
     `onDestroy()` calls that function, so a killed service can't leave it held.
   - Import `android.os.PowerManager`.
3. `SettingsScreen.kt` BugReportSection — Share/Save now ALWAYS visible (ON:
   warning above buttons; OFF: hint above buttons).
4. `SettingsScreen.kt` DebugLogViewer — Copy button (ContentCopy icon) in the
   header; copies the visible log tail via existing
   `Context.copyToClipboard(label, text)` (SystemIntentHelpers.kt:245).
   Strings hoisted at composable level (stringResource not allowed inside the
   plain onClick lambda).
5. `strings.xml` — added `settings_debug_log_copy`.
6. `app/build.gradle.kts` — version 2.4.7/20453 → **2.4.8/20454**.
7. `CHANGELOG.md` — [2.4.8] entry.

## Verification status
- `:app:compileReleaseKotlin` → BUILD SUCCESSFUL (after fixing the
  composable-in-lambda compile error).
- `:app:testDebugUnitTest` → RUNNING (background session proc_8792eeaa57f3).
- Release APK, Catbox upload, git tag v2.4.8, push, GitHub release asset
  Callrex.apk → PENDING (after tests).

## Open / deferred
- VoIP (WhatsApp) capture failure — still needs device log (now exportable:
  Settings → Debug → Share/Save/Copy).
- Speaker labelling (mono encode at DirectAudioRecorderSession.kt "always
  ENCODE MONO") — still deferred pending device log showing actual route.
- GitHub release asset for v2.4.7 already uploaded (Callrex.apk,
  release id 406856991) — in-app mandatory-update check now has an asset to
  find. v2.4.8 asset must replace it.

## Do NOT
- No new hosted model. No published API keys/hosts. Cloud stays user-configured.
- User commits + merges PRs only on explicit permission. Pushes to remote
  `callrex` (NOT `origin` — origin is upstream).
