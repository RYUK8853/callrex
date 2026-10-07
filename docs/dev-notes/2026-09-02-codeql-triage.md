# CodeQL alerts — triage, 2026-09-02

Four alerts, all raised by the first CodeQL run on 2026-09-01 (`.github/workflows/codeql.yml`).
None is a regression from the 2.2.1 work; they are the backlog the scanner found when it was first
switched on.

Status key follows the project rule: nothing here is `✅ VERIFIED` until the maintainer confirms it.

---

## #17 — `java/relative-path-command`, medium — **FIXED 🧪 VERIFYING**

`RecorderServiceImpl.pidsMatching()` ran `ProcessBuilder("pgrep", …)` by bare name.

Worth fixing not because the attack is plausible but because of *where* it runs: this is the
privileged shell-uid daemon, so anything it execs runs at uid 2000. PATH there is inherited from
adbd, not chosen by us.

The clincher is that it was **inconsistent rather than considered** — the sibling exec sites already
spell out absolute paths (`/system/bin/pkill` in `RecorderSession`, `/system/bin/sh` in
`VoipAppIdentity` and `VoipCallerName`). This one had simply been written differently.

Fixed to `/system/bin/pgrep`. Path confirmed present on the OP12 (a symlink to toybox).

**Also fixed, same class, not flagged:** `PrivilegedGrants` ran `appops` and `cmd` by bare name.
Both now absolute; both paths confirmed on device.

**Deliberately NOT fixed:** `RecorderSession:108` launches `app_process` by bare name. Same class,
and `/system/bin/app_process` exists — but that line is on the scrcpy recording launch path, and the
rule here is that a working recording path is not disturbed for a theoretical gain. It needs a real
call to test, so it waits for a session that is testing recording anyway.

---

## #14, #15 — `java/android/implicit-pendingintents`, high — **FALSE POSITIVES, hardened anyway**

The rule fires on a PendingIntent that is *implicit* **and** *mutable*. Both flagged sites are
neither:

| Site | Component set? | Mutability |
|---|---|---|
| `VoipRecordingNotification:117` | yes — `DaemonKeepAliveService::class.java` | `FLAG_IMMUTABLE` |
| `SilentFailureNotifier:143` | yes — `MainActivity::class.java` | `FLAG_IMMUTABLE` |

So neither is exploitable as described, and the "high" severity is the rule's, not this code's.

What makes it worth a change rather than a dismissal: the whole codebase was audited for this while
triaging, and the audit is the useful artefact. **Every** PendingIntent in the app is immutable
except two, and both of those are explicit:

- `UpdateInstaller:172` — `FLAG_MUTABLE`, but `Intent(ACTION_INSTALL_STATUS).setPackage(packageName)`.
  Mutable because `PackageInstaller` writes the session result into it; package-scoped so only we
  can receive it. Correct as written.
- `AdbPairingService:308` — `FLAG_MUTABLE` with an explicit component, for a notification reply.

One genuinely implicit intent exists, at `UpdateNotifications:110` — `ACTION_VIEW` on the hard-coded
releases URL. CodeQL did not flag it, correctly: it is immutable, and opening a fixed https URL in a
browser chooser is the intent's whole purpose.

Both flagged sites now also call `setPackage(context.packageName)`. Redundant next to an explicit
component, but it is what an analyser can see without following the constructor, and it costs
nothing. If the alerts survive the next scan, dismiss them as false positives rather than
contorting the code further.

---

## #16 — `java/android/insecure-local-authentication`, medium — **ACCEPTED, will not fix**

`MainActivity.promptForUnlock()` uses `BiometricPrompt` and, on success, sets a boolean. CodeQL
wants the result bound to a `CryptoObject`, because a callback that only flips a flag can be
bypassed by anyone who can hook the process.

That is true, and it does not matter here, because **the app lock was never a confidentiality
boundary and does not claim to be**. Recordings live in a SAF folder the user picks — frequently on
shared storage, listed by any file manager, and often synced to Drive. The audio is not encrypted at
rest, so an attacker able to hook the app could simply read the files and never touch the prompt.

Binding the prompt to a keystore key would only be meaningful as part of encryption at rest, which
would trade away the things the folder-based design is *for*: playing a recording in any player,
syncing with Syncthing or Drive, and recovering recordings if the app is lost.

The promise is already correctly scoped in the UI, and this is the line to keep honest:

> *"Ask for your screen lock before showing recordings and transcripts. Also keeps them out of
> screenshots and the app switcher."*

That is a shoulder-surfing gate, and it is exactly what is delivered. **If that string is ever
reworded to imply the recordings themselves are protected, this alert stops being acceptable.**
