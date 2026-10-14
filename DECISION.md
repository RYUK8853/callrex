# DECISION.md — Callrex (CallVault fork)
_Last updated: 2026-10-14 — LOG BEACON done + live-verified against running Node (real POST/GET tests). Full suite 1967/1967 green. NEXT: release build v2.5.0 + real SIP E2E._

## Log Beacon — crash/log self-fetch (2026-10-14)

### User ka demand (verbatim)
"how you can test all this ... how you are going to take the crashes logs in realtime ...
it should be like you can fetch by yourself logs and the errors and crashes. can we do that?"
→ **Haan, ho gaya.** Ab phone khud apne logs/crashes Node pe bhejta hai; main server-side
se log file padh leta hoon — user ko kuch batane ki zaroorat nahi.

### Design (kyun aisa)
1. **Node `POST/GET /debug/logs`** (routes/debugLogs.js) — global server-token auth ke
   BAADE mount, isliye phone bhi wahi `NODEJS_API_TOKEN` use karta hai jo takeover pe
   daalta hai. **Naya credential nahi, naya setting nahi.** Files disk pe
   `data/debug-logs/` (max 50, prune). Response `{accepted:true,file}`.
   - **Live-verified:** server uthaya (Mongo 27777), real POST/GET chala:
     POST+token 200, POST bina token 401, GET list 200, GET detail 200, GET bina token 401,
     disk pe 3 files read-back. Ek real bug pakda: Express-4 me internal path `/` likha
     tha → `/debug/logs` 404; fix `/logs`. File-name me spaces → underscores (name URL
     segment bhi hai). Test data delete.
2. **App `LogBeacon`** (services/takeover) — 3 triggers:
   - **Crash:** `AppLogger.onUncaught` (naya hook) → crash report `files/log_beacon/crash_*.txt`
     me **sync** save (process marne se pehle hi disk pe — ye reliable path hai) + 10s
     best-effort bhejo; agle app-start pe `flushPending` chhoota hua bhej deta hai.
   - **Takeover call-end:** writeback ke baad (ok **ya** failed — failed write-back wahi
     waqt hai jab logs chahiye) `sendAfterCall`.
   - **Manual:** Settings → Voice agent → Diagnostics → "Send logs now" (toast result).
   - Payload: device/app/reason + crash? + **500-line log tail** (AppLogger pehle se
     redact karta hai — phone number/name already masked, naya koi data nahi jaata).
   - **Beacon = takeover ka bachha:** takeover enabled + node URL set nahi → complete
     no-op (koi byte nahi jaati). 5s/15s socket timeouts, har send apni IO scope pe
     `runCatching` — beacon fail ho toh app ko thoda bhi effect nahi.
3. **AppLogger me `onUncaught` hook** — dying thread pe chalta hai, isliye KDoc me saaf:
   fast, side-effect-safe, `runCatching` wrapped (throw real crash mask karega).

### Verified
- Node: live POST/GET + auth negative tests (upar). Test data clean.
- App: compile GREEN (6s); full suite **1967/1967 GREEN** (beacon ke saath — koi regression nahi).
- **Phone pe end-to-end (real crash/call) abhi NAHI** — wo release build + real call ke saath aayega.

### Bachi hui (same as before)
- Release build v2.5.0 (beacon isi release me aayega — koi alag build nahi).
- Real SIP E2E (Vobiz/KYC + phone same Wi-Fi).

## 2.5.0 Dynamic Takeover — OwnerWritebackCoordinator (P5, 2026-10-14)

### Kya hua
P5 ka aakhri open piece — `OwnerWritebackCoordinator` — code likha + **32 naye tests**
likhe jo **6 real production bugs** pakde (jo recon se nahi pakad sakte the). Sab fix.
**Final: `:app:testDebugUnitTest` → 1967/1967 GREEN (1935 purane + 32 naye). Compile GREEN. P5 CODE-wise DONE.**

### Key decisions (aur kyun)
1. **Trigger = combine(ownerWriteback, current) + CAS.** Overlay owner-join pe
   `ownerWriteback` set karta hai (tab `current` abhi set hai → guarded skip); call-end pe
   monitor `current` clear karta hai — yahi real fire point. CAS (`inflightCallIds.add`)
   se ek call pe writeback **sirf ek baar** — ring repeat pe bhi duplicate nahi.
   (User: "0 times" data-loss; trigger miss = Node pe khaali record.)
