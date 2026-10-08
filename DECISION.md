# DECISION.md — Callrex (CallVault fork)
_Last updated: 2026-10-08 — v2.4.10 shipped (recordings-vanishing fix + transcription-first delete)_

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
