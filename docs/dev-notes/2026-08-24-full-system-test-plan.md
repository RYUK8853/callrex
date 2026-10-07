# Full-system test plan — every feature, flow, pipeline, service and daemon

Written 2026-08-24, on branch `feat/shizuku-support` at the point where Shizuku support is complete and
2.1.0 is waiting on its release gate. The goal is a single sheet that answers "has this been tested, how,
and by whom?" for **everything the app does** — not only the parts that changed.

## How to read it

Every row has a **tier**, which says who can run it and how much it proves:

| Tier | Meaning |
|---|---|
| **H** | Host — unit tests, lint, build gates. Runs on a laptop with no device. |
| **E** | Emulator — instrumented, `emulator-5554`. Proves binder/transaction/process mechanics. |
| **D** | Device, automated — driven over adb on the OP9 (`daabf34f`). Proves real service and process state. |
| **M** | Maintainer — needs a real two-sided call, a real network, or a reboot with a SIM. Cannot be automated. |

**Status** is one of ✅ pass, ❌ fail, ⏭️ not run, 🚧 blocked, or **M** for a maintainer row that is by
definition outstanding until a real call happens.

The point of the tiers is honesty about coverage. An H row passing means the logic is right; it does not
mean audio comes out of a phone. Only **M** rows prove that, which is why they are listed rather than
quietly folded into "tests pass".

---

## A. Build and static gates (tier H)

| ID | What | How | Status |
|---|---|---|---|
| A1 | Whole unit suite | `testDebugUnitTest` | ✅ 781 tests, 0 failures |
| A2 | Release build compiles | `assembleRelease` | ✅ |
| A3 | Release lint (crash-class only) | `lintVitalRelease` | ✅ |
| A4 | Every locale has every string | `checkTranslations` | ✅ |
| A5 | Release APK is **not** debuggable | manifest check on the built APK | ✅ no debuggable attr; installed pkgFlags clean |
| A6 | Version is bumped for the release | `ciVersionName` in `app/build.gradle.kts` | ✅ 2.1.0 |

## B. Privileged transport — the mode machinery

The class of bug that cost a full day: two recorder hosts alive at once. Each row is one of the five
rules in `only-one-recorder-host`.

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| B1 | Standalone → Shizuku leaves **exactly one** recorder | D | switch, then `ps`/`pgrep` both patterns | ✅ 1 recorder, host=shizuku |
| B2 | Shizuku → standalone leaves **exactly one** recorder | D | as above | ✅ **after the fix below** |
| B3 | The switch waits for the old binder to actually die | H+D | unit + log ordering | ✅ teardown 105 ms (was a 5 s timeout) |
| B4 | `onBinderDied` does not clear a **live** holder | H | unit | ✅ kept the live daemon when the old binder died |
| B5 | `killStaleRecorders` never kills itself | H+E | unit + round-trip still connected after | ✅ `Clearing N other recorder process(es) (I am X)` |
| B6 | Entering Shizuku drops the old user service first | D | log line + fresh pid | ✅ fresh pid each entry |
| B7 | No CallVault service runs in the mode that does not own the recorder | D | `dumpsys activity services` | ✅ no services in the non-owning mode |
| B8 | `DaemonKeepAliveService` is stopped on entering Shizuku | D | dumpsys + log | ✅ |
| B9 | …and does not start on app launch in Shizuku mode | D | force-stop, launch, dumpsys | ✅ logs the standdown on every launch |
| B10 | Wireless debugging is left alone in Shizuku mode | D | `settings get global adb_wifi_enabled` | ✅ adb_wifi_enabled stayed 0 |
| B11 | Every daemon start goes through `RecorderBackend` | H | grep gate: no direct launcher calls | ✅ grep gate clean |
| B12 | Binder round-trip works over our own daemon | E | `RecorderDaemonRoundTripTest` | ⏭️ not run this pass |
| B13 | Binder round-trip works over Shizuku | D | `RecorderShizukuRoundTripTest` | ⏭️ test written, not executed |
| B14 | A stale user service is torn down on app replace | D | install over, check pid changed | ✅ pid 20440 → 23678 |

## C. Capability gating — greying out and auto-off

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| C1 | `ModeCapability` maps every capability for both modes | H | unit | ✅ unit |
| C2 | Switching to Shizuku turns **off** what it cannot do | H | `disableWhatModeCannotDo` unit | ✅ unit |
| C3 | Returning to standalone never silently turns anything **on** | H | unit — the asymmetry is the point | ✅ unit |
| C4 | The reconcile also runs on **app start**, not only on a switch | H+D | unit + prefs after launch | ✅ unit + observed on launch |
| C5 | Greyed rows are actually disabled in the UI, not just unset | D | UI dump of Settings in Shizuku mode | ✅ **after the fix below** |
| C6 | Handoff is ruled out under Shizuku | H | `HandoffPolicy` unit | ✅ unit |
| C7 | Setup prerequisites differ correctly per mode | H | `SetupPrerequisitesModeTest` | ✅ unit |

## D. Capture pipelines — the three paths

All three exist and are easy to forget; anything reading captured audio must be in all three.

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| D1 | Carrier call, standalone, **direct** AudioRecord | M | real call, then check the log names the direct path | **M** |
| D2 | Carrier call, standalone, **handoff** (resilient on) | M | real call — this is the OP12's default path | **M** |
| D3 | Carrier call, **Shizuku** (scrcpy) | M | real call on the OP9 | **M** |
| D4 | Two-sided audio present in D1/D2/D3 | M | **listen** to the file — a lost far side is invisible in logs | **M** |
| D5 | VoIP call, standalone | M | real WhatsApp call | **M** |
| D6 | VoIP arming survives a mode round-trip | M | round-trip, then a real VoIP call | **M** ← raised in priority by the fix |
| D7 | VoIP is not offered under Shizuku | H+D | capability unit + UI | ✅ |
| D8 | A missed VoIP call notifies rather than retries | H | unit — retry is impossible by design | ✅ unit |
| D9 | Speaker turns are produced in standalone | M | real call, check `Stored speaker turns` | **M** |
| D10 | Speaker turns are absent under Shizuku **and the user is told** | D | known gap — nothing surfaces it | ❌ still unsurfaced — open gap |
| D11 | Offline (loopback) recording is standalone-only | H | capability unit | ✅ |
| D12 | The daemon survives a mid-call kill when resilient is on | M | kill the daemon during a real call | **M** |

## E. Services, receivers and the daemon (tier D unless noted)

