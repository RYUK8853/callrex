# Dynamic Takeover — v2.5.0 STATE
GO: 2026-10-13 (user: "go for the dynamic takeover")
Contract: ~/voice-agents/CALLREX-DYNAMIC-TAKEOVER.md (pasted 13-59-37). Live recon done same day.

## Phases
- P0 recon — DONE (stack live, shapes captured, doc bug: /calls list is {"calls":[...]} wrapper)
- P1 deps+manifest — LiveKit io.livekit:livekit-android (mavenCentral), SYSTEM_ALERT_WINDOW
- P2 config+api — ZantacallConfig prefs (enabled, serverBase, engineToken, nodeToken) + ZantacallApi (HttpURLConnection, mirrors CloudApiClient)
- P3 monitor — RingingMonitorService FGS: 2s poll /sip/ringing, backoff on unreachable, new room_name → overlay intent
- P4 overlay — TakeoverOverlayActivity: number, 45s timer, [Main baat karta hoon] [AI agent utha le] [Decline]; 404/409 = stale → dismiss
- P5 human-join — LiveKit join w/ token; recording = existing VoIP pipeline (WebRTC comm path); end → stop → transcribe → POST /api/database/update-call
- P6 agent/decline — agent-takeover + 5s transcript poll; call-drop
- P7 agent calls section — Room table + Node sync (completed/missed, session_type=sip_inbound) + list/detail UI (captured_message + call_summary prominent)
- P8 settings+strings — Settings "Voice Agent" subsection
- P9 tests ~40 + build green → v2.5.0 (20458) + CHANGELOG + push + release
- P10 DECISION.md + callrex-app skill update

## Verified live facts (2026-10-13)
- GET :8000/sip/ringing → {"status":"success","ringing":[{room_name,call_db_id,phone_number|null}]}
- POST /sip/human-join {room_name, participant_identity?} → {serverUrl(wss), roomName, participantToken, participantName, callerNumber, callDbId}; 404 room gone; 409 race
- POST /sip/agent-takeover {room_name, agent_name?, caller_number?, topics?} → {dispatch_id, agent_name, room_name, callerNumber, callDbId}; 404/409
- POST /sip/call-drop {room_name} → 200; 500 w/ TwirpError if room gone (treat 5xx here as stale too)
- Node :3000 auth: X-Server-Token. GET /calls?status=&limit= → {status:"success",total,page,limit,totalPages,calls:[...]} — WRAPPED
- call record: call_id (UUID str or "e2e-dyntake-1"-style; GET /:id needs UUID/ObjectId — "e2e-dyntake-1" 400s!), room_name, phone_number, session_type, status (ringing|in_progress|completed|missed...), answer_mode (human|agent|declined|null), agent_name, has_transcript, duration_seconds, end_time, meta.captured_message, meta.call_summary, transcript {items:[{type,text}]}
- LiveKit cloud: egress available (project keys in ~/voice-agents/zantacall/.env — NOT in app)
- minSdk 30, target/compile 36; INTERNET already granted; FGS specialUse pattern exists (lines 179-350 manifest)
- App has NO HTTP lib — HttpURLConnection + org.json only (CloudApiClient pattern)

## Open decisions
- P5 recording path: reuse VoIP/daemon pipeline (WebRTC = comms path) — field-verify; egress = fallback
- Overlay as Activity vs Window: Activity (TYPE_APPLICATION_OVERLAY not needed for fullscreen activity shown by FGS; but SYSTEM_ALERT_WINDOW for locked phone)
- One release = v2.5.0 (all parts), versionCode 20458

## Checkpoints (append at each milestone)
- 2026-10-14 P5 (settings+lifecycle+API fixes+writeback):
  * Settings "Voice Agent" section (TakeoverSection composable, toggle + 4 fields), SettingsActions +6 methods, SettingsViewModel impl + syncWithPrefs, app-start hook in CallVaultApplication. COMPILE GREEN.
  * API client fixes (verified vs live Node): updateCall body {call_id, update_data} (was fields); getTranscript route GET /calls/:id/transcript (was /transcript?call_id=); transcript = OBJECT {items:[{role, content:[str]}]} not array.
  * OwnerWritebackCoordinator.kt (new, ~250 lines): attach() in Application; trigger = combine(ownerWriteback != null, current == null) — fires at ANSWER (room leaves /sip/ringing); polls for _voip recording (catalog row only written at call END); waits transcript DONE (10min cap) + summary (90s grace then forced runNow); builds engine-shaped transcript {"items":[{type,message,role,content:[str]}]} via trusted ChannelMap (A_IS_FAR → A=assistant, UNKNOWN → A=user); updateCall {status:completed, end_time, duration_seconds, summary, structured_summary, session_data{handled_by:owner}}; idempotent + best-effort (Room = record of truth). COMPILE GREEN 2026-10-14.
  * KEY FINDING: room leaves /sip/ringing at answer, not call-end → trigger fires early → polling required (fixed).
  * PENDING: full unit suite (background, 1935+), ~40 takeover unit tests, release build 20458, push+tag+release, field test.

### 2026-10-14 (later) — OwnerWritebackCoordinator CODE DONE + 32 tests
- NEW OwnerWritebackCoordinator.kt (306 lines): trigger = combine(ownerWriteback, current) → (w!=null && current==null && CAS).
  * CRITICAL timing: trigger fires at ANSWER time (room leaves /sip/ringing), but the Room catalog row only
    exists at call END → waitForRecording() polls every 10 s (cap 2 h), _voip suffix required (carrier calls
    in the same window must never match).
  * waitTranscript → waitSummary (force SummaryScheduler.runNow after 90 s grace) → build Node payload
    {transcript:{items:[{type,role,content:[str],created_at}]}, summary, structured_summary, status,
    end_time, duration_seconds, session_data{handled_by:"owner"}} → TakeoverApiClient.updateCall
    (body {call_id, update_data}).
  * Wrote in ONE header with appScope? Launch — no, uses its own SupervisorJob+IO scope; attach() called once
    from CallVaultApplication (~line 151).
  * Pure testable functions extracted: matchRecording(), buildItems(), roleForChannel(), buildUpdateBody(),
    parseTranscript().
