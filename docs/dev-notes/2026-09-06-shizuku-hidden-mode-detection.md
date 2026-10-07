# Shizuku in "hide" mode was reported as not installed

**🧪 VERIFYING — 2026-09-06.** Fixed, proven in the lab on the OP9 and on the emulator against a
genuinely renamed Shizuku. Not yet confirmed by the reporter on their own phone: that needs an APK in
their hands and one sentence back saying the Shizuku toggle is no longer greyed out.

## What was reported

A user runs Shizuku in a fork's **stealth mode** and CallVault says Shizuku is not installed.

## What stealth mode actually does

thedjchi's Shizuku fork ships a `[BETA] Stealth mode` that **renames the package**: it rebuilds its own
APK under a random package name (`moe.shizuku.privileged.api` + a random suffix), re-signs it with a
generated key, uninstalls itself and installs the clone. Its `ApkUtils.changePackageName` rewrites
exactly two things in the manifest — the `package` attribute and any provider authority that begins
with the old package name. Everything else, including

```xml
<permission android:name="moe.shizuku.manager.permission.API_V23" android:protectionLevel="dangerous"/>
```

is left untouched. The feature exists because banking and UPI apps refuse to run when they can see
Shizuku by name.

## Why we could not see it

`ShizukuBackend.isInstalled()` asked for `moe.shizuku.privileged.api` by name, and
`RecorderBackend.shizukuStatus()` asked *that question first*:

```kotlin
!ShizukuBackend.isInstalled(context) -> ShizukuStatus.NOT_INSTALLED   // won before anything else
```

`NOT_INSTALLED` greys the Shizuku toggle out entirely (`PrivilegedModeSubSection`), hides the offer
card (`PermissionsScreen`) and makes `ensureShizukuRunning` refuse to bind. The user could not turn the
mode on at all, while their Shizuku was running perfectly.

**The same bug hit Sui**, silently and for a different reason: Sui installs no manager app whatsoever,
so the package lookup fails there too. Every rooted Sui user was told Shizuku was not installed. The
comment in `ShizukuBackend` claiming "every variant ships `moe.shizuku.privileged.api`" was simply
wrong, and is now corrected.

## The fix

Two changes, both small:

1. **The binder is asked first.** `ShizukuStatus.of(isRunning, hasPermission, isInstalled)` — a running
   Shizuku outranks an absent package, so a package name that nobody answers to can only ever change
   the *wording* for a Shizuku that is not running. This alone fixes both the renamed case and Sui.
2. **The manager is resolved by the permission it declares**, not by a fixed name:
   `getPermissionInfo(ShizukuProvider.PERMISSION).packageName`. Permissions are a global namespace and
   are not subject to the package-visibility filtering that hides an app. Shizuku+'s own
   `af.shizuku.plus.permission.API_V23` is tried second; the stock package name survives only as a
   fallback for a ROM that refuses the lookup.

The same permission lookup is what Mihon, SD Maid SE (their PR #2541, filed for this exact complaint),
de1984 and **our own upstream ShizuCallRecorder** already ship.

The debug report now prints `Shizuku manager: <package>` in *both* modes, because the report that needs
it most comes from someone stuck in standalone precisely because we could not find their Shizuku.

## What was actually tested, and how

| Claim | Evidence |
|---|---|
| Permission lookup finds a stock Shizuku on real hardware | OP9 (LE2121, Shizuku 13.6.0), instrumented `ShizukuDetectionDeviceTest`: `permission moe.shizuku.manager.permission.API_V23 declared by: moe.shizuku.privileged.api` |
| A *renamed* Shizuku is found | Rebuilt the OP9's Shizuku APK as `moe.shizuku.privileged.api.a7f3k` (apktool + debug-key re-sign — the same two manifest edits stealth mode makes), installed it on the emulator with no stock Shizuku present: `managerPackage() resolved: moe.shizuku.privileged.api.a7f3k`, `stockNameInstalled=false` |
| A renamed Shizuku still hands us a binder | Emulator, cloned server running: `Service: send binder to user app com.baba.callvault in user 0`. `BinderSender` keys delivery off the *permission name*, never the manager's package name |
| Nothing else regressed | 1232 unit tests, 0 failures |

**One caveat on the clone.** Cloning *stock* Shizuku is not enough to make its server run: stock bakes
its manager package into `BuildConfig.MANAGER_APPLICATION_ID`, so the renamed server exited 50
(`MANAGER_APP_NOT_FOUND`) until a stub package with the original name was installed beside it. The fork
does not have this problem — its `ShizukuService` derives the manager package from the APK path in
`CLASSPATH` at runtime, which is exactly what makes stealth mode possible. So the clone reproduced the
*detection* state faithfully; the server-side half of the test needed a prop that a real user does not.

## What is not proven

The last step of a real user's flow — tapping Allow in the renamed Shizuku's own permission dialog —
was not exercised. It is keyed on the app's uid and has nothing to do with the package name, but it has
not been seen working end to end on a device.