Every component declared in the manifest, plus the workers.

| ID | Component | What is checked | Status |
|---|---|---|---|
| E1 | `CallMonitorService` | starts on a call, stops after, does not linger | **M** |
| E2 | `RecordingForegroundService` | foreground type is legal on 14+, notification shows, stops cleanly | **M** |
| E3 | `DaemonKeepAliveService` | runs in standalone only; stopped and never restarted in Shizuku | ✅ standalone only; stopped and stays stopped in Shizuku |
| E4 | `AdbConnectionService` | connects in standalone; inert in Shizuku | ✅ connects in standalone, inert in Shizuku |
| E5 | `AdbPairingService` | pairing flow reachable from onboarding | ⏭️ |
| E6 | `PhoneStateReceiver` | registered; fires on a real call (protected broadcast — cannot be faked) | ✅ registered (firing needs a real call) |
| E7 | `BootReceiver` | fires on boot in **both** modes (was gated on pairing) | ⏭️ needs a reboot |
| E8 | `RetentionTimezoneReceiver` | reschedules the sweep on a timezone change | ✅ registered |
| E9 | `UpdateInstallReceiver` | handles the install result | ⏭️ |
| E10 | `UpdatePackageReplacedReceiver` | tears the daemon/user service down after replace | ✅ restarts the user service on replace |
| E11 | `RecorderBinderProvider` | binder delivery uses our own authority | ✅ delivery via our own authority |
| E12 | `MainActivity` | launches, survives rotation and process death | ✅ launched ~20× this pass |

## F. Background work (WorkManager)

| ID | Worker | What is checked | Tier | Status |
|---|---|---|---|---|
| F1 | `TranscriptionWorker` | enqueued, constraints honoured, writes transcripts | E+M | **M** |
| F2 | `SummaryWorker` | runs after transcription, respects language | E+M | **M** |
| F3 | `ModelDownloadWorker` | downloads, verifies, resumes | M | **M** |
| F4 | `RecordingCopyWorker` | copies to the chosen storage target | D | ⏭️ |
| F5 | `SyncSweepWorker` | Drive sync on schedule | M | **M** |
| F6 | `RetentionSweepWorker` | deletes past the retention window | H+D | ✅ scheduler decision logged |
| F7 | `UpdateCheckWorker` | polls GitHub, respects the toggle | D | ⏭️ |
| F8 | `UpdateInstallWorker` | standalone: silent `pm install`; Shizuku: `UNAVAILABLE` → one tap | H+D | ✅ unit |

## G. Post-processing — transcription, summaries, speakers, search

| ID | What | Tier | Status |
|---|---|---|---|
| G1 | Transcription produces segments for a real Hebrew call | M | **M** |
| G2 | Language pinning beats auto-detect | M | **M** |
| G3 | A **non-Hebrew** call transcribes correctly | M | ← 2.1.0 gate |
| G4 | A **long** call (>15 min) transcribes without OOM | M | ← 2.1.0 gate |
| G5 | Summaries generate and respect the requirements | M | **M** |
| G6 | Speaker map override and confirm persist | H+D | ⏭️ |
| G7 | Search finds a word inside a transcript | M | **M** |
| G8 | Deleting a recording makes its transcript unsearchable | M | ✅ confirmed earlier on device |
| G9 | Room migration v1→v2 keeps data | E | ⏭️ |
| G10 | Transcription is untouched by the privileged mode | H | ✅ unit |

## H. Storage, sync, retention, playback, UI

| ID | What | Tier | Status |
|---|---|---|---|
| H1 | Recording folder + storage target honoured | D | ⏭️ |
| H2 | Retention deletes only past the window | H | ✅ unit |
| H3 | Drive sync round-trip | M | **M** |
| H4 | Playback screen opens, waveform draws, notes persist | D+M | ⏭️ |
| H5 | Per-copy delete lives in the confirm dialog | D | ⏭️ |
| H6 | Home status card reflects the **live** mode | D | ✅ tracks the live mode |
| H7 | The mode switch modal cannot be dismissed while working | D | ⏭️ not exercised this pass |
| H8 | The modal's ready tick is inline and green | D | ✅ verified previously |
| H9 | Onboarding offers both modes and completes on either | D | ⏭️ |
| H10 | Settings restructure — every section reachable | D | ✅ every section reachable |
| H11 | Theme, dynamic colour, and no accidental coral | D | ✅ status pill green, not coral |
| H12 | i18n — no missing strings in any locale | H | ✅ checkTranslations |

## I. Regression guards for bugs already fixed

Each of these cost real debugging once; each should now be a test rather than a memory.

| ID | The bug | Guard | Tier | Status |
|---|---|---|---|---|
| I1 | Two recorder hosts alive at once | B1/B2/B7 | D | ✅ |
| I2 | Stale user service holding a dead APK path | B14 | D | ✅ |
| I3 | Keep-alive draining battery in Shizuku mode | B8/B9 | D | ✅ |
| I4 | Status card showing the previous mode | H6 | D | ✅ |
| I5 | Reconcile skipped for users already in Shizuku | C4 | H+D | ✅ |
| I6 | Updater reporting a false failure in Shizuku | F8 | H | ✅ |
| I7 | Boot and warmup skipped in Shizuku mode | E7 | D | ⏭️ |
| I8 | A second voice AudioRecord silently dropping the near side | D4 | M | **M** |
| I9 | M3 defaults rendering coral | H11 | D | ✅ |
| I10 | Daemon churn from `killStaleDaemons` on app restart | B5 | H+E | ✅ |

---

## Results — run 2026-08-24, OP9 (`daabf34f`) on 2.1.0

**63 rows executed, 2 real bugs found and fixed, 1 known gap unchanged, 15 rows left for the maintainer.**

Sections A, B, C, E, F, H are green apart from the rows marked ⏭️ (not run) — those need either the
emulator, a reboot, or a screen this pass did not reach. Section D is mostly **M** by construction: no
amount of automation proves that audio came out of a phone.

### Bug 1 — leaving Shizuku mode left its recorder running, and the app kept using it

**The most serious thing found, and it was live on the maintainer's phone at the start of the run.** The
very first process listing showed the phone in standalone mode with a `com.baba.callvault:recorder`
still alive — a Shizuku-hosted recorder in a mode that should not have one.

Reproduced deliberately and traced:

