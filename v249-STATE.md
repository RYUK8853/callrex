# v2.4.9 STATE — 2026-10-08 (v249-STATE.md)

## Root cause (v2.4.8 log se, Nothing A001)
- Recording kabhi start NAHI hui; "screen-off crash" tha hi nahi.
- 18:43:54 call → app "recording" notif → ADB transport dhundha: WD off + USB off + adbd STOPPED.
- App khud WD on karna chahta tha lekin `WirelessDebuggingEnableGate.decide` ne `RESPECT_USER` bola:
  `userTurnedOff=true` (false-positive flag — boot/default-off user-off dikha) + `enforced=false`
  (setting default OFF thi) → write refuse → ~84s retry (18:43:55→18:45:19) → `PipelineInitializationException`
  → notif removed → user "crash" samjha. **0 recordings.**
- WRITE_SECURE_SETTINGS=true tha, plan KEEP_ONLY_TRANSPORT tha — sab ready tha, sirf default OFF tha.

## Changes (v2.4.9)
1. `data/AppPreferences.kt:1479` — `isWirelessDebuggingEnforced()` default `false` → `true`.
   Gate ke saath: enforced=true → RESPECT_USER skip → WD write allowed (Wi-Fi up hone par).
   Setting Settings mein hai; user off kare toh purana behaviour wapas (respected).
2. `integrations/adb/TransportReadiness.kt` (NEW) — pure `check(...)` + `forContext()`.
   Verdicts: REACHABLE / DEAD_END_NO_GRANT / DEAD_END_NO_WIFI / DEAD_END_WD_OFF.
   Sirf PROVEN dead end short-circuit; koi bhi UNKNOWN → REACHABLE (path cut nahi hoti).
3. `integrations/adb/AdbShell.kt:789` — `wirelessDebuggingWriteAllowed(context)` = gate ka
   non-writing probe (userRequested=false, borrowingForLoopback=mayBorrow).
4. `server/RecorderServerLauncher.kt:~156` — `ensureServerRunningLocked` start par:
   verdict != REACHABLE → log + `return false` (millisecond-level fail; ~84s attempts nahi).
   Probe throw ho toh runCatching → normal attempt path (fail-open).
5. `services/recording/AudioRecordingEngine.kt` — `ensureConnected` fail par verdict-based
   user message: `noTransportMessageRes(verdict)` (internal helper, companion object).
   Naye strings: `recording_error_no_transport_{generic,no_grant,no_wifi,wd_off}` —
   har message "This call was NOT recorded" se start.
6. `app/build.gradle.kts:127` — 20454/2.4.8 → **20455/2.4.9**.
7. `CHANGELOG.md` — [2.4.9] section.
8. NEW test: `app/src/test/.../adb/TransportReadinessTest.kt` — 12 cases (field-log shape,
   unknowns-never-dead-end, every REACHABLE branch).

## Build/test status
- [x] assembleRelease + testDebugUnitTest — BUILD SUCCESSFUL (40s), **1913/1913** (failures=0 errors=0)
- [x] APK copy /tmp/Callrex.apk — 67,898,329 B, sha256 eeb9bbf2e186324789fddd1646a9b3fa638688b8be9731166d9b78e4bb5e5610
- [x] Git: commit f51fa77, tag v2.4.9, push callrex + main
- [x] GitHub release v2.4.9 (asset Callrex.apk, 67,898,329 B) — `releases/latest` = v2.4.9 (in-app updater fire karega)
- [x] Catbox: https://files.catbox.moe/4ssw10.apk
- [x] User ko Hinglish mein deliver + manual tap-path

## Verify on device (user call se log — PENDING)
- WD auto-on hua ya nahi (log: "enabling Wireless debugging")
- Recording start hua, recordings list mein entry
- Agar WD auto-on fail (koi reason) → turant "NOT recorded" error notif, 84s nahi
