# v2.5.5 — STATE (2026-10-17)

## Release status — PUBLISHED 2026-10-17 20:12 IST
| Step | Status |
|---|---|
| Code (decline→deflect, OOM gate, crash fix, tel: fix, dialer manifest) | DONE, emulator-verified |
| Version bump 20505 / "2.5.5" | DONE |
| CHANGELOG v2.5.5 | DONE |
| assembleRelease (-PallowMissingTranslations=true) | GREEN, aapt: 20505/2.5.5 |
| Cert vs v2.5.4 | IDENTICAL (SHA-256 2505c33a…) → in-place update |
| Commit + push (callrex + main) | DONE — `cfff3f6` on callrex AND main |
| Tag v2.5.5 | DONE, pushed |
| GitHub release assets: `Callrex.apk` (in-app updater) + `Callrex-2.5.5.apk` (link) | DONE, release_id 415852952 |
| Asset byte-verify (sha256 `97c3b5f4…f5c8` + PK + badging) | PASS — download from GitHub matches local exactly |
| Catbox + verify (range GET head PK + tail content-range) | PASS — `https://files.catbox.moe/9j2s0d.apk` |
| In-app updater check: `GET /releases/latest` | v2.5.5 + `Callrex.apk` present, prerelease=false |

## Emulator final verification (v2.5.5 RELEASE apk, 2026-10-17 22:03 IST)
Fresh cold boot (conhost recovered) + release APK + correct prefs ownership (uid 10215/u0a215):
- `emu gsm call 1234567890` → AiCallActivity focus in +455ms, clean number, Decline/Answer visible
- **Decline tap → `call.deflect()` → Telecom `REQUEST_DEFLECT` → TelephonyConnectionService → GsmCdmaConnection.deflect** (full chain confirmed in logcat)
- Emulator carrier then rejects: `CallStateException: deflect is not supported for CS` (platform limit — fake network, no real carrier to execute the forward)
- App handled failure gracefully: 0 crashes, call returned to normal RINGING state
- PITFALL found: release build runs as u0a215 (uid 10215), NOT the debug uid — chown'ing seeded prefs to the wrong uid makes SharedPreferences delete+recreate the file, silently wiping takeover settings (gate fails → toast → no deflect). Use `stat -c %u /data/data/<pkg>` for the real uid.

## Links
- GitHub: https://github.com/RYUK8853/callrex/releases/tag/v2.5.5
- Download (versioned): https://github.com/RYUK8853/callrex/releases/download/v2.5.5/Callrex-2.5.5.apk
- In-app asset: https://github.com/RYUK8853/callrex/releases/download/v2.5.5/Callrex.apk
- Catbox: https://files.catbox.moe/9j2s0d.apk
- LAN: http://192.168.1.9:8899/ (apk-test dir, if still up)

## Verified on emulator (callrex2)
- Inbound `emu gsm call 1234567890` → AiCallActivity top-resumed 782ms, no crash
- Vision: clean number, red Decline + green Answer, pill GONE
- Decline tap → `call.deflect()` → Telecom `REQUEST_DEFLECT` → `CallStateException: deflect is not supported for CS` (emulator platform limit) → graceful (caught + toast, call live, 0 FATAL)
- Prefs 4 keys on device, reboot-safe; DIALER role persists
- HeavyModelGate: build + `testDebugUnitTest` GREEN

## Open (next)
1. Real-device E2E: phone install → Make Callrex Default (ECM) → real call → Decline → Vobiz → agent auto-pickup (`RingingMonitorService` → POST /sip/agent-takeover). BLOCKED on: Vobiz inbound provisioning (24-48h from 10-14).
2. R3 screen-off test (0 deaths) — field, post-release.
3. `SipTakeoverClient.kt:230` stale LiveKit fallback removal — user "haan" pending.
4. Owner-side `nodejs-api/server.js:172` LiveKit credentials — separate permission.
5. 13 locales backfill — separate release.

## Known emulator limit
Emulator CS telephony rejects deflect (`deflect is not supported for CS`) — full loop requires real SIM + carrier. Emulator conhost also desynced late in session (calls stop reaching Telecom); app-side registration verified correct (PM + role + mDefaultDialerCache) — emulator instance issue, not app.