```
17:48:32.906  Leaving Shizuku mode; releasing the user service
17:48:32.911  A previous recorder's binder died; the current one is alive - keeping it
17:48:37.925  The previous recorder is still connected after 5000ms      <- the wait gave up
17:48:37.929  Privileged mode is now STANDALONE
17:48:37.932  Recorder daemon already connected; reusing existing binder <- the SHIZUKU binder
```

30 s later the only recorder on the phone was still the Shizuku one, and the switch dialog had said
**"Ready — using CallVault"**. That claim is not cosmetic: `ModeSwitchResult.Ready` means a live binder,
and a live binder was exactly what it had — the wrong one.

The cost is entirely silent. Capture keeps working, so nothing looks broken, but it goes through scrcpy:
**handoff, VoIP arming and speaker attribution all quietly stop happening**, and the front clip that
v1.4.0 removed comes back. A user who tried Shizuku once and switched back would have a permanently
degraded standalone mode with no symptom to report.

Root cause: `ShizukuBackend.stop(remove = true)` only asks *Shizuku* to destroy the service. On this
phone it did not. The ADB branch of the same `when` had always called `destroy()` on the binder it holds;
the Shizuku branch never did.

Fixed in `RecorderBackend.switchTo`, plus `RecorderConnection.forceClear` so a timed-out teardown drops
the old host's binder instead of carrying it into the new mode. After:

```
18:06:52.847  Leaving Shizuku mode; releasing the user service
18:06:52.873  Daemon binder died; clearing RecorderConnection
18:06:52.952  Previous recorder is gone; starting the STANDALONE backend   (105 ms, not 5 s)
18:06:57.136  Recorder daemon connected on attempt 1; binder available
18:06:57.418  Clearing 1 other recorder process(es)
```

Covered by a new instrumented regression test that asserts the mode switch leaves neither the process
nor the binder behind.

### Bug 2 — offline recording was auto-disabled under Shizuku but stayed tappable

`ModeCapability` has always listed `OFFLINE_RECORDING` as unavailable under Shizuku, and the reconcile
duly turned it off — but the row itself was never greyed, so it could be switched straight back on and
would silently do nothing. Resilient recording and VoIP already used `unavailableReason`; this was the
third row that needed it. The Experimental screen now renders the note twice, once per row.

While checking the other three gated capabilities: `DAEMON_KEEP_ALIVE` and `WIRELESS_DEBUGGING_CONTROL`
have **no Settings row at all**, and their preferences have no reader anywhere —
`DaemonKeepAliveService` gates on the privileged mode directly. Nothing to grey, and worth knowing
before someone goes looking for the rows the audit implies exist.

### Bug 3 — a mode round trip was a one-way door (fixed after the run)

Raised by the maintainer reading the first draft of this document, and the sharpest catch of the day.

`disableWhatModeCannotDo` only ever switched things **off**, and nothing ever switched them back. So
turning Shizuku on to look at it, and immediately off again, permanently left resilient recording, VoIP
recording and offline recording disabled — with nothing on screen saying so, because the list of what
changed went only to the log. Recording still worked, so there was no symptom to report; the user just
silently lost the setup they had chosen.

The original one-way design existed to protect a real property: a mode switch must never re-enable
something the *user* had deliberately turned off. That property is kept, because the fix does not
snapshot settings — it records **which switches CallVault itself turned off**, and restores only those.
Anything the user turned off was never recorded, so it is never touched.

Recorded per switch rather than per capability, because VoIP recording and VoIP auto-start share one
capability: restoring the capability wholesale would hand auto-start to someone who only ever wanted the
manual prompt.

Verified on the OP9, reading the actual switch node at each step:

| Step | Resilient recording |
|---|---|
| standalone, turned on by hand | `checked=true enabled=true` |
| → Shizuku | `checked=false enabled=false` |
| → standalone | `checked=true enabled=true` |

```
Turned off (unsupported in SHIZUKU): RESILIENT_RECORDING
Turned back on (supported again in STANDALONE): RESILIENT_RECORDING
```

Eight unit tests cover the round trip, including the cases that make it safe rather than merely
convenient: a switch the user turned off stays off, a restored record is consumed so a later reconcile
cannot resurrect it, a restore in a mode that still cannot do the thing is a no-op that keeps the record,
and a second round trip remembers the newer answer.

### Bug 4 — wireless debugging can be left on indefinitely (found, not fixed)

Noticed as an odd end-state, then reproduced and diagnosed. **Not fixed**, deliberately — see below.

Wireless debugging is enabled and disabled in two different files. `AdbShell` turns it **on** whenever
it needs to (re)establish the embedded ADB connection (`AdbShell.kt:170`), and only
`RecorderServerLauncher` turns it back **off**, on the path where that connection ended in a daemon
launch (`RecorderServerLauncher.kt:233`). So any reconnect that does *not* end in a launch leaves it on,
with nothing left to turn it off.

Measured on the OP9 — the last cycle has no matching disable:

```
19:44:49.843  Wireless debugging disabled (DROP_USB_KEEPS_ADBD; commands flow over binder)
19:45:19.605  Re-enabled Wireless debugging; waiting for adbd to advertise…
19:45:23.402  dumpsys usb printed no screen_unlocked_functions line
              (…nothing further…)
```

and `adb_wifi_enabled` was still `1` three minutes later, with no daemon launch in between. The trigger
here looks like the USB-default-mode check: it runs `dumpsys usb` over the ADB shell, which reconnects,
which enables wireless debugging — and since no launch follows, the disable never runs. That last step is
inferred from the log ordering rather than traced through the code, so treat the *trigger* as probable
and the *asymmetry* as established.

Why it matters: wireless debugging is an open network port. Leaving it on after the thing that needed it
is finished is exactly the exposure that got the off-Wi-Fi loopback work parked.

Why it is not fixed here: this is the WD lifecycle, which is where the Samsung churn loop came from —
disabling WD when it was the last transport killed the daemon and span. The fix is probably to pair the
enable with its own disable in `AdbShell` rather than relying on the launcher, but it needs its own
think and its own device test, and it is unrelated to the mode work this run was about.

## The recording matrix — every flow-affecting setting, both call types, both modes

Derived from the preferences the recording and call services actually read, not from the Settings screen
— so it covers what changes behaviour and nothing that does not. Codec, bit rate, file-name template,
storage target, theme and the transcription settings are all deliberately absent: they change the file
or the UI, never the path the audio takes.

### The axes that matter

