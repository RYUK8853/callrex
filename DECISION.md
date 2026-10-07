# DECISION.md — CallSage (CallVault fork)
_Date: 2026-10-05 | Language: Hinglish_

## 1. App kya hai?
CallSage = Android 16 pe fully on-device call recording + AI summary app.
Normal calls (dono taraf) + WhatsApp/VoIP calls → whisper.cpp transcript → llama.cpp+Gemma summary (intent, key points, decisions, action items, timestamps). Data phone se nahi jaata.

## 2. Sabse bada decision: scratch nahi, CallVault fork
**Kya:** madkongo/CallVault ko fork kiya (`~/dev/callvault-fork`), scratch build NAHI.
**Kyun:**
- CallVault exactly yahi product hai jo Vishal chahiye — aur ye production-tested hai (GitHub issues se sikhke built: 6.4s audio lag fix, vivo crash fix, OEM permission traps sab documented).
- Capture layer (ADB pairing + shell process + scrcpy + VoIP loopback reflection) 654 Kotlin files ka complex system hai jo humne pehle verify kiya — dobara likhne mein 4-6 hafte waste hote, aur har trap pehle se踩过 (stepped-on) tha.
- Unka base: whisper.cpp transcription + Gemma summary + speaker labels (2-channel, bina voice model) + search/tags/export. 95% ready.
- Alternative rejected: (a) ShizuCallRecorder seedha — usme transcription/summary/WhatsApp nahi; (b) scratch — zero reason, pure risk.

## 3. Rebrand approach: partial, staged
**Kya:** abhi sirf `applicationId` → `com.vishal.callsage` + `app_name` → "CallSage". Internal package `com.baba.callvault` PEHLE SE KAAM KARTE CODE mein haath nahi maara.
**Kyun:** namespace/package rename se har file ke `R`/imports toot jaate — build ko pehle green rakhna priority. Full rename Phase 2 (clean commit, review ke saath).

## 4. License handling
CallVault GPL-3.0 + §7 hai → humara fork bhi GPL hoga + unhone attribution (kitsumed + CallVault authors) maintain ki hai. Personal use ke liye koi issue nahi. NOTICE.md/README credits delete NAHI honge.

## 5. Naam: CallSage (tentative)
Vishal ko naam decide karna hai — abhi default "CallSage" rakha hai (call + wisdom). Change ek string + applicationId se ho sakta hai, cost zero.

## 6. Distribution: Play Store NAHI
Call recording Play ki policy ke khilaf hai (CallVault apna README mein batata hai). Rasta: signed APK direct / F-Droid / Obtainium. Test: Vishal ka Android 16 phone pe side-load.

## 7. Next (abhi running)
SDK+NDK (27.2.12479018) + CMake install ho raha hai → pehla `assembleDebug` → string sweep + icon → Phase 2 rename → summary prompt customization (business use ke liye) → signed APK + Android 16 phone pe E2E.