2. **CRITICAL timing:** trigger ANSWER pe fire hota hai, lekin Room catalog row sirf
   call-END (file finalize) pe likhi jati hai (VoipRecordingCoordinator stop path).
   Isliye `waitForRecording()` **poll** karta hai (10s interval, 2h cap) — ek-baar
   check hamesha null deta.
3. **`_voip` suffix REQUIRED matching me:** time-window alone kafi nahi — carrier call
   bhi same window me ho sakti hai. `_voip` sirf VoIP recording match karta.
   (Wrong recording ka transcript sahi call_id pe write = corruption — sabse khatarnaak bug.)
4. **`roleForChannel` A_IS_FAR inverted tha (TEST pakda):** A = far party = caller =
   "user" (pehle "assistant" tha). B (owner) = "assistant".
5. **BOTH/SILENCE skip (TEST pakda):** `fromKey()` BOTH/SILENCE pe valid value deta hai
   (null nahi) — `?: continue` unhe skip nahi karta tha. Explicit guard laga.
6. **parseTranscript — 3 latent bugs (TESTS pakde):** (a) `runCatching{...return}` poora
   function short-circuit karta tha (fallbacks dead code); (b) top-level array
   `JSONObject()` me crash; (c) org.json: no-arg `optString("missing")` → literal `"null"`,
   empty array pe `optString` → `"[]"` — dono text me leak. Fix: `(opt(key) as? String)?.`.
7. **`observeTranscript` arg order (recon pakda):** `getTranscript(nodeUrl, callId, nodeToken)`
   — token PATH segment ban raha tha. CallId route param hai; token auth header ka.

### Abhi bhi open (not verified)
- **Real E2E (SIP owner pickup → Node writeback):** blocked on Vobiz number + KYC + phone online.
- **Release build v2.5.0:** abhi nahi bana (user ko phone me update chahiye).
- **Phone offline** (ADB no devices); Node :3000 DOWN; local Mongo 27777 EMPTY; AI/legacy box unreachable
  — transcript shape **LiveKit SDK source se verify** kiya (ground truth enough).

### Files
- NEW `services/takeover/OwnerWritebackCoordinator.kt` (306)
- NEW test `services/takeover/OwnerWritebackCoordinatorTest.kt` (15)
- NEW test `services/takeover/TakeoverApiClientTest.kt` (17)
- MOD `TakeoverApiClient.kt` (parseTranscript/parseItems/buildUpdateBody + JSONArray import)
- MOD `TakeoverCallRepository.kt` (observeTranscript arg order)
- MOD `CallVaultApplication.kt` (attach() hook ~151)

---


## 2.4.11 addendum — Fish Audio STT (user directive: "we will use fish.audio for stt for now")

### Decision
- **STT = fish.audio `transcribe-1-pro`** as the cloud engine (user-configurable, local whisper untouched = fallback).
- **Summary = user's own model** ("summary hm apne model se likhwa lenge") — no summary engine change this round.
- **Privacy relaxed** (user: "this app is for our use only no privacy problem for now we want accuracy and best results").