| Axis | Values | What it changes | Read by |
|---|---|---|---|
| Privileged mode | standalone / Shizuku | which process captures | `getPrivilegedMode` |
| Resilient recording | on / off | **capture path**: handoff vs direct | `isHandoffPersistEnabled` |
| Offline recording | on / off | **transport**: loopback vs wireless debugging | `isOfflineRecordingEnabled` |
| VoIP recording | on / off | whether app calls record at all | `isVoipRecordingEnabled` |
| VoIP auto-start | on / off | records by itself vs prompts | `isVoipAutoStartEnabled` |
| Carrier recording | on / off | whether phone calls record at all | `isCarrierRecordingEnabled` |
| Auto-record incoming / outgoing | on / off | whether that direction records | `isAutoRecordIncoming/OutgoingEnabled` |
| Ignore rules | on / off | whether recording starts for this caller | `isIgnore*`, `getIgnoredContacts*` |
| Audio source | `voice-call` / `mic-voice-communication` | which capture source | `getAudioSource` |

Under Shizuku the first three are greyed and forced off, so that mode has fewer rows — that is the
point of the greying, and rows Z2 checks it.

### Where each switch lives, and the baseline it starts from

Mapped on the OP9, 2026-08-25, by `spike-tools/crawl.sh`. Worth writing down because two of them are
not where you would look for them.

| Setting | Path |
|---|---|
| Record phone calls | RECORDING ▸ **Phone calls** (accordion) |
| Automatically record incoming / outgoing | RECORDING ▸ **Incoming calls** / **Outgoing calls** (accordions) |
| Ignore anonymous / cross-country / contacts | inside those same two accordions |
| Resilient recording | GENERAL ▸ **Experimental** |
| Offline recording | GENERAL ▸ **Experimental** |
| Record VoIP calls + **Start automatically** | GENERAL ▸ **Experimental** — not under RECORDING |
| USB debugging | GENERAL ▸ **Experimental** |
| Audio source / codec / bit rate | AUDIO CONFIGURATION (pickers, not switches) |

**S1 baseline, set and verified:** mode standalone; record phone calls **on**; auto-record incoming and
outgoing **on**; every ignore rule **off** with "Record all contacts" both directions; resilient
recording **on**; VoIP recording **on** with auto-start **on**; offline recording **off** (it has its own
row, S5); audio source `Voice call`.

Two things the mapping turned up, neither chased:

1. **"Ignore cross-country incoming calls" is greyed out** while the outgoing equivalent is not. There
   may be a good reason — an incoming rule needs a home country the SIM has not supplied — but it is
   asymmetric and undocumented.
2. **Turning VoIP recording on raises a confirmation** ("I understand, turn it on"), so it cannot be
   flipped blind. The driver knows that dialog now; anything else automating it will not.

### How each row is judged

Every row records the same four things:

1. **Result** — is there a file, and is it the expected size (or correctly absent)?
2. **Pipeline** — which path the log names: `Handoff: startHandoff…`, the direct session, scrcpy, or
   `armVoipCapture`.
3. **WD status** — `adb_wifi_enabled` **after** the call settles. It must be `0` in standalone unless
   wireless debugging is adbd's only transport. This is the row that was leaking until yesterday.
4. **Audio** — only where the capture path itself changed. Rows expecting *no* recording need only a
   few seconds of call; rows expecting audio need the near-side clip and a few words from the far side.

`A` in the table below = needs real two-sided audio. `Q` = quick, connect and drop.

### Results as rows are run
**S13-baseline — INCOMING call, standalone, everything on. PASS (2026-08-25).** Closes the day's one
structural gap: every other call was outgoing.

| What | Result |
|---|---|
| Ring → answer | rang after 8 s, answered by `KEYCODE_HEADSETHOOK`, reached off-hook |
| Direction | correctly identified — `Sending stop INTENT for INCOMING call` |
| Contact | resolved from an **inbound** number: `20260825_132010.896+0300_in_פרוזה.ogg` |
| Pipeline | handoff — `HandoffEncoder finished: 21.768s` |
| Speaker turns | produced and stored |
| Services | `RecordingForegroundService` up mid-call, gone after; keep-alive as expected |
| WD | 0 throughout |
| File | 75,134 bytes, mono opus, 21.76 s, mean -35.8 dB |

The incoming path exercises code the outgoing rows never touch — a different `PhoneStateReceiver` state
sequence, the direction branch in `CallSessionManager`, the separate auto-record-incoming switch, and
contact resolution against a number this phone did not dial. All four fixes made on 2026-08-25 sit on
code this path also runs through, and it behaves identically to the outgoing case.

**Maintainer-run rows — all PASS (2026-08-25), reported from the device.**

| Row | Configuration | Expected | Result |
|---|---|---|---|
| S4 | resilient **off**, VoIP call | still records | ✅ |
| S8 | VoIP recording **off**, VoIP call | no recording | ✅ |
| S9 | VoIP auto-start **off**, VoIP call | prompt, not auto-record | ✅ |
| S11 | carrier recording **off**, VoIP call | still records | ✅ |
| Z2 | Shizuku, VoIP call | no recording | ✅ |
| S13 | auto-record incoming **off**, incoming call | no recording | ✅ |
| Z6 | Shizuku, **incoming** call | records via scrcpy | ✅ |

**Z6 confirmed from the log, not just the file**: `ScrcpyClient` packets, `ScrcpyAudioMuxer` finalising,
and `Sending stop INTENT for INCOMING call` — so incoming × Shizuku genuinely runs the scrcpy path. That
combination shared no tested code with anything else in this matrix.

That completes every row except S15 (needs a real contact-list edit) and Z5/Z7.

**Two wireless-debugging bugs, found by the maintainer noticing WD blink in Shizuku mode.**

Deleting the debug logs and toggling logging off and on made Wireless debugging switch on — in the one
mode that is supposed to never touch it. Two separate faults:

1. **`SystemLogCollector` drives the embedded ADB regardless of mode.** The audit already listed
   "diagnostics needs the ADB shell" as a Shizuku gap, but recorded it as a *degraded feature*. It is
   worse than that: it silently opens a network port. It now skips the shell entirely in Shizuku mode
   and says so, losing the ring-size commands there — a much smaller cost.
2. **The lease leaked.** Every release logged `last=false`, meaning the count never reached zero and
   `releaseWirelessDebugging` never ran once — `adb_wifi_enabled` sat at **1**. The holder was the
   *opportunistic* USB-default read, which runs on a bounded worker thread that is deliberately
   abandoned when the ADB stream wedges. The abandoned thread held a lease nobody could return. That
   path never connects and so has nothing to release; it no longer takes the lease at all.

