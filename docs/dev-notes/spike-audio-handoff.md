# Spike: Persistence Option B — Audio-Capture Handoff

**Branch:** `spike/audio-handoff` (off `main` @ v1.4.5). `main` is UNTOUCHED and released.
**Status:** M1–M2c ✅ · **M3a ✅** · **M3b-1/-2 ✅** · **M3d ✅ (refcount keep-alive)** · **M3b-3 ✅ (daemon→app binder handoff via manual symbol resolution + BinderDelivery)** · **M3c ✅ (track SURVIVES a real daemon kill — keep-alive PROVEN end-to-end on A16)** · **M3e ✅✅ OPTION B FULLY PROVEN** — after a real daemon kill the app keeps the track CAPTURING REAL audio (survives + drives the FIFO via w46 drain + not silenced). The whole chain works end-to-end on A16.
**Devices:** primary = OnePlus 12 / CPH2581 (`OP595DL1`), **Android 16 / SDK 36**, serial `6011b07e`. Secondary portability check = OnePlus 9 Pro / LE2121, **Android 14 / SDK 34**, serial `daabf34f`. Both `arm64-v8a`.
**OUTCOME: the spike concluded and SHIPPED.** Option B is now the "Resilient recording" opt-in (default OFF); the throwaway scaffolding has been removed and the surviving code lives in production packages — see [What shipped](#8-what-shipped).

This file stays as the research record: the milestones below describe probes that no longer exist in the tree, and the paths they cite are pre-productionisation.

> Living document — appended each session. Newest milestone last. Keep the [Scorecard](#scorecard) and [Resume checklist](#resume-checklist) current.

---

## 1. Why this spike exists

CallVault records calls via a **shell-uid (2000) `app_process` daemon** launched over embedded ADB. The daemon holds the privileged audio-capture permission the app process lacks. Two things kill the daemon:

1. **Screen-off / lock** restarts `adbd` on OnePlus/Xiaomi/Samsung → kills the shell daemon. *Mitigated* in v1.4.5 by the USB "Charging only" default (stops the USB gadget cycling). Universal, unfixable in-app otherwise (even Shizuku hits this).
2. **Idle reap** (~3–30 min). *Mitigated* by the keep-alive foreground service (validated ~30 min).

Both are mitigations, not cures. **Option B** is the ambition to make a recording *survive the daemon dying mid-call* — i.e. hand the live capture to the always-alive app process so killing the daemon doesn't stop it. If it works, it beats every non-root competitor (SCR, Shizuku-based) on mid-call resilience.

### The AudioFlinger theory (from AOSP `frameworks/av` review)

- A record track is torn down by the **RecordHandle binder REFCOUNT**, *not* a death-recipient. When the client's `sp<media::IAudioRecord>` (a `BpBinder` proxy) is destroyed, the binder driver notifies the server → refcount drops → track destroyed.
- The `CAPTURE_AUDIO_OUTPUT` / `VOICE_CALL` permission is checked **only at `createRecord`**, against the pinned shell uid. There is **no per-read check**.
- Reading audio = a **shared-memory FIFO** (`audio_track_cblk_t` + data in one ashmem region) — pure `mmap`, no permission.

**⇒ If the always-alive app co-holds the `IAudioRecord` binder (a second client ref), the shell dying won't destroy the track, and the app can keep reading the FIFO.** The #1 risk is **appop SILENCING** (zero-filling the track after the shell uid dies) — untested until we have a live track.

---

## 2. The core obstacle: linker namespaces

To hand off the `IAudioRecord` binder + cblk fd, *some* code we control must touch platform binder/audio objects (`libaudioclient`, `libbinder`). Android's `nativeloader` decides a library's linker namespace from the **classloader that loads it**, not the process uid. A normal-app APK → **isolated namespace** with no access to `/apex` platform libs. This is the wall the whole spike navigates.

---

## 3. Milestone log

### M1 — App process can't reach platform audio libs ✅ (commit `1feb9ac`)

Set up NDK `27.2.12479018` + CMake `3.22.1`, `externalNativeBuild`, a JNI `.so` (`libaudiohandoff.so`), and a probe called from `CallVaultApplication.onCreate` (the **app** process).

**Result:** the `.so` loads (toolchain OK) but the app process **cannot** dlopen platform libs:
```
dlopen FAIL: libaudioclient.so (library "libaudioclient.so" not found)   [namespace clns-9]
```
**Conclusion:** capture-creation cannot happen in the app process.

---

### M2 — JVM daemon `.so` is ALSO sealed ✅ (decisive)

Loaded the same `.so` into the **daemon** (`app_process`, uid 2000) by explicit path (`useLegacyPackaging=true` extracts it to `<apkDir>/lib/arm64/`, then `System.load(path)`), and probed there.

**Result — a *different*, decisive failure:**
```
Load libaudiohandoff.so using isolated ns clns-1
  (default_library_paths=/system/lib64:/system_ext/lib64, permitted_paths=/data:/mnt/expand)
dlopen FAIL: libaudioclient.so
  (libdl_android.so ... not accessible for the namespace "clns-1")
dlopen NOLOAD libandroid_runtime.so : not reachable
dlopen NOLOAD libbinder.so          : not reachable
dlsym RTLD_DEFAULT javaObjectForIBinder : null
dlsym RTLD_DEFAULT defaultServiceManager: null
```
The daemon **finds** `libaudioclient` in `/system/lib64` but can't resolve its `/apex` transitive dep. And the escape hatch (borrow framework libs `app_process` already loaded) is **closed** — `RTLD_NOLOAD`/`RTLD_DEFAULT` see nothing, because a `System.load`-ed lib is sealed in an isolated namespace regardless of uid or what's resident in the process.

**Conclusion:** the **vessel** (a `.so` loaded into the JVM via the app classloader) is the problem, not the privilege. The daemon *can* create captures via the **Java** `AudioRecord` (that's how recording works today — the *framework* loads `libaudioclient` in its own bootclasspath namespace) — we just can't reach those platform objects from our own loaded code.

---

### M2b — Standalone native executable IS the vessel ✅ (green light)

A raw ARM64 ELF, `exec`'d **directly** by the daemon (not loaded via the JVM classloader), gets the linker **default** namespace — like any `/system/bin` tool. Packaging trick: build an `add_executable` target but name it `libaudiohandoffprobe.so` so gradle ships it in `lib/arm64/` (extracted `0755` = executable).

**Result (uid 2000):**
```
dlopen OK  : libbinder.so
dlopen OK  : libaudioclient.so          ← all transitive deps resolved
dlopen OK  : libutils.so / libaudioclient_aidl_conversion.so / libpermission.so / libaudiofoundation.so
dlsym  OK  : IPCThreadState::self
VERDICT: standalone-exec default-namespace platform access = AVAILABLE (green light)
```
**Conclusion:** the standalone-exec vessel bypasses the namespace seal entirely. Option B is buildable through it.

---

### M2c — The namespace-restricted app CAN be the surviving reader ✅ (green light)

Validated the app-side read path in isolation. The **daemon** creates an ashmem FIFO (via public `ASharedMemory_create` — a public NDK lib, allowed even in the isolated app namespace) + a writer thread advancing a magic + frame counter, and hands the fd to the **app** over the existing `IRecorderService` binder as a `ParcelFileDescriptor` (new spike AIDL `spikeStartFifoProbe(int)`). The app `mmap`s and reads it natively.

**Result — two separate processes, live:**
```
[daemon uid 2000] nativeCreateFifo: fd=63 size=8192 writer started
[app   untrusted] m2c APP-SIDE FIFO READ: magic=0xCA11F1F0 (MATCH) w0=1 w1=30 advanced=YES dataByte0=30
```
The app saw the writer's counter advance `1 → 30` over 300 ms, using only libc `mmap` + atomics (no `libaudioclient`). fd delivery over binder is SELinux-safe (no raw cross-uid socket).

**Conclusion:** the app can survive as the reader of an ashmem cblk. The entire app-side crux is proven. Only the binder-refcount keep-alive + silencing remain (need a real track).

---

### M3a — Native `android::AudioRecord` in the helper 🟡 (in progress, ~80%; commit `8cb1b18`)

The helper (`libaudiohandoffprobe.so capture mic`) creates a real `android::AudioRecord` by dlsym'ing libaudioclient's **mangled C++ symbols** (pulled with `llvm-nm` from the device's `/system/lib64/libaudioclient.so`).

**Proven working:**
- ✅ **ABI binding correct** — `set()` logs back the exact 18 params I passed; no crashes.
- ✅ **`ProcessState::self()` via the x8 struct-return trick** — returns a valid pointer. (An `sp<>` is returned in memory via x8 on AAPCS64; modelled with a non-trivially-copyable 1-pointer struct so the compiler uses x8.)
- ✅ Over-allocated object (`calloc(8192)`) + placement ctor; `getMinFrameCount`=640 → frameCount 1280.
- ✅ **Permission works** — uid 2000 gets *past* the audio policy `getInputForAttr` (no `PERMISSION_DENIED`), reaching `AudioFlinger::createRecord`.

**Blocker:** `createRecord` returns **`-22` (BAD_VALUE)**:
```
set(): inputSource 1, sampleRate 16000, format 0x1, channelMask 0x10, frameCount 1280, transferType 3,
       attributionSource AttributionSourceState{pid: N, uid: 2000, deviceId: 0, packageName: (null),
       attributionTag: (null), token: (null), ...}
createRecord_l(0): AudioFlinger could not create record track, status: -22
```
The attribution has `packageName=(null)` + `token=(null)`. The Java path always supplies them (AppOps needs the package to attribute `RECORD_AUDIO` to uid 2000).

**Why hand-writing the attribution failed:** `AttributionSourceState` is a polymorphic `Parcelable` (vtable @ 0). Setting fields by byte offset works for `pid`/`uid` (ints) but is fragile for `std::optional<std::string>` / `sp<IBinder>`. A test write of `packageName` at a guessed offset landed in **`deviceId`** instead — proving the real memory field order is `pid, uid, deviceId, packageName, …` (= toString order, NOT the AIDL declaration order). See the evidence: `deviceId: 1836016418` = `0x6D6F632E` = ASCII bytes of the package string.

**Fix CODED + COMPILED offline (commit `5782d23`), not yet run on device:** built the `AttributionSourceState` via a **`Parcel` round-trip** in `buildAttribution()`. The AIDL **wire format order was derived by disassembling the device's own `writeToParcel`** (`llvm-objdump` on the pulled `libpermission.so`):
```
int32 parcelable_size (patched) · int32 pid · int32 uid · int32 deviceId ·
writeUtf8AsUtf16 packageName · writeUtf8AsUtf16 attributionTag ·
writeStrongBinder token · writeUtf8VectorAsUtf16Vector renouncedPermissions · <array> next
```
The helper writes that exact layout with low-level `Parcel` methods (so it never constructs a platform `optional<std::string>`): `writeInt32` for the ints; `packageName` as a raw String16 wire (`writeInt32(len)` + `Parcel::write` of `(len+1)` UTF-16 units); `attributionTag`/`renouncedPermissions` as `writeInt32(-1)` (null); `token` via `writeStrongBinder(&null)`; `next` as `writeInt32(0)` (empty). Then it patches `parcelable_size`, rewinds, and calls the exported virtual `readFromParcel` on the vtable-initialised object → **the platform's own libc++ constructs the members correctly, zero layout guessing.** Sets `packageName="com.android.shell"`, `uid=2000`; **token still null this pass**.

All Parcel/readFromParcel symbols verified present in the device binaries (see table). Compiles + packages clean.

**Validated on a 2nd device (OnePlus 9 Pro, Android 14 / SDK 34) via `adb shell` push+run** — see M3a-portability below. The Parcel round-trip WORKS (packageName + token now correctly populate), but `createRecord` **still returns `-22` with a COMPLETE attribution** → the `-22` is NOT attribution completeness; the cause is elsewhere in the `createRecord` request.

**⏭️ NEXT SESSION (main device, Android 16 / OnePlus 12):** diff my native `createRecord` request against the **working Java `DirectAudioRecorderSession`** (log the Java path's attribution + params, or capture what the framework passes to native `set()`), to find what differs and causes `-22`. Also test `capture voicecall` (the real target) during an active call.

---

### M3a-portability — cross-check on Android 14 (OnePlus 9 Pro, SDK 34)

Tested the native capture on a second device with **`adb shell` push+run** — no app/daemon/pairing needed, because `adb shell` already runs as uid 2000 (shell) in the default namespace, the exact daemon context:
```bash
unzip -o -j app/build/outputs/apk/release/*.apk lib/arm64-v8a/libaudiohandoffprobe.so -d /tmp/
adb push /tmp/libaudiohandoffprobe.so /data/local/tmp/cvprobe && adb shell chmod 755 /data/local/tmp/cvprobe
adb shell /data/local/tmp/cvprobe capture mic        # stdout shows the CV log lines directly
```
This is the **preferred M3a test harness** going forward (simpler than the app/daemon path).

**Findings (all valuable, portability-relevant):**
1. **Symbols identical across Android 14 & 16** — every AudioRecord/Parcel/readFromParcel/ProcessState symbol resolved with the same mangling. The native approach is portable at the symbol level.
2. **`AttributionSourceState` wire format is version-specific.** Android **15+ (API 35)** inserts an `int32 deviceId` after `uid`; **Android 14 has no such field**. My Android-16 wire format broke A14's `readFromParcel` (`status=0x80000008`). Fixed: `buildAttribution(withDeviceId)` — picks by SDK (`>=35`) and **retries the other variant** if `readFromParcel` fails, so it auto-adapts to any version. On A14 it selects no-`deviceId` and `readFromParcel` returns 0. *(Derived A14's order by disassembling its `writeToParcel`: `size,pid,uid,packageName,attributionTag,token,renouncedPermissions,next`.)*
3. **Parcel round-trip PROVEN on-device** — `packageName` correctly reads back as `com.android.shell` (couldn't verify offline last session).
4. **Added a local `BBinder` token** (a null token was the suspect). No refcount gymnastics: `readStrongBinder` re-wraps it into an `sp` (incStrong), pinning it via the attribution. `BBinder : public IBinder` → `BBinder* == IBinder*` (first non-virtual base). Token now populates: `token: binder:0x...`.
5. **`createRecord` STILL returns `-22` with a COMPLETE attribution** (uid=2000 + `com.android.shell` + valid token). ⇒ **the `-22` is not attribution completeness.** AudioFlinger verbose logging was drowned by OnePlus `EffectDapController` spam; the server-side reason didn't surface. Deferred to the main device to diff against the working Java path.

**Guidance honoured:** stopped here rather than deep-debugging `-22` on the non-primary device (it's not Android-14-specific — it also `-22`'d on A16 — so it's a main-path issue best cracked where the working Java reference lives).

---

### M3a — SOLVED ✅ (native AudioRecord captures real PCM; OnePlus 9 Pro / Android 14)

Root cause of `createRecord=-22`: **`AUDIO_SESSION_ALLOCATE` was hardcoded as `-1`, but it is `0`.** `-1` is `AUDIO_SESSION_OUTPUT_STAGE` — an invalid session for a record track, so `createRecord` rejected it. It was constant across every sweep config, which is why *all* of them failed identically.

**How it was found — the Java-reference diff (reusable technique).** The native `AudioRecord::set()` logs its full request under tag `AudioRecord` *regardless of caller*. So a **working shell-uid Java `AudioRecord`** run via `app_process` produces a directly-comparable `set():` line — the ground truth. Build + run:
```bash
# scratchpad/javaref/Ref.java (saved in repo at docs/dev-notes/spike-tools/Ref.java)
javac -cp $ANDROID_HOME/platforms/android-34/android.jar -d classes Ref.java
$ANDROID_HOME/build-tools/34.0.0/d8 --lib .../android.jar --output cvref.jar classes/cvref/Ref.class
adb push cvref.jar /data/local/tmp/cvref.jar
adb shell "CLASSPATH=/data/local/tmp/cvref.jar app_process /data/local/tmp cvref.Ref"
adb logcat -d | grep -E "CVRef|AudioRecord: set\(\)"
```
The Java ref **worked** as shell (uid 2000) on Android 14: `getState=INITIALIZED`, `RECORDING`, `CAPTURE peak=5572 REAL` — proving shell *can* record here and isolating the `-22` to the raw-native request.

**The diff table (the ground truth for any device):**

| Variable (native `set()`) | Java ✅ works | native ❌ `-22` | native ✅ fixed | Notes |
|---|---|---|---|---|
| inputSource | 1 (MIC) | 1 | 1 | same |
| sampleRate | 16000 | 16000 | 16000 | same |
| format | 0x1 (PCM_16) | 0x1 | 0x1 | same |
| channelMask | 0x10 (IN_MONO) | 0x10 | 0x10 | same |
| frameCount | 2048 | 1280 | 1280 | both ≥ min(640); not the cause |
| notificationFrames | 0 | 0 | 0 | same |
| **sessionId** | **0** | **-1** ❌ | **0** ✅ | **THE BUG.** `AUDIO_SESSION_ALLOCATE=0`, not -1 |
| transferType | 0 (DEFAULT) | 3 (SYNC) | 3 | 3 is fine (SYNC = read-based); not the cause |
| flags | 0 | 0 | 0 | same |
| attribution uid / pkg / token | 2000 / com.android.shell / binder | identical | identical | attribution was never the issue |

**Result after fix (native, adb shell push+run):** `set()=0`, `start()=0`, `CAPTURE 51200 bytes peak=14033 REAL`. **Native privileged capture in the helper works.**

**Constants double-checked (stable across versions — reuse on the main device as-is):** `AUDIO_SOURCE_MIC=1`, `AUDIO_SOURCE_VOICE_CALL=4`, `AUDIO_FORMAT_PCM_16_BIT=0x1`, `AUDIO_CHANNEL_IN_MONO=0x10`, `AUDIO_CHANNEL_IN_STEREO=0xc`, **`AUDIO_SESSION_ALLOCATE=0`**, `TRANSFER_SYNC=3`, `AUDIO_INPUT_FLAG_NONE=0`, `AUDIO_UID_INVALID=(uint32)-1`.

**For the main device (OnePlus 12 / Android 16) — should be zero-friction:** the session fix is a universal constant, symbols are identical (verified), and the attribution is version-adaptive. Just `adb shell /data/local/tmp/cvprobe capture mic` → expect `capture=WORKS audio=REAL`. If anything differs, re-run the Java ref (`cvref.jar`) and diff the `set():` line the same way. Then test `capture voicecall` during a live call (source=4, same path).

---

### M3b — the handoff (in progress)

**M3b-1 ✅ (OnePlus 9 Pro / Android 14): extract the cblk fd offset-free + prove a 2nd mapping reads live audio.**
After the native `AudioRecord` starts, the cblk ashmem is mapped and its fd is open in-process — so it's discoverable **without any member offsets** by scanning `/proc/self/fd` + `/proc/self/maps`:
- `/proc/self/fd`: the cblk fd shows as `fd N -> /dev/ashmem<uuid>` (`fstat` size reads 0 for ashmem — use `ioctl(fd, ASHMEM_GET_SIZE=0x7704)` → **8192**).
- `/proc/self/maps`: the region is `rw-s … /dev/ashmem/MemoryHeapBase (deleted)`, size `0x2000` (8192).

Independently `mmap`-ing that fd (PROT_READ, MAP_SHARED — what the app will do) sees the **live** cblk: control words advance (`word[0] 0→1280`, etc. = `audio_track_cblk_t` positions) and **real PCM is present** (`peak=31742`). **The `audio_track_cblk_t` header + the audio ring buffer share the SAME 8192-byte ashmem** → one fd carries everything.

⇒ Combined with **M2c** (passing an ashmem fd to the app over the daemon binder as a `ParcelFileDescriptor`), the **cross-process cblk read is effectively solved**: helper discovers the cblk fd via `/proc/self/fd` → hands it to the app (PFD or `SCM_RIGHTS`) → app `mmap`s + reads. *(Proper in-order FIFO extraction — driving front/rear via the `AudioRecordClientProxy` obtainBuffer/releaseBuffer protocol — is a productionization detail; the spike only needs "2nd mapping sees live audio", which is proven.)*

**M3b-2 ⏭️ (the keep-alive crux — the big remaining chunk): extract + transfer the `IAudioRecord` binder.** The cblk fd does NOT keep the track alive — the AudioFlinger RecordTrack lifetime is tied to the **`IAudioRecord` binder refcount** (`mAudioRecord`, a private `sp<media::IAudioRecord>` in `android::AudioRecord`). The always-alive app must hold a ref on it (a shell-uid helper can't survive screen-lock/idle-reap — only the app process does).

**✅ Binder LOCATED (commit `a8d4e1e`, Android 14) — the SIGSEGV-guarded scan works.** `mAudioRecord` is at **`ar+0x190`** → the `BpAudioRecord`; its `BpRefBase::mRemote` (the `IAudioRecord` `BpBinder`) is at **`+0x10`** (a secondary binder is at `+0x50` — a cached/death ref). This matches the `0x190` offset from the `stop()` disasm. Two techniques were required and are **reusable for any binder extraction**:
- **Empirical BpBinder vptr** (don't guess the vtable address point). `BpBinder : IBinder : virtual RefBase` has virtual-base-offset entries before the address point, so `symbol+16` is WRONG. Instead get a real BpBinder at runtime — `ProcessState::self()->getContextObject(null)` (servicemanager proxy) — and read its first word as the reference vptr (`_ZN7android12ProcessState16getContextObjectERKNS_2spINS_7IBinderEEE`, returns `sp<IBinder>` via x8).
- **Allow scudo-tagged pointers.** Android tags heap pointers in the top byte (e.g. `0xb4…`); the CPU ignores it on deref (TBI). Range-check the **untagged** address (`v & 0x00FFFFFFFFFFFFFF`), else every real pointer is rejected.

Guard the scan with a `sigsetjmp`/SIGSEGV+SIGBUS handler. Safe to run single-threaded: `TRANSFER_SYNC` + null callback means **no** concurrent `AudioRecordThread`.

**Alternative (cleaner binder, heavier marshalling):** call **`AudioFlinger::createRecord` directly** via AIDL — the `CreateRecordResponse` has `.audioRecord` (IAudioRecord) + `.cblk` (SharedFileRegion→fd) + `.buffers`, bypassing the `AudioRecord` helper. Reuses the Parcel machinery already built for the attribution. Consider this if the member offset (`ar+0x190`/`+0x10`) proves unstable across devices — but the offset-free scan should re-derive it per device anyway.

**Transfer:** hand the binder to the app via a binder transaction (the app hosts a receiver AIDL and survives; the helper `writeStrongBinder`s the IAudioRecord to it), or register it with servicemanager. The app then holds the ref (keep-alive) + mmaps the cblk fd (M3b-1) + drives the FIFO read.

**M3c** = kill the creator (daemon+helper) → confirm the RecordTrack survives (cblk `server` pos keeps advancing = refcount theory holds) + audio stays REAL, not zero-filled (appop **silencing** — risk #1). MIC first, then VOICE_CALL.

**⏭️ M3b-3 + M3c — the final phase (needs app integration, can't be done in the `adb shell` harness).** All extraction is proven; what remains is transferring the binder to a *surviving* process and the kill/survive test. Two real binder endpoints are required — shell likely can't `addService` arbitrary names, and `fork()` in a binder process is unsafe. Plan in the real architecture:
1. Wire the capture helper into the daemon (daemon `exec`s it; or fold the native code into the daemon path). Helper extracts cblk fd (`/proc/self/fd`) + `IAudioRecord` binder (`ar+0x190`→`+0x10`).
2. Helper/daemon hands both to the **app** over the existing `IRecorderService` binder: cblk fd as a `ParcelFileDescriptor` (proven in m2c); the `IAudioRecord` `IBinder` via a new spike AIDL method (`writeStrongBinder` — the app is a real binder endpoint).
3. App holds the `IBinder` (keep-alive ref) + `mmap`s the cblk fd + drives the FIFO read (port `AudioRecordClientProxy` obtainBuffer/releaseBuffer, ~50 lines).
4. **Kill the daemon + helper.** Observe in the app: does the cblk `server` position keep advancing (track alive → refcount theory holds)? Is the audio REAL or ZERO-filled (silencing)? MIC first, then VOICE_CALL during a live call.
This is the decisive Option-B validation; do it on the **main device (A16/OnePlus 12)** where the daemon/app/pairing already exist.

**M3c-fork — a preliminary kill-and-observe on OP9 (commit `4ba5f49`).** Since a real cross-process transfer needs the app, I did the tractable approximation: `fork()`, parent captures ~3s then `_exit`s with **no `stop()`/dtor** (real-kill sim), child (inheriting the cblk mmap + binder fd) watches the cblk.
- **Result:** the cumulative frame counter (`cblk w0`/`w18`/`w20`) advances while the parent lives (`1280→19200→35200`), then **FREEZES ~1s after the parent dies** (`48640`, stays frozen), and a flag word `w2` flips `0→1` at death. `peak` stays high only because that's **stale** ring audio (no new frames).
- **This does NOT disprove Option B.** `fork()` is not a valid keep-alive vector — the child shares the parent's `binder_proc` (whose binder buffer is tied to the dead parent's `mm`), so it never held a genuine independent driver ref. A proper transfer gives the **app its own `binder_proc` ref**, which fork can't replicate.
- **Value:** (1) a clean **liveness monitor** for the real test — watch `cblk w0`/`w18`/`w20` (cumulative captured frames); alive ⇒ advancing, dead/stopped ⇒ frozen. (2) It sharpens the decisive question: is the freeze from the **refcount dropping** (a proper app ref prevents it → Option B works) or the **framework stopping the track on client-death** (kills Option B regardless of refs)? Only the app transfer distinguishes these — that's THE thing to determine on the main device.
- Note: shell can't toggle appops (`appops set` needs `MANAGE_APP_OPS_MODES`), so the appop-silencing *proxy* test isn't runnable as shell; silencing must be observed via the real kill on the main device.

### M3d — refcount keep-alive CONFIRMED ✅ (in-process, ping-based; OP12/A16, commit `4f8e309`)

The decisive mechanism question — *does an independent ref keep the track alive after the creator's client ref goes away?* — answered **YES** in-process, cleanly:
- Create `AudioRecord` + start. Take an **independent** strong ref on the `IAudioRecord` via a `Parcel` `writeStrongBinder`→`readStrongBinder` round-trip (adds a real local strong ref; no vbase math for my ref).
- **Release the `AudioRecord`'s own client ref** by destroying its `BpAudioRecord` — `RefBase::decStrong` on it, located via the **vbase offset** (`vptr-24` → `vbase=40` → `RefBase*`, validated by `mRefs` being a plausible pointer). This drops the client ref **without** an explicit `stop()`.
- **Liveness via a REAL transaction:** `BpBinder::pingBinder()` (`_ZN7android8BpBinder10pingBinderEv`) to the `RecordHandle` returns **`0` (alive)** for the full 6s afterward, held only by the independent ref. (`isBinderAlive()` agreed, but it's a cached flag — the ping is the trustworthy signal.)

⇒ **Track lifetime = `IAudioRecord` binder refcount, PROVEN on the real device.** Releasing the client ref does not tear down the track while another ref is held. This is a **major de-risk**: a proper app-held ref should keep the track alive when the daemon drops its ref on death.

**Corrects the M3c-fork reading:** its `w0` freeze meant *the reader (parent) died* (no one draining the FIFO), NOT that the track died — `w0` is a read-driven position. The ping-based signal supersedes it.

**The ONE remaining risk (still needs real cross-process death):** does the **appop `finishOp` on the creating uid/pid's DEATH** silence/stop the track even with a ref held? The refcount teardown — the likeliest killer — does *not* fire on ref release, so this is now the sole open question, and it's answered only by the app-transfer + real-kill test.

New reusable symbols (A16): `RefBase::inc/decStrong` (libutils) `_ZNK7android7RefBase9inc/decStrongEPKv`; `BpBinder::pingBinder` `_ZN7android8BpBinder10pingBinderEv`, `isBinderAlive` `_ZNK7android8BpBinder13isBinderAliveEv`; `Parcel::readStrongBinder` `_ZNK7android6Parcel16readStrongBinderEPNS_2spINS_7IBinderEEE`. Technique: locate a virtual-`RefBase` subobject via the vtable vbase-offset slot at `vptr-24`.

*(Note: USB "Charging only" already prevents the screen-lock adbd-kill in prod (v1.4.5); Option B targets the idle-reap case + general resilience, where only the app reliably survives — hence the binder must live in the app.)*

### M3b-3 + M3c — APP INTEGRATION: track SURVIVES daemon death ✅ (OP12/A16, commits `716f3f4`,`a1b7f42`,`b2b1334`)

The full cross-process handoff, built on the real app/daemon:

1. **Manual symbol resolution** (`audiohandoff.cpp` `manualResolve`): the daemon's `.so` is in the isolated classloader namespace (can't dlsym `libandroid_runtime`), so compute a function's runtime address = `/proc/self/maps` load base + on-disk ELF `.dynsym` `st_value`. Resolved `javaObjectForIBinder` @ `base+0x1faa70`. ✅
2. **Daemon extracts + wraps** (`HandoffSpike` + `nativeExtractBinder`): create a Java `AudioRecord(MIC)` (framework loads `libaudioclient` in ITS namespace), find the native `android::AudioRecord*` in the Java object's `mNativeAudioRecordHandle` long field (auto-validated via `nativeValidateArPtr`), read the `IAudioRecord` `BpBinder` at `ar+0x190→+0x10`, and wrap it into a Java `IBinder` (BinderProxy) via the manually-resolved `javaObjectForIBinder`. `pingBinder()`=true → it's the real live RecordHandle. ✅
3. **Deliver to the app** over the EXISTING `BinderDelivery` (new `sendHandoff` method + `RecorderBinderProvider` handler): the `IAudioRecord` (in a `BinderContainer`) + the cblk fd (as a `ParcelFileDescriptor`). The app (`HandoffReceiver`) holds the `IBinder` in ITS OWN `binder_proc` — a proper independent ref. Trigger: app calls `IRecorderService.spikeStartHandoff()`.
4. **KILL the daemon** (`pkill app_process RecorderServer`).

**RESULT (decisive):**
```
t=6s  ping(alive)=true
CV:RecorderConn: Daemon binder died; clearing RecorderConnection   ← daemon process confirmed dead
t=7s..t=34s  ping(alive)=true   ← IAudioRecord RecordHandle STILL ALIVE
```
⇒ **The privileged capture track SURVIVES the creating daemon's death, held alive by the app's independent ref. Option B's keep-alive is PROVEN end-to-end on the real device.** (Matches M3d's in-process refcount result, now demonstrated cross-process with a real kill.)

**Silencing / active-capture — STILL OPEN, and harder than expected.** RE'd the record cblk via the probe read loop @16kHz: `w[0]`/`w[18]`/`w[20]` = frame position (advance 16000/sec while reading; `w[0]` = server "rear" cumulative frames), `w[2]=1`/`w[3]=320`/`w[4]=0xE0006000` = const flags, `w[15]` = slow poll counter, `w[28..31]` = a ns timestamp. **But a memory-only drain does NOT sustain capture:** in the `CV_DRAIN` test (read 2s to keep the track active, then stop `AudioRecord::read` and manually advance `w18=w20=rear`), `w[0]` FREEZES (buffer full at `mFront+frameCount`) and the ring peak goes stale — the server stopped capturing. Advancing `w18`/`w20` doesn't restart it: either they aren't the effective `mFront`, or (likely) **the RecordThread STANDBYs once no *active* (libaudioclient) client is reading**, and a raw memory poke doesn't register as active.

⇒ *(Initial worry — RESOLVED below.)* The "standby" read was a **red herring**: it was the wrong `mFront` word.

### M3e — SOLVED: OPTION B FULLY PROVEN ✅✅✅ (OP12/A16, commits `e45b207`,`ca4556e`)

**Found the real `mFront`.** Diffing 64 cblk words, reading vs no-read: `w0`/`w18`/`w20`/`w47` = server "rear"; **`w46` = client `mFront`** (0 when no reader, advances with reads). `w46`/`w47` are the `AudioTrackSharedStreaming` `mFront`/`mRear` pair @ offset 184/188.

**Memory drain sustains capture.** Advancing `w46=w47` every 40ms — even with **no `AudioRecord::read` ever** (pure `mmap`, no `libaudioclient`) — keeps `rear(w0)` climbing 16000/sec with real audio. **NOT standby.** The namespace-restricted app can drive the FIFO itself.

**THE COMPLETE TEST (app drives the FIFO across a real daemon kill):**
```
t=1..7s   rear(w0) +16000/sec  ringPeak~1400-1900  CAPTURING REAL
[t~7s]    Daemon binder died         ← daemon process killed
t=8..17s  rear(w0) +16000/sec  ringPeak~1525-2051  CAPTURING REAL   (daemon DEAD)
```
After the daemon dies, the app **keeps capturing new frames** (`rear` advancing) with **real audio** (`ringPeak` non-zero, varying — not zeros). **No silencing.** The appop `finishOp` on the creator's death did NOT zero-fill the track.

⇒ **ALL THREE remaining questions = YES: keep-alive · active-capture-from-the-app · no-silencing. Option B is fully viable on the real device** — a recording continues, with real audio, after the privileged daemon is killed, held + read entirely by the always-alive app. App-side read = pure cblk `mmap` + `w46`→`w47` drain (`nativeMonitorCblk`); this is also the productionization read path.

**New reusable pieces:** `manualResolve` (ELF `.dynsym` runtime resolution — bypasses nativeloader), `nativeExtractBinder`/`nativeValidateArPtr` (Java-AudioRecord → IAudioRecord Java IBinder), method-parametrized `BinderDelivery` + `sendHandoff`. Java-AudioRecord native ptr field on A16 = `mNativeAudioRecordHandle`.

## 4. Reference

### Verified symbols (Android 16 / SDK 36, `libaudioclient.so` unless noted)

| Purpose | Mangled symbol |
|---|---|
| AudioRecord ctor (AttributionSource) | `_ZN7android11AudioRecordC1ERKNS_7content22AttributionSourceStateE` |
| AudioRecord::set (18 args) | `_ZN7android11AudioRecord3setE14audio_source_tj14audio_format_t20audio_channel_mask_tmRKNS_2wpINS0_20IAudioRecordCallbackEEEjb15audio_session_tNS0_13transfer_typeE19audio_input_flags_tjiPK18audio_attributes_ti28audio_microphone_direction_tfi` |
| AudioRecord::start | `_ZN7android11AudioRecord5startENS_11AudioSystem12sync_event_tE15audio_session_t` |
| AudioRecord::read | `_ZN7android11AudioRecord4readEPvmb` |
| AudioRecord::stop | `_ZN7android11AudioRecord4stopEv` |
| AudioRecord::getMinFrameCount (static) | `_ZN7android11AudioRecord16getMinFrameCountEPmj14audio_format_t20audio_channel_mask_t` |
| ProcessState::self (libbinder) | `_ZN7android12ProcessState4selfEv` |
| ProcessState::startThreadPool (libbinder) | `_ZN7android12ProcessState15startThreadPoolEv` |
| AttributionSourceState vtable (libpermission) | `_ZTVN7android7content22AttributionSourceStateE` (vptr = symbol + 16) |
| AttributionSourceState::readFromParcel (libpermission) | `_ZN7android7content22AttributionSourceState14readFromParcelEPKNS_6ParcelE` |
| AttributionSourceState::writeToParcel (libpermission) | `_ZNK7android7content22AttributionSourceState13writeToParcelEPNS_6ParcelE` |
| Parcel ctor (libbinder) | `_ZN7android6ParcelC1Ev` |
| Parcel::writeInt32 (libbinder) | `_ZN7android6Parcel10writeInt32Ei` |
| Parcel::write(void*,size_t) (libbinder) | `_ZN7android6Parcel5writeEPKvm` |
| Parcel::writeStrongBinder (libbinder) | `_ZN7android6Parcel17writeStrongBinderERKNS_2spINS_7IBinderEEE` |
| Parcel::dataPosition (libbinder) | `_ZNK7android6Parcel12dataPositionEv` |
| Parcel::setDataPosition (libbinder) | `_ZNK7android6Parcel15setDataPositionEm` |
| BBinder ctor — for a token (libbinder) | `_ZN7android7BBinderC1Ev` |
| RefBase::incStrong — hold the token (libbinder) | `_ZN7android7RefBase9incStrongEPKv` |
| BpBinder vtable — find IAudioRecord in the object (libbinder) | `_ZTVN7android8BpBinderE` (vptr = symbol + 16) |

M3b-2 note: `media::BpAudioRecord` vtable is NOT exported (can't scan for it); scan for the inner `BpBinder` instead. `ASHMEM_GET_SIZE` ioctl = `0x7704`.

Note: `Parcel::writeString16(const char16_t*, size_t)` is NOT exported → the String16 wire format is reproduced manually with `writeInt32(len)` + raw `Parcel::write`.

- `initCheck()` and `getCblk()` are **inline** (no exported symbol). Gate health on `set()`/`start()` return codes. For M3b, read `mCblk` via a member offset.
- Get symbols: `~/Library/Android/sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/*/bin/llvm-nm -D --defined-only <lib>`.

### `AttributionSourceState` memory layout (empirical, Android 16)

```
offset 0  : vtable ptr        (Parcelable is polymorphic)
offset 8  : pid   (int32)
offset 12 : uid   (int32)
offset 16 : deviceId (int32)
offset ~24: packageName      std::optional<std::string>
   then     attributionTag   std::optional<std::string>
            token            sp<IBinder>
            renouncedPermissions
            next             std::vector<AttributionSourceState>
```
**Do not** rely on this for writes — use the Parcel round-trip. Layout is here only to interpret logs.

### Key parameter values used (MIC path)

`source=AUDIO_SOURCE_MIC(1)`, `sampleRate=16000`, `format=AUDIO_FORMAT_PCM_16_BIT(0x1)`, `channelMask=AUDIO_CHANNEL_IN_MONO(0x10)`, `frameCount=minFrames*2`, `callback=null wp`, `sessionId=AUDIO_SESSION_ALLOCATE(-1)`, `transferType=TRANSFER_SYNC(3)`, `flags=AUDIO_INPUT_FLAG_NONE(0)`, `uid=pid=-1` (defer to attribution). Switch `source` to `AUDIO_SOURCE_VOICE_CALL(4)` after MIC mechanics work (needs an active call).

### Files (as they were DURING the spike — see [What shipped](#8-what-shipped) for where this code lives now)

| File | Role |
|---|---|
| `app/src/main/cpp/CMakeLists.txt` | builds `libaudiohandoff.so` (JNI) + `libaudiohandoffprobe.so` (executable) |
| `app/src/main/cpp/audiohandoff.cpp` | app/daemon JNI probe (m1/m2) + m2c ashmem FIFO create/read (`ASharedMemory`) |
| `app/src/main/cpp/audiohandoffprobe.cpp` | standalone exec: m2b reachability probe + **m3a native capture** (`capture mic`) |
| `app/src/main/java/.../spike/AudioHandoffNative.kt` | JNI bridge (`loadFromPath`, `nativeProbe`, `nativeCreateFifo`, `nativeReadFifo`) |
| `app/src/main/java/.../server/RecorderServer.kt` | daemon: runs the m2 probe + m2c FIFO create (AIDL) + **execs the m3a capture helper** |
| `app/src/main/java/.../CallVaultApplication.kt` | app: m1 probe + m2c FIFO app-side read |
| `app/src/main/aidl/.../IRecorderService.aidl` | spike method `spikeStartFifoProbe(int)` |
| `app/build.gradle.kts` | `ndkVersion`, `abiFilters arm64-v8a`, `externalNativeBuild`, `packaging{ jniLibs{ useLegacyPackaging=true } }` |

### Build / install / test recipe

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=~/Library/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cd ~/Desktop/Projects/callrecorder
./gradlew :app:assembleRelease -PversionName=1.4.5-spike -PversionCode=<N>   # N > last used (10452)
adb install -r app/build/outputs/apk/release/*.apk
adb shell pm grant com.baba.callvault android.permission.WRITE_SECURE_SETTINGS   # dropped on install-over
adb logcat -c
adb shell am force-stop com.baba.callvault
adb shell monkey -p com.baba.callvault -c android.intent.category.LAUNCHER 1
sleep 16
adb logcat -d | grep -iE "AudioHandoffExe|AudioRecord:|createRecord|m3a|m2c" | grep -v "child:"
```
- **SAME release keystore** (cert `c875ffd0`) so it installs over v1.4.5 — never uninstall (wipes ADB pairing).
- Keep USB **"File transfer"** for dev (charging-only drops USB adb).
- The daemon warms at app launch; the capture helper is exec'd from `RecorderServer.main`.

### Diagnostics baked in (per device-specific concern)

The helper logs `ENV[phase] release/sdk/model/brand/device/abi` + every resolved symbol pointer + `set()`/`start()` status on every run (tag `CV:AudioHandoffExe`). Since this native path is Android-version/vendor-specific, **fold these same fields into the production debug report** once capture works, so user reports pinpoint an ABI break on other devices/versions.

---

## 5. Scorecard

| Milestone | Question | Result |
|---|---|---|
| M1 | App process dlopen platform libs? | ❌ namespace-sealed (`clns-9`) |
| M2 | JVM daemon `.so` dlopen platform libs? | ❌ same seal (`clns-1`), decisive |
| M2b | Standalone exec (uid 2000) dlopen `libaudioclient`+`libbinder`? | ✅ green light |
| M2c | Namespace-restricted app read a cross-process ashmem FIFO natively? | ✅ green light |
| M3a | Helper creates a native `AudioRecord` + reads real PCM? | ✅ **DONE** — `set()=0`, `start()=0`, 51200 bytes captured, peak 14033 REAL (Android 14). Bug was `AUDIO_SESSION_ALLOCATE` (0, not -1) |
| M3a-portability | Symbols/approach portable across Android versions? | ✅ symbols identical A14↔A16; attribution wire format version-specific (deviceId A15+) but auto-adapted; session fix is universal |
| M3b-1 | Extract cblk fd + prove a 2nd mapping reads live audio | ✅ **DONE** — cblk fd found offset-free via `/proc/self/fd`; 2nd mmap sees advancing words + peak 31742 REAL |
| M3b-2 | Locate the `IAudioRecord` binder in the object | ✅ **DONE** — `mAudioRecord`@`ar+0x190` → `BpBinder`@`+0x10` (offset-free scan; empirical vptr + tagged-ptr fixes) |
| M3b-3 | Transfer the binder to the app + hold it | ✅ **DONE** — daemon extracts+wraps via manual symbol resolution, delivers over BinderDelivery; app holds it (ping=true) |
| M3d | Independent ref keeps track alive after client ref released? | ✅ **CONFIRMED** (in-process, real pingBinder=0 for 6s) — refcount controls lifetime |
| M3c | Kill daemon → track SURVIVES? | ✅ **PROVEN** — ping(alive)=true past 'Daemon binder died' |
| M3e | App keeps the surviving track CAPTURING + real audio (no silencing)? | ✅✅ **PROVEN** — app drives the FIFO (w46 drain, no libaudioclient); after the daemon dies it keeps capturing REAL audio (rear advancing, ringPeak non-zero). Option B fully viable |

---

## 6. Resume checklist

1. Read this file + memory `spike-audio-handoff-status.md`.
2. Phone connected (serial `6011b07e`), USB "File transfer".
3. **M3a is DONE.** On the **main device (A16/OnePlus 12)** just sanity-run `adb shell /data/local/tmp/cvprobe capture mic` → expect `capture=WORKS audio=REAL` (session fix is universal). Then test `capture voicecall` during a live call (source=4). If anything differs, diff via the Java ref (`docs/dev-notes/spike-tools/Ref.java` → `cvref.jar`).
4. **M3b (extraction) is DONE** — cblk fd (via `/proc/self/fd`) + `IAudioRecord` binder (`ar+0x190`→`+0x10`) both proven offset-free on A14. Next = **M3b-3 + M3c integration** (needs the app/daemon, do it on the main device): wire the helper into the daemon, hand the cblk fd (PFD) + `IAudioRecord` (`writeStrongBinder`) to the app over `IRecorderService`, app holds the binder + reads the cblk FIFO, then **kill daemon+helper** and observe survival (cblk pos advances) + real-vs-silenced audio. MIC then VOICE_CALL. See §M3b "final phase".

---

## 7. Persistence-architecture implications — what Option B replaces (and what it does NOT)

_(2026-07-25 — code-grounded review of the current bootstrap + recording paths on `spike/audio-handoff`, prompted by the question "the whole point was persistence — can this replace WD / loopback / keep-alive? does it work Wi-Fi off?")_

### The immovable constraint

Non-root `VOICE_CALL` capture requires a **shell-uid (2000) process to CREATE the `AudioRecord`** (only that uid holds `CAPTURE_AUDIO_OUTPUT`/`VOICE_CALL`; the app process is namespace-sealed and can't even `dlopen` the audio libs — M1/M2). The only non-root way to get a shell-uid process is **ADB**, which needs a transport: **WD** (`adb_wifi_enabled`, Wi-Fi-only — `AdbShell.enableWirelessDebugging`) or **classic-tcpip loopback** (off-Wi-Fi, opt-in — `AdbShell.connectLoopback`/`armLoopbackIfNeeded`, `OFFLINE_RECORDING_ENABLED`). **Option B does not touch this chain.**

### The daemon's job splits into two phases

| Phase | What it does today | Needs | Option B effect |
|---|---|---|---|
| **CREATE** (call start) | shell-uid daemon creates + `start()`s `AudioRecord(VOICE_CALL)` | ADB (WD/loopback) + shell uid | **unchanged — still required** |
| **SUSTAIN** (during call) | daemon holds the track, `read()`s, encodes (`MediaCodec`), muxes into the app's SAF fd for the *whole call* (`DirectAudioRecorderSession`) | daemon alive for the entire call | **ELIMINATED** — daemon hands `IAudioRecord`+cblk to the app at call start; app reads the ring + encodes into its own fd; daemon may die |

Today a mid-call daemon death is fatal and explicitly unrecoverable — `AudioRecordingEngine.kt:180-182` (`livenessWatch`, one-shot): _"Recorder daemon binder died while a recording is live — no audio is being captured … the daemon-mode pipeline cannot recover mid-call."_ Option B deletes that failure mode: **any recording that STARTS will COMPLETE**, regardless of what kills the daemon mid-call (screen-off adbd kill, Athena ~30-min reap, WD churn — all become harmless once capture is underway).

### The three questions, answered honestly

- **Is WD still necessary?** **Yes.** Still needed to launch the daemon so it can CREATE the track at call start. Option B removes the daemon's need to *survive the call*, not its need to *exist when the call begins*.
- **Is loopback still necessary?** **Yes, for off-Wi-Fi.** Its only job is launching/relaunching the daemon without Wi-Fi. Option B provides no transport — it changes *who holds the track after creation*.
- **Does it work Wi-Fi off?** **Only under today's condition** — offline recording opted-in + loopback armed (arming needs Wi-Fi+WD once, clears on reboot). Option B does not independently unlock off-Wi-Fi.

### What CAN be replaced / degraded / removed

- ✅ **The mid-call daemon-death failure mode** — `AudioRecordingEngine.livenessWatch`'s one-shot "cannot recover" becomes moot; the recording survives daemon death outright. **Headline win.**
- ✅ **The USB "Charging only" screen-lock workaround (shipped 1.4.5) loses its critical role** — it existed to stop the screen-off adbd kill from killing a *live* recording; with Option B that kill is harmless mid-call. (May still marginally help keep the daemon warm *between* calls, but it no longer protects recordings.)
- ✅ **Mid-call resume / fast-recovery work becomes unnecessary** — nothing to recover; the recording never stops. (This was previously flagged as "our edge over SCR"; Option B makes it obsolete by prevention.)

### What CANNOT go away

- ❌ **WD / ADB bootstrap** — daemon must be launchable at call start.
- ❌ **Loopback** — still the only off-Wi-Fi launch path.
- ❌ **Daemon keep-alive** (`DaemonKeepAliveService`) — still needed so the daemon is up (or launchable in ~1–2 s) *when a call rings*. Its role narrows from "survive the whole call" to "be ready at the start," but it doesn't disappear.

### The one remaining gap for true "record no matter what"

Option B guarantees the SUSTAIN half. The only surviving risk is the **START**: the daemon must be alive, or launched fast enough, at the instant a call begins (the cold-start / bootstrap problem — untouched by Option B).

### 🔬 Speculative future path — eliminate the CREATE dependency too (UNPROVEN, worth investigating)

> Expanded into its own research doc with code-grounded "what it replaces" + a VoIP capture track + creative ideas: **`docs/dev-notes/capture-research-directions.md`** (Track A). Summary below.

Have the app hold a **pre-created, idle `VOICE_CALL` `AudioRecord`+cblk across calls** — created once by a briefly-alive daemon (e.g. post-boot / post-onboarding), then handed off and held indefinitely by the always-alive app. If an idle `VOICE_CALL` track simply starts yielding audio when a call becomes active, then **no daemon is needed at call start at all** — the app is already holding the capture, and the ADB/WD/loopback bootstrap collapses to a one-time (or occasional re-arm) event rather than a per-call requirement. This is the ONLY route that would drop the per-call daemon dependency entirely.

**Open risks to validate before betting on it:**
- **Idle silencing** — the appop/UID-state monitor may zero-fill (or the RecordThread may STANDBY) a track that has no active reader / whose creator uid goes non-foreground (the M3e silencing risk, but across hours of idle rather than seconds).
- **Multi-hour track validity** — does an `IAudioRecord` + cblk survive doze / audio-policy reconfig / route changes for hours, and resume cleanly when a call starts?
- **Does `VOICE_CALL` gate on an active call at CREATE or at data-time?** — if `getInputForAttr` refuses to create a `VOICE_CALL` input when no call is active, you'd have to create it *at* call start anyway (defeating the point), or hold a MIC/other-source track and switch — needs a probe.
- **Battery** — an always-open capture track's cost.
- **Re-arm after reboot** — tcpip/loopback clears on reboot, so the daemon still needs one connectivity moment post-boot to re-create the held track; not fully "zero daemon," but "daemon once per boot" instead of "daemon per call."

Cheap first probe: on the main device, create a `VOICE_CALL` `AudioRecord` in the daemon with NO call active, hand off to the app, hold idle 10–30 min through screen-off/doze, then place a call and check whether the held track produces real audio without re-touching the daemon.

---

## 8. What shipped

Option B shipped as **"Resilient recording"** — a Settings ▸ Reliability opt-in, default **OFF**. With the
toggle off the recording path is unchanged, so the feature is fully reversible in-app.

### Where the code lives now

| Path | Role |
|---|---|
| `app/src/main/cpp/audiohandoff.cpp` | JNI: validate + extract the `IAudioRecord` binder, find the cblk fd, drain the ring to a pipe |
| `.../services/recording/handoff/HandoffSource.kt` | DAEMON: creates the privileged `AudioRecord`, extracts + delivers it |
| `.../services/recording/handoff/HandoffReceiver.kt` | APP: holds the binder (keep-alive), starts drain + encode, releases at call end |
| `.../services/recording/handoff/HandoffEncoder.kt` | APP: PCM pipe → `MediaCodec` → `MediaMuxer` into the SAF fd |
| `.../services/recording/handoff/HandoffGeometry.kt` | cblk ring geometry + the validation that gates every native mmap |
| `.../services/recording/handoff/AudioHandoffNative.kt` | JNI bridge (app loads by name, daemon loads by path) |
| `.../integrations/scrcpy/ScrcpyAudioSourceMapping.kt` | shared cliKey → `MediaRecorder.AudioSource` map (which sources support the handoff) |
| `.../utils/PcmDownmix.kt` | shared stereo→mono downmix (both capture pipelines) |
| `app/src/main/aidl/.../IRecorderService.aidl` | `startHandoff(source, rate, channels)` / `stopHandoff()` |

Build config that is **required, not scaffolding**: `ndkVersion`, `ndk { abiFilters }`,
`externalNativeBuild`, `buildFeatures { aidl }`, and `packaging { jniLibs { useLegacyPackaging = true } }`
— the daemon has no classloader library-search path, so the `.so` must exist as an extracted file for it
to `System.load` by absolute path.

### Known limitation: the app cannot heal an invalidated track

The app holds the control block and the binder, but NOT an `AudioRecord` object — that lives in the
daemon, which by design may be dead. `AudioRecord::restoreRecord_l`, the silent rebuild AudioFlinger
expects a client to perform after an invalidation, is therefore unreachable, and
`EVENT_NEW_IAUDIORECORD` is never dispatched on the record path. When the track is torn down the app
gets no exception, no callback and no dead binder: the ring simply freezes.

`nativeDrainToPipe` therefore watches for it directly — `CBLK_INVALID` in the control block's flags
word (offset 44, cross-checked against the empirically pinned `mFront`=46), plus a rear cursor that
stops moving for 10 s as a layout-independent backstop. Note a *silenced* track still advances the
ring at full rate, so ring progress alone proves nothing.

These are **diagnostic**: they end the drain cleanly so the container finalises and log why, instead
of spinning until the call ends and truncating the recording with no explanation. They cannot recover
the capture. Known triggers are uncommon — input preemption at `maxOpenCount`, audioserver restart,
and route changes that close the input. Switching a live call from speaker to a **Bluetooth** headset
was tested on-device with the feature on and did NOT trigger it; BT SCO connect is the classic
`checkCloseInputs()` path, so that is meaningful evidence.

### What was removed

All the milestone probes: the ashmem FIFO (`spikeStartFifoProbe` + `nativeCreateFifo`/`nativeReadFifo`),
the dlopen/namespace reachability probe (`nativeProbe`), the manual-symbol-resolution test
(`nativeResolveTest`), the cblk diagnostics (`nativeMonitorCblk`/`nativeDumpCblk`), the standalone
`audiohandoffprobe.cpp` executable and its CMake target, the daemon-boot probe block in
`RecorderServer.main`, and the auto-fire in `CallVaultApplication.onCreate`.

Also removed: two temporary debug aids used while diagnosing on-device (an unconditional `AppLogger`
mirror to external storage, and a daemon trace to `/data/local/tmp/cv_handoff.log`).