### What's built (verified: build green, 1935/1935 tests, APK versionCode 20457 / v2.4.11)
1. `CloudApiClient.transcribe` routes by host: `api.fish.audio` → fish path, else existing OpenAI path (Groq/OpenAI/Ollama-proxy unaffected).
2. Fish path = native `POST {base}/asr`: `model` header (docs: header miss silently downgrades to `transcribe-1`),
   `ignore_timestamps=false`, multipart `audio` field, **no `language` field** (API has none — server auto-detects).
   15-min read timeout (fish docs ceiling 900s; app's 60-min max recording fits).
3. `parseFish`: `speaker_turns` → one timed segment per utterance (app's A/B labeller works on the timings);
   else whole punctuated `text` = ONE clean segment (word `segments` are per-word, no punctuation — wrong unit).
   `<|speaker:N|>` markers stripped in BOTH paths (bug caught by the empty-turns test).
4. UI: "Set up Fish Audio" one-tap button in transcription settings (fills base URL + model; user pastes key only).
   New string `cloud_fish_setup` in default values (cloud_* keys are English-only in this codebase — convention).
5. Tests: `CloudApiClientFishTest` (8 tests, real live-API response shapes from 2026-10-12 capture).

### Key handling (IMPORTANT)
- User's key is masked by the platform in chat — **the full key never reaches my tools** (verified: I could only save
  the 13-char masked form). `~/.callrex/keys/fish_audio` holds ONLY the masked form — useless for API calls.
- **The key goes into the APP on the phone only** (Settings → Transcription → Cloud → "Set up Fish Audio" → paste key).
  It is stored in Android SharedPreferences on-device, never in this repo/git/logs. That satisfies
  "do not expose anywhere just save and give me when asked" — I can never return the full key because I never received it unmasked;
  the user still has it (they typed it) and it lives on their phone after install.
- Live API test was BLOCKED (401 with masked key) — the parser is tested against captured real response shapes instead;
  end-to-end fish verification happens on the first real call after the user installs + pastes the key.

### Release state (v2.4.11, PUBLISHED 2026-10-12 21:32 UTC — user GO "clean push + build")
- Commit `13ced3f` (33 files, +790/−27) → branch `callrex` pushed to RYUK8853/callrex (f51fa77..13ced3f).
- Annotated tag `v2.4.11` pushed.
- **GitHub release LIVE**: `https://github.com/RYUK8853/callrex/releases/tag/v2.4.11`
  (release id 410809732), asset `Callrex.apk` 67,921,409 B, state=uploaded.
- **In-app update path verified end-to-end** (simulated GitHubReleases.kt): `/releases/latest`
  → tag v2.4.11, not draft/prerelease, asset name `Callrex.apk`, https URL, size>0;
  download URL live; **Range → 206** (resumable download works); **sha256 live == local**
  (`13f2feec7f9cb1e6`). Phone pe app kholne se update banner dikhega (versionCode 20457 > installed).
- **Catbox mirror**: `https://files.catbox.moe/bvyrel.apk` (Range-verified full size 67,921,409).
- Build note: `assembleRelease` needed the documented `-PallowMissingTranslations=true`
  escape (21 new base strings, present since v2.4.10, not yet in 13 locales). Follow-up:
  translate the 21 keys (cloud_*, recording_error_*, settings_debug_log_copy, summary_engine_label,
  transcription_engine_label, transcription_model_highest) — v2.4.12 scope.
- Token note: keychain git token HAS API scope this time (v2.4.6-era "git-only 401" was a
  different token); asset upload pitfall: URL is `uploads.github.com`, not `api.github.com`
  (the latter 404s on /assets).
- R3 (handoff root cause) still deferred — R1 diagnostics will surface the daemon-side reason on the next failed call.

## 2.4.11 — Crash fixes (R1/R2/C) + Transcription (T1/T2)

### Kya hua (doni logs verified: 36B5C989 debug + ADE220A8 system)
- **Handoff ON tha but `startHandoff` 11/11 false** — har call raw daemon path pe, 4 mid-call daemon deaths = audio lost.
- **System report: 3 STILL OPEN VOICE_CALL captures** (pid 25034) — failed handoff ke leaked mic captures.
- **Transcription `lang=auto` chal raha tha** — whisper en/hi/ur flap karta tha (user ka exact symptom).

### Changes
1. **R2 — `HandoffSource.deliverToApp` mic-leak fix**: `heldRecord = record` ke baad 3 failure returns
   (no native ptr / extractBinder null / delivery !ok) mic release nahi karte the + exception path pe bhi.
   Ab sab `releaseHeld()` karte hain (idempotent, `CaptureAudit.released` ke saath).
2. **Fix C — no-audio guard (`RecordingForegroundService.routeFinalRecording`)**: sirf 0-byte check tha;
   ab `AudioDecoder.hasAudioTrack()` (MediaExtractor, `audio/*` track) — headless container (bytes hain,
   audio nahi) catalog/transcription mein nahi jata; same "recording was empty" error notification.
3. **R1 — daemon log ab report aayega**:
   - Root cause (code+log verified): daemon ka ring in-memory + per-process; report sirf LIVE daemon ka
     ring drain karta tha; failed daemon "Clearing N other recorder process(es)" se pehle kill ho chuka tha.
     Ring sirf `ringEnabled` (user logging ON) pe collect karta tha — normal install pe khali.
   - Fix (a) `AppLogger`: daemon process (`prefs == null`) mein ring ALWAYS collect (bounded 4000, in-memory,
     disk nahi). (b) `AudioRecordingEngine`: handoff fail hote hi `service.drainDiagnostics()` → app ke
     persistent log mein. Agla report daemon-side reason dega.
4. **T1 — Hindi + Urdu first-class**: `SUPPORTED` + `NON_LATIN` (hi→Devanagari, ur→Arabic) + picker labels
   (14 locale strings files, proper translations) + `TranscriptionLabels.languageOf`. Side effect:
   `WrongScriptRetry` ab hi/ur pins pe active (Latin-script transcript → re-decode).
5. **T2 — language flap khatam** (user: "majorly hindi ya english hi rahti hai"):
   - Fresh install: `defaultLanguage()` ab phone locale pin karta hai (unsupported pe `en` fallback, auto nahi).
   - **One-time migration** (`migrateAutoDetectToLocalePin`, AppApplication.onCreate): stored `auto` →
     phone ki language (logged, reversible per-call dialog se). Ye phone `lang=auto` tha — fresh-install
     fix se kaam nahi padta tha, isliye migration.
   - **Per-call language dialog ab default ON** — 1 confirming tap (en), 1 switch (hi), koi bhi language reachable.
6. Version 20457 / 2.4.11. Naye tests: 6 (hi/ur locale mapping + hi unsupported-before + migration x3).

### Verified
- Tests **1927/1927** (baseline 1921 + 6; failures=0 errors=0 skipped=0)
- `assembleDebug` + `assembleRelease -PallowMissingTranslations=true` green
  (checkTranslations ke remaining missing keys pre-existing the — v2.4.10 se, documented escape hatch)
- APK: /tmp/Callrex.apk, 67,913,553 B, sha256
  `bef1f6d07f3f83514b0508105ca400d22cdc8117d2c905816e74ae2a9c598a16`
- aapt2: `versionCode='20457' versionName='2.4.11'`

### Known / BAKI
- **Handoff ka actual root cause (kyun 11/11 false) abhi unknown** — R1 sirf diagnose karega; next field
  report ke baad fix (v2.4.12). Mid-call daemon death pe audio tab tak lost ho sakta hai jab tak handoff kaam na kare.
- WD-off → daemon kill ka correlation valid hai (launcher code + log). User: WD on rakhna.
- Release: DONE 2026-10-12 — commit 13ced3f + tag v2.4.11 + GitHub release (API token had scope) + Catbox bvyrel.apk.

## 2.4.10 — Recordings-vanishing fix (v2.4.9 log ka root cause)

### Kya hua (field log 21:01:10 export, Nothing A001)
Transport ab THEK tha (20:40 pe WD auto-on → loopback :50838 → daemon binder → journal-end success).
**Dono calls RECORD hui** (20:45 WhatsApp VoIP 813KB/50s farPartyHeard=true; 20:59 normal call 363KB/22s
DIRECT AudioRecord) — phir dono GAYAB:
1. `Storage target: DRIVE` + **Drive folder = wahi Recordings folder** (dono `primary:Recordings`).
2. Publish ke **545ms baad** `RecordingCopyWorker: already in Drive; not uploading it again` —
   real cloud upload mein possible NAHI → destination mein apni hi file mili (self-reference).
3. DRIVE mode `KEY_DELETE_LOCAL=true` → `finish()` ne **wahi original delete** kar di → kahin koi nahi.
4. **Race:** transcription sirf local file se padhti hai (no Drive fallback) → delete pehle jeet gaya
   → `FileNotFoundException` → "transcription cant be done" → manual tap → "no longer in the catalog".
5. 21:00:40 `HomeViewModel: Recording gone (deleted outside the app); pruning stale entry` → 0 recordings.

### Changes (kya fix kiya)
1. **`SafHelper` — 2 naye pure-URI predicates** (`isDocumentInTree`, `isSameFolder`).
   Reason: pure URI id comparison (percent-encoding safe, no provider round-trip) — field-bug ki
   shape detect karni hai bina file touch kiye.
2. **`RecordingCopyWorker`:**
   - `doWork` start: source destFolder ke andar hai → **refuse** + warning notification
     (`recording_error_drive_folder_is_recordings`) + `markDrive(src, deleteLocalAfter=false)`
     (row file ki real jagah point karegi → list/playback/transcription sab resolve) + success.
   - `finish()`: delete se pehle re-check `isDocumentInTree` (settings mid-flight badal sakti hain)
     + `transcriptionSettled(name)`: **sirf DONE = delete OK**; QUEUED/RUNNING/FAILED/no-row =
     file device pe rahegi; `Result.retry()` jab tak `DELETE_WAIT_ATTEMPTS` (= MAX_ATTEMPTS=10)
     na ho; phir terminal success with **BOTH copies** on the row.
3. **`SyncSweepWorker`:** same-folder (tree-vs-tree) → sweep skip (library self-delete se bacha);
   per-file delete pe wahi `transcriptSettled` gate (DONE-only).
4. **Settings + Wizard:** recordings folder ko cloud backup ke roop mein pick karna REFUSE
   (`folder_same_as_recording_rejected` toast) — misconfiguration ko darwaze pe roka.
5. **`AppLogger.writeConfiguration`:** export ab "Recordings folder: X" + "Drive folder: Y" naam
   dikhata hai — agla log first-read pe misconfiguration batayega (3 exchanges nahi).

### Verified
- Build SUCCESS, tests **1921/1921** (1913 purane + 8 naye `SafHelperSameFolderTest`; failures=0 errors=0)
- APK: /tmp/Callrex.apk, 67,910,449 B, sha256 `b9f2623f4a34a56eb0d981de23e38b635f44e0a3ad690b9dc9047bfdb04c585f`
- aapt verified: `versionCode='20456' versionName='2.4.10'`
- Git: commit 2f6a5ab, tag v2.4.10, push callrex→main + tag done
- Catbox: **https://files.catbox.moe/zcwobt.apk**
- State: `v2410-STATE.md`
- **GitHub release v2.4.10 LIVE** (BrowserOS neo signed-in profile se banaya, v2.4.6/2.4.9 waisa):
  https://github.com/RYUK8853/callrex/releases/tag/v2.4.10 — asset `Callrex.apk` 67,910,449 B
  (API /releases/latest se verify: size exact match). In-app updater ab v2.4.10 dega.

- **FIELD TEST PASSED (user-confirmed, 2026-10-08):** v2.4.10 install ke baad ek normal test call
  karo — **record + transcription + AI summary sab on-device kaam kiya.** Recording engine ab
  verified. Auto-record (incoming/outgoing/VoIP) config mein already ON → koi bhi aane wali
  call khud record hogi. Remaining watch: Wireless debugging ON rakhna (transport dependency).

### Known / BAKI
- API se release nahi bana paya (git credential = git-only scope, 401 on /releases + basic-auth;
  keychain secret yahan visible nahi; `.env.bak` token placeholder tha) → BrowserOS neo browser
  session se publish kiya. **Pattern: agle releases bhi wahi raasta.**
- **Dono purani recordings (20:45 + 20:59) device se gayab** — v2.4.9 ne delete ki thi; recover
  impossible. v2.4.10 sirf future ko rokta hai (yeh user ko batana zaroori hai).
- User ko: v2.4.10 install → Settings → Storage → **Drive backup folder dobara select karo**
  (v2.4.10 picker same-folder refuse karega; purani config rahe toh runtime guard + notification
  handle karegi) ya storage target "Both"/"Local" pe le jao (tab koi delete nahi hota).

## 2.4.9 — Transport fix (v2.4.8 log ka root cause)

### Kya hua
1. **Root cause (v2.4.8 log, Nothing A001):** recording kabhi start NAHI hui. WD off + USB off +
   adbd STOPPED tha. App khud WD on karna chahti thi par gate ne `RESPECT_USER` bola —
   `enforced=false` (setting default OFF) + `userTurnedOff=true` (false-positive: boot/default-off
   ko user-off maan liya). Isliye ~84s silent retry → notification removed → "crash" perception.
   0 recordings. Screen-off timing coincidental tha.
2. **`isWirelessDebuggingEnforced()` default `false` → `true`** (AppPreferences.kt)
   - Reason: app ka kaam hi recording hai; fresh phone pe transport available hona chahiye.
   - Safety: setting Settings mein hai; user off kare toh purana behaviour (switch absolute) — respected.
3. **Fail-fast probe — naya `TransportReadiness.kt`**
   - Launcher start se pehle check: kya koi transport possible hai? Proven dead end → ms-level fail.
   - Sirf PROVEN dead end short-circuit (adbd STOPPED + USB proven OFF + gate refuse).
   - Koi bhi UNKNOWN reading → REACHABLE (path mat kato). Probe crash → runCatching → normal path (fail-open).
4. **Error notification actionable** (4 naye strings, verdict-based):
   - "This call was NOT recorded — …" + exact fix (Wi-Fi / WD on / pairing over USB / generic).
5. Version 20455 / 2.4.9. Naya test: `TransportReadinessTest.kt` (12 cases).

### Verified
- Build SUCCESS (40s), tests **1913/1913** (1901 purane + 12 naye; failures=0 errors=0)
- APK: /tmp/Callrex.apk, sha256 `eeb9bbf2e186324789fddd1646a9b3fa638688b8be9731166d9b78e4bb5e5610`
- State: `v249-STATE.md`

### BAKI (user action)
- v2.4.9 install karo → ek call karo → log bhejo (WD auto-on + recording confirm karna hai).

## 2.4.8 — Screen-off crash fix + log export

### Kya hua
1. **Screen-off crash fix** — recording ab whole call ke liye CPU wake lock
   (PARTIAL, 1h time-boxed) hold karti hai. Root cause: poore codebase mein
   koi wake lock NAHI tha (na WAKE_LOCK permission, na code) — foreground
   service sirf process rakhta hai, CPU nahi; screen off → CPU soya →
   capture thread suspend → mid-call break. Yahi user ka symptom tha.
2. **Log export fix** — Settings → Debug mein Share/Save buttons ab HAMESHA
   visible (pehle sirf jab logging OFF — exactly tab chhup jaate jab user
   bug capture karke log bhejne aata tha). Log viewer mein Copy button
   (clipboard) — WhatsApp/Telegram/email mein paste.

### Decisions + kyun
- **Wake lock recording service mein, acquire = pipeline Active, release =
  stopRecordingSessionAndService() ke top + onDestroy()** — service kill
  ho jaye to bhi lock held nahi rahega; 1h lease se stuck release se phone
  brown-out nahi hota.
- **stringResource onClick lambda mein nahi chalta** (plain lambda, no
  composable context) → strings composable-level hoist kiye. (Compile error
  aaya, fix.)
- **GitHub: purana v2.4.7 release delete, naya v2.4.8** — in-app updater
  `releases/latest` check karta hai; asset exact `Callrex.apk` naam.
- **v2.4.7 release asset ab tak missing tha** → ab v2.4.8 ke saath live;
  in-app mandatory update dono ke liye fire karega (old users latest = 2.4.8).

### Verified
- Tests 1901/1901 pass; aapt2: 20454/2.4.8; `releases/latest` = v2.4.8 +
  Callrex.apk (67,888,197 B); Catbox: https://files.catbox.moe/2hnvt5.apk
- State: `v248-STATE.md`

## 2.4.6 — Hybrid AI + mandatory updates + sponsor removal

### Kya hua
1. **Hybrid AI pipeline ("dono")** — local on-device whisper + user-ka-apna cloud API.
2. **Mandatory update system** — har launch pe update prompt jab tak install na ho; in-place
   update (data/AADB pairing safe) wahi certificate ke saath.
3. **Sponsor/donation links removed** — Ko-fi/PayPal dialog, Home pill, About row, 14 locales ke
   strings. Telegram community pill RAKHA (donation nahi).
4. **Transcript UI polish** — monospace timestamps, uppercase letter-spaced speaker labels
   (Nothing-style).
5. **GitHub push done** — main + callrex branch + tag v2.4.6. Release banana pending (see below).

### Decisions + kyun
- **Cloud = user-ka-apna endpoint, zero preset.** `POST {baseUrl}/v1/audio/transcriptions`
  (WAV 16k mono multipart) + `POST {baseUrl}/v1/chat/completions`. Key/URL/model sirf
  SharedPreferences mein — export allow-list se EXCLUDE, log mein host-only (URL masked).
  HTTP library project mein nahi thi → `HttpURLConnection` + `org.json` (no new dependency).
- **Cloud transcription decode on-device:** `AudioDecoder.decodeRange` → WAV → upload. Speaker
  labelling same decode pe ride karti hai (cloud transcript ko bhi A/B labels). WrongScriptRetry
  cloud pe bhi chalta hai. Misconfigured cloud → local fallback (retry loop nahi).
- **Cloud summary = `CloudSummaryHost`** sirf `generate()` swap karta hai; chunking/parsing/
  citation-strip/dedupe sab local pipeline jaisa.
- **DEFAULT local model = `LARGE_V3_Q5_0`** (whisper large-v3 Q5_0) — Hermes reference
  (faster-whisper large-v3 = "perfect") se match.
- **Mandatory update:** `updatePopupTag()` ab "shown once" pref se filter nahi → har launch pe.
  Dialog back/scrim dismiss NAHI; "Remind me later" = isi launch ke liye. `ApkVerifier` pin =
  naya cert `2505c33a…` (wahi cert jo delivered v2.4.5 apps pe hai) → in-place update.
  **`signing/callvault-signing.keystore` (gitignored) MAKAAN PE RAKHO — kho gaya to dobara
  in-place updates kaam nahi karenge.**
- **Update source:** `RYUK8853/callrex`, asset naam exactly **`Callrex.apk`** (updater sirf wahi
  dhoondhta hai; test fixture bhi update kiya).

### Verified (real runs)
- `assembleDebug` + `assembleRelease -PallowMissingTranslations=true` — BUILD SUCCESSFUL
- Release APK: 67.9 MB, cert SHA-256 `2505c33a52c2687a9ebda44846826f0293c1524198bc674c08294478db7e6cfb`
  (= installed apps ka cert), versionCode 20452 / 2.4.6
- `testDebugUnitTest`: **1901 tests, 0 failures**
- Push: main `c9b7251..4972ba9`, branch `callrex`, tag `v2.4.6`

### BAKI (user action)
1. **GitHub release v2.4.6 + `Callrex.apk` asset.** API token git-only scope hai (401 on
   /releases); BrowserOS window band tha; local Chrome profile locked → main bana nahi paya.
   Koi bhi: (a) BrowserOS neo window kholo ya Chrome band karo → main khud bana dunga;
   (b) manual — github.com/RYUK8853/callrex/releases pe "Set up the release" (tag v2.4.6),
   APK attach, naam EXACTLY `Callrex.apk`, publish. Jab tak release nahi, current apps ko
   update nahi dikhega (API /releases/latest 404 deta hai).
2. Phone pe v2.4.6: update popup, ek cloud transcribe + summary, speaker labels, local large-v3 speed.

### Known
- Cloud strings abhi sirf base (en) mein — 13 locale English-fallback render karte hain tab tak
  translations na aayein (`-PallowMissingTranslations=true` = documented escape hatch).
- Release ke baad F-Droid/Obtainium track alag step hai.

---

## History
- **2026-10-05:** Fork decision (CallVault, nahi scratch), CallSage→Callrex rename, Play Store NAHI
  (F-Droid/Obtainium/direct APK), ADB+scrcpy capture arch, on-device AI baseline (whisper.cpp +
  llama.cpp + Gemma 2.6GB), orphan-branch GitHub push, README+screenshots, Telegram APK delivery.

## 2026-10-14 — v2.5.1 release bhi live ho gaya
- GitHub release v2.5.1 (tag pe sahi, Latest) — Callrex.apk 81,571,556 B, API + HEAD download se verified (200).
- First publish attempt me tag drop hua (untagged commit pe bana) + APK upload publish se pehle complete nahi hua tha. Fix: release edit, file remove + fresh upload, upload API pe `uploaded` dikhne ke baad hi save.
- BrowserOS neo gotcha: GitHub release page ka binary file input hidden hai (aria-hidden + sr-only). Unhide karo (aria-hidden hatao + size do), snapshot me "Choose files" button aata hai, us ref pe `upload` tool se file set karo. API token push-scope ka hai — API se release/asset nahi bana sakte, sirf browser hi kaam karta hai.

## 2026-10-14 — Callrex code ab /Users/vishal/code/callrex pe
- User ne kaha: saara code local sirf /Users/vishal/code/ me, koi aur folder nahi.
- Repo /Users/vishal/dev/callvault-fork → /Users/vishal/code/callrex move hua (git intact, remote callrex = RYUK8853/callrex, branch callrex, latest commit eea35ea v2.5.1).
- Node API already /Users/vishal/code/voice-agents/zantacall/nodejs-api pe tha.
- Ab code locations: app=/Users/vishal/code/callrex, node=/Users/vishal/code/voice-agents/zantacall/nodejs-api.

## 2026-10-14 — Real-call test attempt #1: root cause = carrier, not app
- Vishal ne +91 80642 69004 (Vobiz) pe call kiya → overlay nahi aaya.
- Verified chain: phone v2.5.1 engine ko 2s me poll kar raha (logs me 200 OK), beacon crash-log Node pe aaya (beacon+network OK), engine monitor ON, LiveKit list_rooms = **0 rooms** (call LiveKit tak nahi pahunchi), Vobiz console = **0 calls today** (call Vobiz tak nahi pahunchi).
- Conclusion: PSTN-level provisioning lag — number aaj hi (10-14) purchase hua, external inbound routing abhi live nahi. App/engine sab ready hai.
- Number final verified: Vobiz console se +91 80642 69004 (Mongo me stale US placeholder tha → +91 80642 69004 me update kiya, vobiz-console-verified metadata ke saath).
- Alag bug fixed: v2.5.0 crash "Can't toast on thread that has not called Looper.prepare()" (LogBeacon.sendNow callback IO-thread pe → SettingsScreen Toast). Fix: sendNow ab Handler(mainLooper) pe callback post karta hai. compileDebugKotlin GREEN (42s). UNCOMMITTED.
- Next: 1-2h baad doosre SIM se retest; phir kal. Agar Vobiz console me bhi 0 calls → Vobiz support (carrier provisioning).

## 2026-10-14 — Normal-call takeover overlay: BANAYA (v2.5.2 candidate)

### User ka demand (verbatim, multiple times)
"i was saying if third person called me on my phone the overlay should trigger where i can select whether i will forward this to agent or not" + "maine jo bataya hai wo working me kar".
Choice: "Overlay pehle banao, button action main choose karunga".

### Kya bana
1. **NEW `NormalCallTrigger.kt`** — single choke point `CallSessionManager.handlePhoneState` se feed hota hai (broadcast receiver + post-boot live TelephonyCallback dono yahin se). RINGING + takeover ON + koi call in-flight nahi → overlay raise. Number late (Android double-broadcast) → second broadcast me fill. OFFHOOK/IDLE → local overlay auto-close.
2. **`RingingMonitorService`** — `ACTION_LOCAL_RINGING` + `raiseLocalRinging()` = best-effort process warmth sirf (persistent notification). Overlay launch FGS pe dependent NAHI (Android 15+ background FGS start block ho sakta hai; overlay primary path hai).
3. **`TakeoverOverlayActivity`** — local (`roomName == "local_normal_incall"`) vs SIP branch. Local: 3 buttons abhi sirf overlay band karte hain (call dialer par chalti hai) — "agent utha le" ka real action (call-back/forwarding) user ke choice par baad me. SIP path untouched.
4. **Manifest** — `SYSTEM_ALERT_WINDOW` added. ZAROORI: normal call pe dialer foreground, CallVault background → background activity launch (overlay) Android 10+ sirf is permission se exempt. Bina iske overlay silently block.
5. **Settings → Voice agent** — naya row "Allow show over other apps" (one-tap system settings, return par ✓ refresh).

### Verified (real)
- `:app:compileDebugKotlin` GREEN
- `:app:testDebugUnitTest` **1967/1967, 0 failures, 0 errors** (JUnit XML se count)
- Field test (phone pe real call) — PENDING v2.5.2 install ke baad

### Known limitations (honest)
- Overlay permission bina diye overlay nahi aayega (Settings me one-time grant; missing par log + beacon fire).
- Live carrier call ko app se agent me bridge karna Android pe allowed nahi — provider-side mechanism (call-back/forwarding/InCallService) chahiye; user choose karega.
- R3 screen-off death field test abhi bhi pending.