The second was a bug in the lease added the same morning, and it is exactly the failure it was meant to
prevent, arriving by a different door. `last=false` in the log is the tell.

**Z7 — VoIP after a mode round trip. Already covered, no separate call needed.**

The last Shizuku → standalone switch was at 12:28:53. S6's VoIP call ran at 13:00:00 with
`armVoipCapture -> true` and a clean recording, and the maintainer's own VoIP rows at 13:34 followed the
same round trip. "VoIP arming survives a mode round trip" is therefore demonstrated twice, which is the
whole content of Z7.

**Z5 — `mic-voice-communication` under Shizuku. Dropped deliberately.**

It would only tell us whether scrcpy degrades that source the same way the daemon path does. Since S14
established the source is a fallback and the maintainer has parked it, that is a curiosity about a
source users are being steered away from, not a risk. Recording the decision so nobody re-adds it as an
oversight.

**That leaves S15 as the only unsettled row** — an ignore rule matching the caller, which needs a real
contact added to and then removed from the ignore list.

### Coverage after the 2026-08-25 session

Thirteen rows run, twelve pass, one void and re-run. What is now demonstrated on a real device:

| Dimension | Covered |
|---|---|
| Capture paths | handoff, direct AudioRecord, scrcpy, VoIP — **all four** |
| Transports | wireless debugging **and** loopback |
| Modes | standalone and Shizuku, **both switch directions verified by a call** |
| Gates | carrier master, auto-record outgoing, VoIP-off isolation — in **both** modes |
| Audio source | `voice-call` good; `mic-voice-communication` degraded and loses speaker turns |

**The gap this session did not close: every single call was OUTGOING.** Thirteen recordings made today,
zero incoming. The incoming path is not a variation on the outgoing one — `CallSessionManager` handles
direction separately, `PhoneStateReceiver` sees a different state sequence, auto-record incoming is its
own switch with its own ignore rules, and contact resolution runs against a number the phone did not
dial. All four fixes made today touch code an incoming call also runs through.

That makes **S13 and Z6 the highest-value remaining rows**, and both need the other phone to originate.

Still outstanding after that: S4 (resilient off + VoIP), S8/S9/S11 and Z2 (the VoIP gates), and S15
(an ignore rule matching the caller, which means editing a real contact list).


**S1 — standalone, resilient on, outgoing cell call. PASS (2026-08-25).**

| What | Result |
|---|---|
| Pipeline | **handoff** — `AudioHandoff drainToPipe` streaming, `HandoffEncoder finished: 20.952s` |
| Speaker turns | produced — `Speaker turns came from the handoff capture` → `Stored speaker turns` |
| Services | `CallSessionManager` saw the state change; `RecordingForegroundService` started and stopped; nothing lingered |
| WD after | **0** — the lease fix holding under a real call |
| File | 72,571 bytes, mono opus, 20.94 s, mean -42.1 dB / max -15.5 dB |
| Audio | **both sides confirmed by ear** |

This is row **D2**, not D1: resilient recording is on, so the call took the handoff path. D1 needs
resilient off, which is S3.

**It took two attempts, and the first failure is the useful part.** The first run recorded 11 seconds of
silence exactly where the near-side clip should have been. During a call, media audio is routed to the
**earpiece**, so the clip played perfectly and the microphone never heard a thing. The harness now
switches the call to speakerphone before playing, and — at the maintainer's suggestion — switches it
back off as soon as the clip ends, because a loudspeaker left on replays the far side into this phone's
own microphone and stops the near side being a clean signal to judge.

Worth remembering for any future audio test: a silent near side has two completely different causes that
look identical in the file — the app failed to capture it, or nothing was ever transmitted. Only the
second one is a harness bug, and the way to tell them apart is to ask whether the far end *heard* it.

**S2 — standalone, everything on, WhatsApp call. PASS, with one thing to watch (2026-08-25).**

| What | Result |
|---|---|
| Detection | `VoIP call detected (mode=IN_COMMUNICATION)`, app resolved to uid 10207 on attempt 1 |
| Pipeline | `startVoipRecording codec=opus bitRate=24000` → `VoIP capture started: rate=48000` |
| Far side | `farPartyHeard=true` |
| WD after | **0** |
| File | 83,471 bytes, **mono** opus, 19.64 s, mean **-19.0 dB** / max -3.8 dB |
| Silence spans | none over 1.5 s |

Much louder than the carrier call (-19.0 dB against -42.1 dB), which is worth knowing before judging any
future recording by its level alone: the two paths do not land in the same place.

**The thing to watch:** the log reported `near capture silenced by the platform — re-taking the mic`
**seven times** in nineteen seconds, and the capture summary agreed — `19s, 7 silence-filled chunks`.
This is the platform taking the microphone away from our near-side capture mid-call, exactly the
behaviour that makes VoIP recording fragile. The recovery works — no silence span exceeds 1.5 s, so
each gap is short and the file is continuous — but seven interruptions in nineteen seconds is a high
rate, and on a longer call it is worth knowing whether it stays proportional or gets worse.

*A carrier call in the same session showed none of this, so it is specific to the VoIP path.*

**S3 — standalone, resilient OFF, outgoing cell call. PASS (2026-08-25).**

The row S1 could not cover: with resilient recording off the call takes the **direct AudioRecord** path
instead of the handoff, and the log says so in as many words.

| What | Result |
|---|---|
| Pipeline | `Recording via DIRECT AudioRecord — source=voice-call codec=opus` |
| Capture shape | `captureCh=2 encodeCh=1 rate=48000` — two channels captured, encoded to mono |
| Speaker turns | produced — `Speaker turns came from the daemon capture` |
| Auto-record | `Auto-record is enabled for this outgoing call` |
| WD | disabled during setup (`DROP_USB_KEEPS_ADBD`), **0** after |
| File | 80,376 bytes, mono opus, 23.16 s, mean **-33.7 dB** / max -11.7 dB |

**Speaker attribution works on both capture paths** — S1 logged "came from the handoff capture", this one
"came from the daemon capture". That answers a question the code alone made easy to get wrong.

Levels so far, all the same phone and the same clip, which is worth keeping in one place:

| Row | Path | mean |
|---|---|---|
| S1 | handoff | -42.1 dB |
| S3 | direct | -33.7 dB |
| S2 | VoIP | -19.0 dB |

**S5 — standalone, offline recording ON, outgoing cell call. PASS (2026-08-25).**

The transport row: the daemon is reached over a loopback port instead of wireless debugging.