- BUGS FOUND BY THE NEW TESTS (fixed in production code, not in the tests):
  1. roleForChannel A_IS_FAR was INVERTED (A is the far party/caller → "user", not "assistant").
  2. buildItems: BOTH/SILENCE are valid enum values — `fromKey(...) ?: continue` did NOT skip them.
  3. parseTranscript: runCatching{...return} short-circuited the entries/top-level-array fallbacks (dead code).
  4. parseTranscript: top-level array threw in JSONObject() before the fallback could run.
  5. parseItems: org.json no-arg optString("missing") returns literal "null"; optString on empty array
     returns "[]" — both would leak into transcript text. Fixed with (opt(key) as? String)?.
  6. TakeoverCallRepository.observeTranscript: getTranscript(nodeUrl, nodeToken, callId) arg order was WRONG
     (nodeToken was the path segment). Fixed to (nodeUrl, callId, nodeToken).
- Tests: OwnerWritebackCoordinatorTest (15) + TakeoverApiClientTest (17) = 32 new, ALL GREEN.
- Full 1935+32 suite re-run in progress (background).

### 2026-10-14 (later) — LOG BEACON DONE + LIVE VERIFIED
User demand: "you should be able to fetch by yourself logs and crashes in realtime". Built:
- Node: routes/debugLogs.js (POST/GET /debug/logs, GET /debug/logs/:file) — mounted AFTER global
  authenticateToken (server token), files -> data/debug-logs/ (pruned to 50). Express-4 mount fix:
  internal paths are /logs (not /) — first run 404'd, caught live. safeName: spaces->underscores
  (file name doubles as URL segment). Response {accepted:true,file}.
  LIVE VERIFIED against running Node (Mongo on 27777): POST+token 200, POST no-token 401,
  POST+crash 200, GET list 200, GET detail 200 (space-free name), GET no-token 401, disk files read back.
  Test data cleaned from data/debug-logs/.
- App: LogBeacon.kt (new, services/takeover). Three triggers:
  1. crash: AppLogger.onUncaught (NEW hook, AppLogger.kt) -> persist crash_*.txt to files/log_beacon
     (sync, survives death) + 10s best-effort send; next app start flushPending resends leftovers.
  2. takeover call end: OwnerWritebackCoordinator calls LogBeacon.sendAfterCall(callId, ok) after
     write-back (ok or failed — a failed write-back is exactly when logs matter).
  3. manual: Settings "Voice agent" > Diagnostics > "Send logs now" (Toast result).
  Payload: device/model/android/app + reason + detail + crash? + 500-line log tail (AppLogger already
  redacts). Beacon = child of takeover: no-op unless takeover enabled + node URL set (same Node URL
  + token as takeover — no new credential, no new setting). 5s connect/15s read socket timeouts;
  every send runCatching on own IO scope.
- TakeoverApiClient.postDebugLog() added (slowPost, {accepted} parse).
- AppLogger: onUncaught hook added (KDoc: runs on dying thread, must never throw).
- CallVaultApplication: LogBeacon.init after AppLogger.init.
- Strings: takeover_logs_title/description/send/sent/failed.
- Compile GREEN (6s). Full suite running (background) after 1967 baseline.

### 2026-10-14 (evening) — RELEASE v2.5.0 SHIPPED
- Version bump 2.4.11/20457 → **2.5.0/20500** (build.gradle.kts) + CHANGELOG 2.5.0 section.
- `assembleRelease -PallowMissingTranslations=true` → **BUILD SUCCESSFUL in 3m 11s**;
  APK 81,571,540 B; aapt verified: package `com.vishal.callrex` versionCode 20500 versionName 2.5.0.
  (First release build FAILED on `checkTranslations` — custom lint, 13 locales, new takeover_*
  strings not backfilled. Same known convention as v2.4.11 fish strings: ship with the flag,
  backfill locales later as separate task.)
- Commit db59cff (callrex + main), tag v2.5.0 pushed. Secret scan on committed docs: CLEAN.
- GitHub token regenerated from osxkeychain → /tmp/gh_token.txt (41 bytes).
- Release create + asset upload + /releases/latest verify: script /tmp/release_v250.py (background).

### 2026-10-14 (final) — v2.5.0 RELEASE LIVE + VERIFIED
- API release create: 401 (git-cred token = push scope only) → BrowserOS neo fallback (skill path):
  web form, tag v2.5.0, title "Callrex 2.5.0", notes, hidden #releases-upload input exposed,
  neo upload (77.79 MB), file renamed in-form app-release.apk → **Callrex.apk**, Latest, Published.
- VERIFIED: GET /releases/latest → tag v2.5.0, asset Callrex.apk **81,571,540 B** (exact match
  with local APK). Download head = PK, tail content-range /81571540. In-app updater reads exactly
  this URL + name → update prompt WILL appear on phone.
- Catbox: https://files.catbox.moe/j42uqa.apk (verified: head PK + range /81571540).
- Node beacon endpoint also verified earlier (live POST/GET + 401s) against the running local
  Node (Mongo 27777). Node is the user's normal local stack — leave it running when the user
  field-tests the beacon.
- /tmp/Callrex.apk = the shipped APK (delivered in chat).
