# v2.5.5 — STATE (2026-10-17)

## Release status
| Step | Status |
|---|---|
| Code (decline→deflect, OOM gate, crash fix, tel: fix, dialer manifest) | DONE, emulator-verified |
| Version bump 20505 / "2.5.5" | DONE |
| CHANGELOG v2.5.5 | DONE |
| assembleRelease (-PallowMissingTranslations=true) | GREEN, aapt: 20505/2.5.5 |
| Cert vs v2.5.4 | IDENTICAL (SHA-256 2505c33a…) → in-place update |
| Commit + push (callrex + main) | IN PROGRESS |
| Tag v2.5.5 | PENDING |
| GitHub release assets: `Callrex.apk` (in-app updater) + `Callrex-2.5.5.apk` (link) | PENDING |
| Asset byte-verify (sha256 + PK + badging) | PENDING |
| Catbox + verify (range GET head PK + tail content-range) | PENDING |
| In-app updater check: `GET /releases/latest` → v2.5.5 + `Callrex.apk` | PENDING |

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