| What | Result |
|---|---|
| Transport | `Connected over loopback tcpip :57780 (works off-WiFi)`, `service.adb.tcp.port=57780` |
| WD | **0 before, during and after** — `Nothing to release after the daemon launch; Wireless debugging is already off` |
| Pipeline | handoff (resilient is back on) — `HandoffEncoder finished: 20.536s` |
| Speaker turns | produced and stored |
| VoIP arming | `VoIP capture policy armed (loopback-render, 48000Hz)` → `armVoipCapture -> true` |
| File | 69,948 bytes, mono opus, 20.52 s, mean **-34.9 dB** / max -11.9 dB |
| Audio | confirmed by ear |

Worth stating plainly because it is the whole point of the mode: **a call recorded correctly with
wireless debugging switched off the entire time**, over a loopback listener that survives being off
Wi-Fi. The lease added yesterday did exactly nothing here, which is right — there was nothing to
release, and it said so rather than toggling anything.

### Standalone

| # | Configuration | Call | Expect | Verify | |
|---|---|---|---|---|---|
| S1 | everything on (resilient **on**) | cell | records via **handoff** | `startHandoff`, speaker turns, WD=0 | A |
| S2 | everything on | VoIP | records, auto-started | `armVoipCapture`, stereo file | A |
| S3 | resilient **off** | cell | records via **direct** AudioRecord | direct session in log, no front clip | A |
| S4 | resilient **off** | VoIP | unaffected — still records | `armVoipCapture` | Q |
| S5 | offline recording **on** | cell | records over **loopback**, WD stays off | loopback in log, WD=0 throughout | A |
| S6 | offline recording **on** | VoIP | unaffected | file present | Q |
| S7 | VoIP recording **off** | cell | unaffected — records | handoff/direct as configured | Q |
| S8 | VoIP recording **off** | VoIP | **no recording**, no false "ready" | no file, no arming in log | Q |
| S9 | VoIP auto-start **off** | VoIP | **prompt** instead of auto-record | prompt appears; recording only if tapped | Q |
| S10 | carrier recording **off** | cell | **no recording at all**, no notification | no file, call ignored | Q |
| S11 | carrier recording **off** | VoIP | app calls unaffected — records | file present | Q |
| S12 | auto-record outgoing **off** | cell (out) | **not recorded** | no file | Q |
| S13 | auto-record incoming **off** | cell (**in**) | **not recorded** | no file — OP12 must call the OP9 | Q |
| S14 | audio source `mic-voice-communication` | cell | records, different source | source named in log; **listen** — this is the pair that can drop the near side | A |
| S15 | ignore rule matches the caller | cell | **not recorded** | no file, ignore reason logged | Q |

**Z1 — switch to Shizuku, then a cell call. PASS (2026-08-25), and it took two bug fixes to get here.**

| What | Result |
|---|---|
| Host | `shizuku(17449)` — the user service, and the only recorder |
| Pipeline | **scrcpy** — `ScrcpyClient` packets, `ScrcpyAudioMuxer` finalising |
| Speaker turns | **absent**, as predicted: `No speaker turns for this recording (mono capture, or an older daemon)` |
| Channels | **stereo** — standalone produces mono, this does not |
| File | 76,922 bytes, 20.69 s, mean **-23.6 dB**; per channel **L -36.6 dB / R -20.7 dB** |
| Services | none running in Shizuku mode |

Two things worth keeping. **Shizuku's output is stereo where standalone's is mono**, so the per-channel
check only works on this path — on a standalone carrier recording there is nothing to split. And the
"no speaker turns" line names *mono capture* as the reason, which is misleading here: the capture is
stereo, the reason is that the scrcpy path never exposes the raw channels to `SpeakerTurnDetector`.

**The bug this row was written to catch, caught twice.** Switching into Shizuku left the phone being
served by our own ADB daemon — the mirror image of yesterday's teardown bug:

```
11:46:49.893  keep-alive: binder-death signal — relaunching immediately
11:46:49.939  Privileged mode is now SHIZUKU
11:46:50.006  Attempt 1: launching recorder daemon
11:46:50.302  Shizuku started the recorder service
11:46:50.547  Clearing 2 other recorder process(es)      <- our daemon killed Shizuku's
```

The keep-alive relaunches on confirmed binder death, and a mode switch causes exactly that death on
purpose. The death signal beat the mode change by **46 ms**, so every mode-aware guard downstream was
too late. The first fix — stopping the keep-alive before the teardown instead of after — was not enough,
because `stopService` only *asks*: the service lives until `onDestroy`, and the death lands in that
window. What closed it was clearing `RecorderConnection.onDeath` **synchronously** inside `stop()`.

Neither would have been found by a process check. B1/B2 both passed throughout — one recorder was alive
each time. It was the wrong one.

**Z8 — standalone after a full Shizuku round trip, then a cell call. PASS (2026-08-25).**

The direct regression test for the teardown fix, and the answer is unambiguous.

| Check | Result |
|---|---|
| Pipeline | **handoff** — `HandoffEncoder finished: 19.384s` |
| scrcpy mentions in the whole log | **0** — the line that must never appear here |
| Speaker turns | **back** — `came from the handoff capture` |
| Channels | mono, the standalone shape (Shizuku's is stereo) |

So a standalone call after a round trip really is served by our own daemon. Both switch directions are
now proven by a call rather than a process count.

*The near-side clip did not transmit on this particular call — the middle of the file is quiet at any
sensible threshold, most likely a missed speaker toggle in the harness. The maintainer placed a separate
call in the same configuration immediately afterwards (mean -31.3 dB) and confirmed both sides audible,
which is what settles the audio half of this row.*

**A measurement lesson worth keeping.** The silence check used a fixed `-35 dB` threshold — but these
recordings range from -19 dB (VoIP) to -42 dB (handoff) mean, so on the quiet paths ordinary speech sits
below the threshold and reads as silence. The check now derives its threshold from each file's own mean
(12 dB under it, floored at -45 dB). A false "the near side is missing" is worse than no check at all: it
sends you hunting a capture bug that was never there.

**Gate rows S10, S12, S7 — all PASS (2026-08-25).** Turning one thing off must turn off *only* that thing.

| Row | Configuration | Expected | Result | The log's reason |
|---|---|---|---|---|
| S10 | carrier recording **off** | no recording | ✅ 40 → 40 | `Phone-call recording is off — ignoring this OUTGOING call entirely` |
| S12 | auto-record outgoing **off** | no recording | ✅ 40 → 40 | `Auto-record for outgoing call is disabled` |
| S7 | VoIP recording **off**, *carrier* call | **must record** | ✅ 40 → 41 | `Sending start INTENT for OUTGOING call` |

S7 is the control and the point of the whole group: switching VoIP off must not take carrier recording
with it. It did not.

Worth noting the precedence visible in S10: the log says `Auto-record is enabled for this outgoing call`
and *then* `Phone-call recording is off — ignoring this OUTGOING call entirely`. The master switch is
evaluated after the per-direction one and correctly overrides it, which is the order the settings screen
implies but the code was free to get wrong.

Each switch was restored immediately after its row, and the final state matches the starting state plus
the one recording S7 was supposed to make.

**Shizuku gate rows Z3, Z4 — PASS (2026-08-25), plus two checks that come for free.**

| Row | Configuration | Expected | Result | Host during the call |
|---|---|---|---|---|
| Z3 | carrier recording **off**, Shizuku | no recording | ✅ 41 → 41 | `shizuku(729)` |
| Z4 | auto-record outgoing **off**, Shizuku | no recording | ✅ 41 → 41 | `shizuku(729)` |

The gates behave identically in both modes and give the same reasons — `Phone-call recording is off —
ignoring this OUTGOING call entirely` and `Auto-record for outgoing call is disabled`. That is the
answer to "does the mode change how the filters behave?": it does not.

Two things this run confirmed without needing rows of their own:

- **The switch landed on the right host and stayed there.** `shizuku(729)` before, during and after both
  calls, and `daemon(8508)` after switching back. The resurrection bug fixed earlier today has not
  returned across four more switches.
- **The VoIP switch reads `off disabled` in Shizuku mode** — greyed *and* off, live on the device rather
  than inferred from a screenshot. That is the capability gating doing its job, and it is most of
  row Z2: the switch cannot be turned on, so nothing can arm.

The round trip also restored all three switches again: `Turned back on (supported again in STANDALONE):
RESILIENT_RECORDING, VOIP_RECORDING, OFFLINE_RECORDING`.

**S14 — audio source `Voice communication mic`. RECORDS, BUT BADLY (2026-08-25). The most useful row so far.**

Both sides are present — the maintainer could hear them — but only "bits and pieces, just barely". As a
call recording it is not usable. Measured against the same call on the default source:

| | `Voice call` (S1) | `Voice communication mic` (S14) |
|---|---|---|
| PCM streamed | 3,942,912 bytes | **1,931,520** — exactly half |
| Capture channels | 2 → encoded mono | **1** |
| Speaker turns | produced | **none** — `No speaker turns for this recording` |
| Mean level | -42.1 dB | **-51.7 dB** |
| Audible? | both sides, clearly | both sides, barely |

Two things follow, and neither is written down anywhere in the app.

1. **Choosing this source silently costs speaker attribution.** The capture is genuinely mono — half the
   PCM bytes — so `SpeakerTurnDetector` has nothing to separate. The row's own description mentions only
   echo cancellation. This is the same shape as Shizuku's missing speaker turns, except it is reachable
   from a dropdown in standalone mode, in the mode where the feature is supposed to work.
2. **It is a fallback, not a peer.** The picker presents four sources as equal choices. On this device
   one of them produces a barely-audible recording with a feature silently removed. It exists because
   `voice-call` is unavailable on some OEMs, which is a good reason to keep it — and a good reason to
   say so where it is chosen.

**Two sources are not in this matrix and should be:** `Voice call uplink` and `Voice call downlink`
record one side *by design*. Worth rows of their own precisely because "only one side" is the correct
outcome there, and any future check that treats a one-sided file as a failure would be wrong about them.

*Restored to `Voice call` after the row.*

**S6 — VoIP over the loopback transport. VOID, not failed (2026-08-25) — and worth writing down anyway.**

A real 19-second WhatsApp call, both sides speaking, was **not recorded**. It looked exactly like a
serious bug — offline recording had just been enabled, and the obvious story was "the loopback transport
breaks VoIP". The timestamps say otherwise:

```
12:53:00.406  setMode mode=3 from com.whatsapp     <- the call starts
12:53:19.592  setMode mode=0 from com.whatsapp     <- the call ends, 19s later
12:53:28.899  RecorderBinderProvider onCreate      <- CallVault's process starts
12:53:33.663  RecorderServer starting
12:53:34.254  armVoipCapture -> true
```

**CallVault was not running during the call.** An APK had been installed a minute earlier, and `adb
install` force-stops the app; nothing brought it back until nine seconds *after* the call ended. So the
row proves nothing about offline mode and has to be re-run.

Three things are worth keeping from a void row:

1. **A missed VoIP call is unrecoverable and, here, entirely silent.** No notification fired — and could
   not have, because the app was not there to notice a call had happened. The miss notice can only
   cover the case where the app is alive and the *daemon* is missing.
2. **The window after an update is real, if narrow.** A user updating the app and receiving an app call
   in the next minute loses it the same way. Worth knowing before dismissing this as a harness artifact.
3. **The harness now refuses to invite a call until the app process and a recorder are both up** for
   three consecutive checks. Without that, "nothing recorded" is ambiguous — and the ambiguity points at
   the product first, which is the wrong place to start looking.

**S6 — VoIP over the loopback transport (re-run). PASS (2026-08-25).**

| What | Result |
|---|---|
| Detection | `VoIP call detected (mode=IN_COMMUNICATION)`, app resolved to uid 10207 on attempt 1 |
| Pipeline | `startVoipRecording` → `VoIP capture started: rate=48000` |
| Transport | offline/loopback, **wireless debugging 0 throughout** |
| Far side | `farPartyHeard=true` |
| File | 64,775 bytes, mono opus, 16.08 s, mean **-19.3 dB** |

**VoIP recording works over the loopback transport with wireless debugging off** — the last combination
that had not been shown to work.

**The near-side dropout rate is now measured twice and it is not a fluke:**

| Row | Transport | Call length | "near capture silenced" | Rate |
|---|---|---|---|---|
| S2 | wireless debugging | 19 s | 7 | 0.37/s |
| S6 | loopback | 16 s | 8 | 0.50/s |

The platform takes the microphone away from our near-side capture roughly every two to three seconds,
and the recovery re-takes it each time. The transport makes no difference, so this is a property of the
VoIP capture path itself, not of how the daemon is reached. Both files sound fine, and no silence span
reaches 1.5 s — but on a long call this is the thing most likely to degrade, and it is now the single
best-evidenced open question in the VoIP path.

### Mode switching — the rows that need a call, not just a process check

B1/B2 proved a switch leaves exactly one recorder alive. They did **not** prove the next call goes
through the right one, and that is precisely the shape yesterday's bug took: the app sat in standalone
mode, reported "Ready — using CallVault", and recorded through a leftover Shizuku host. Every layer was
happy. Only a real call names the pipeline.

| # | Do this | Then | Must see |
|---|---|---|---|
| **Z1** | switch standalone → **Shizuku** | cell call | scrcpy in the log, **no** handoff, **no** speaker turns |
| **Z8** | switch Shizuku → **standalone** | cell call | handoff *or* direct, **never** scrcpy, speaker turns back |
| **Z7** | switch Shizuku → **standalone** | VoIP call | `armVoipCapture` true again |

Z8 is the direct regression test for the teardown fix. A pass means the standalone call after a round
trip really is served by our own daemon; a scrcpy line there would mean the old host survived again.

The switch itself should also be visibly honest each time: the modal names the mode it ended in, the
Home pill agrees, and the capability reconcile turns the right switches off on the way in — and, since
this morning, back on on the way out.

### Shizuku

| # | Configuration | Call | Expect | Verify | |
|---|---|---|---|---|---|
| Z1 | everything the mode allows | cell | records via **scrcpy** | scrcpy in log, front clip present, **no** speaker turns | A |
| Z2 | same | VoIP | **no recording** — the mode cannot | no arming; the switch is greyed and off | Q |
| Z3 | carrier recording **off** | cell | not recorded | no file | Q |
| Z4 | auto-record outgoing **off** | cell (out) | not recorded | no file | Q |
| Z5 | audio source `mic-voice-communication` | cell | records | source named in log | A |
| Z6 | everything on | cell (**in**) | records incoming | file present, two-sided | A |
| Z7 | after switching **back** to standalone | VoIP | arming restored | `armVoipCapture` true — the row yesterday's fix touched | A |

### Cost, honestly

**22 rows, of which 9 need real two-sided audio and 13 are quick connect-and-drop.** At roughly 20 s per
audio call and 10 s per quick one, that is about 8 minutes of call time — but the real cost is the
setting changes between rows, and there are 20-odd of those.

Suggested split, so a session ends somewhere useful:

- **Session 1 — the capture paths (S1, S2, S3, S5, S14, Z1, Z7).** Every row where the audio path itself
  differs. These are the ones that can silently produce a one-sided or empty file, which is the failure
  that matters. All need audio.
- **Session 2 — the gates (S7–S13, S15, Z2–Z4).** All quick. They prove that turning something off
  turns off *only* that thing, which is exactly where the mode work could have leaked.
- **Session 3 — incoming (S13, Z6)** needs the OP12 to originate, so it is a different setup.

## The call schedule — which real call settles which row

Four calls settle everything that automation cannot, and the order matters: the mode round trip has to
happen **before** the VoIP call, because that is the thing today's teardown fix changed.

Each call is placed from the OP9 to the OP12 by `spike-tools/callharness.sh`, which plays a ~5 s
near-side clip ("Testing, testing. One. Two. Three. This is OP9.") out of the OP9's speaker so its
microphone transmits a near side. The far side is a person on the OP12. Both then have to be present in
one file — that is D4, and it is the row that logs cannot answer.

### Call 1 — standalone, resilient recording OFF

Exercises the **direct AudioRecord** path, which is what v1.4.0 introduced to kill the front clip.

| Row | What it settles | How it is judged |
|---|---|---|
| D1 | The direct path actually runs | the log names it, not the handoff |
| D4 / I8 | Both sides are in the file | the clip AND the far voice are audible; `mean_volume` near -34 dB, not -73 |
| D9 | Speaker turns are produced | `Stored speaker turns` in the log |
| E1 | `CallMonitorService` starts and stops | `dumpsys activity services` during and after |
| E2 | `RecordingForegroundService` shows and stops cleanly | notification during the call, gone after |
| G3 | A non-Hebrew call transcribes | ← **speak English on this one** and it doubles as the 2.1.0 gate |

### Call 2 — standalone, resilient recording ON

Same phone, one switch different, and a different capture path entirely — this is the one the OP12 uses
by default, so it is not an edge case.

| Row | What it settles |
|---|---|
| D2 | The handoff path runs (`Handoff: startHandoff…` in the log) |
| D4 | Two-sided again — the handoff encoder is a separate writer and has produced 0-byte files before |
| D12 | The daemon is killed **mid-call** from adb; the recording must survive and finish |

### Call 3 — Shizuku mode

| Row | What it settles |
|---|---|
| D3 | Capture goes through scrcpy and produces a file |
| D4 | Two-sided under scrcpy |
| D10 | Speaker turns are absent **and nothing tells the user** — confirms the open gap rather than fixing it |
| — | The front clip returns here; the first word of the clip may be missing, which is expected |

### Call 4 — back to standalone, then a WhatsApp call

**Do the mode switch back to standalone first, then place the VoIP call.** VoIP arming happens on a
daemon binder, and today's fix changed which binder that is after a round trip.

| Row | What it settles |
|---|---|
| D6 | VoIP arming survives a mode round trip — the highest-value row on the list today |
| D5 | VoIP recording works in standalone |
| D4 | Two-sided; VoIP files are stereo, so the per-channel numbers apply here |

### After the calls — no further calls needed

These all run on the recordings the four calls produced:

| Row | What |
|---|---|
| G1 | Hebrew transcription produces segments |
| G2 | The pinned language beats auto-detect |
| G5 | Summaries generate |
| G7 | Search finds a word inside a transcript |
| F1 / F2 | The transcription and summary workers ran |
| H4 | The playback screen opens, the waveform draws, notes persist |

### Not covered by these four, and why

- **G4 (a call over 15 minutes)** — an endurance run; it needs time, not coordination. Do it whenever.
- **E7 / I7 (boot behaviour in both modes)** — needs a reboot, and Shizuku must be restarted by hand
  afterwards.
- **H3 (Drive sync)** — needs an account round trip, unrelated to calls.
- **E5, H9 (pairing and onboarding)** — would mean resetting a working phone.

### What only the maintainer can settle

A real two-sided carrier call in each mode, with the audio **listened to** — a lost far side is invisible
in logs and in the waveform. Plus the two 2.1.0 gate items that were already outstanding: one long call
(>15 min) and one non-Hebrew call.
