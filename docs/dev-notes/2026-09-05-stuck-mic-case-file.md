# Stuck microphone & truncated recordings — the case file

**Date:** 2026-09-05. **Status: ⏸️ WAITING ON EVIDENCE, by decision.**

This supersedes `2026-09-03-shell-mic-open-first-evidence.md`, which grew by accretion across a week
and is now hard to read in order. That file is kept for its working, but **this is the current
account.** Read this first.

**Decision, 2026-09-05:** we build nothing further on this until the tester sends a report taken
*while the microphone indicator is stuck*. We can now see the cause when that happens, and we have
sent this tester seven builds already. A mitigation was designed and deliberately **not** built —
see "Deliberately not built".

---

## 1. What is actually being reported

Two symptoms, on one tester's phone. **It is not established that they are one bug.**

| # | Symptom | Status |
|---|---|---|
| A | A call records ~1 second and the rest is missing | Root cause **confirmed** |
| B | The green microphone indicator stays on after the call | Holder **confirmed**, cause **unknown** |

**Device:** OnePlus 13 (`CPH2653`, `OP5D55L1`), Android 16 / OxygenOS 16, ROM
`CPH2653_16.0.10.501(EX01)`. STANDALONE mode, **Resilient recording (handoff) ON**, VoIP recording on.
**Our devices:** OnePlus 12 (`CPH2581`) and OnePlus 9 Pro. Neither has ever reproduced either symptom.

---

## 2. Symptom A — root cause CONFIRMED

From the tester's report (30), on `2.3.0-rearm1`, in our own log, in words:

```
11:04:54.512 [I] drain segment: streamed=241920B elapsed=1279ms dropped=0frames/0overruns
11:04:54.513 [E] handoff drain ended EARLY after 48000Hz capture:
                 TRACK INVALIDATED by AudioFlinger (CBLK_INVALID) — capture torn down mid-call
```

**AudioFlinger tears our record track down 1.28 seconds into the call.** A 25-second call produced a
2051-byte file. `dropped=0 frames / 0 overruns` matters: we were keeping up perfectly. This is the
platform taking the track away, not us falling behind.

Previously this was established only by elimination. **It is now observed. Do not re-derive it.**

It is intermittent — two other calls in the same report drained normally (76 s and 504 s).

### Why it breaks the recording rather than healing

Resilient recording ("handoff") is the reason this is visible at all:

- **Without it**, the daemon owns the `AudioRecord` and reads from it normally. AOSP's own
  `AudioRecord::restoreRecord_l()` sees `CBLK_INVALID`, recreates the track, preserves position and
  retries — transparently. The user never knows.
- **With it**, we drain the shared control block directly in native code, which is precisely how the
  recording survives the daemon dying. That bypasses AOSP's recovery too, so we had to write our own.

So the feature does not *cause* the teardown; it removes the platform's automatic repair. **Turning
Resilient recording off is a valid fallback** for an affected user, at the cost of the feature.

---

## 3. Symptom B — the holder is CONFIRMED, the cause is not

The tester, 2026-09-05, asked whether a stuck indicator normally needs a restart:

> "Exactly. Sometimes several forced shutdowns of the process occur, but very rarely. This time the
> update closed it without any further action."

Installing an update kills and relaunches the shell-uid helper. That cleared the indicator with no
other action. So **the helper process holds the microphone**, and stopping it releases it.

This agrees with the ledger. Across report (30), captures #1–#8 — carrier calls and VoIP calls alike
— each log open → release → `no capture left open in this process`, **including on the failed call**:

```
11:05:18.058 [I] IAudioRecord.stop accepted from the app (delivered=true)
11:05:18.104 [I] capture#4 released ... (still live: 0)
11:05:18.104 [I] after releasing the handoff capture: no capture left open in this process
```

So `a5efcd8` (stop-before-release) is doing its job, and **nothing our audit can see is leaking.**
Whatever holds the microphone is outside what that ledger covers.

⚠️ **Unresolved and important:** we do not know whether B happens on the same calls as A. Report (30)
captured A. We have never had a report taken while B was visible. That is exactly what we are now
waiting for.

---

## 4. Our own bugs found along the way — both fixed

### 4a. Mid-call recovery could never work (`4e2ad94`)

The re-arm built to survive Symptom A **failed 100% of the time**, on any device.

`rebuild()` asked the daemon for a replacement capture and only *then* began waiting for it. But the
daemon delivers **inside** that request, and the rendezvous was a `SynchronousQueue`, which completes
a hand-off only if a receiver is already waiting. The receiver was the thread still blocked in the
request.

The timestamps show both four-second timeouts running **in sequence, never overlapping**:

| moment | time | arithmetic |
|---|---|---|
| daemon begins delivering | 11:04:55.009 | — |
| delivery gives up | 11:04:59.013 | 55.013 **+ 4000 ms** |
| request returns; receiver starts waiting | 11:04:59.018 | — |
| receiver gives up | 11:05:03.018 | 59.018 **+ 4000 ms** |

A **healthy replacement capture was built and closed four seconds later** for want of a receiver
(`capture#4 opened ... ping=true`). Raising the timeout could never have helped.

Fixed with a one-place parking slot (`HandoffSlot`, 7 unit tests) so a capture can be put down before
anyone is there to collect it. **Never exercised end to end on real hardware** — see §7.

### 4b. The system report silently failed (`81676a4`) — ✅ VERIFIED by the tester

This is why six rounds produced no evidence, and it was our bug, not his setup.

The system half of a report ran seven ADB commands, each able to force a reconnect, behind the share
screen's 45-second budget. When the budget ran out the app shared the debug report alone and **said
nothing**. He received one file and reasonably reported that the app "doesn't produce it".

**Reproduced on our OP12** by matching his transport state exactly (`pm revoke
WRITE_SECURE_SETTINGS`, Wireless debugging off): a long modal, then one file.

Fixed by collecting over the binder the app already has to the daemon, which is already the shell
user — no Wireless debugging, no `WRITE_SECURE_SETTINGS`, no transport, no retries, no budget. A
whitelist (`DiagnosticDumps`), not a command runner. ADB remains the fallback.

**Confirmed working on the tester's own phone**, 2026-09-05, on `2.3.0-diagbinder`.

---

## 5. Ruled out — do not re-propose

| Theory | Verdict |
|---|---|
| `REMOTE_SUBMIX` captures leaking | **False.** They can never emit a `rec stop`, so they always look open. Our own healthy OP12 shows five. Not evidence of anything. |
| Audio route changes trigger the teardown | **Tested and disproven**, 2026-09-05. A real switch to speaker landed **2.0 s** into capture on the OP12 against his 1.28 s, and the recording came out whole: `37.1s encoded of 37.1s captured`. The maintainer said first that they switch outputs constantly without trouble; they were right. |
| The app process leaks an `AudioRecord` | **No.** Captures #1–#8 all released, ledger clean, including on the failed call. |
| The `silenced` mechanism | **No.** A silenced track still advances the ring, so it would produce silent audio, not an early end. |
| `cmd appops stop` clears it | **No.** It passes system_server's token; a silent no-op for an op started by audioserver. |
| `accepted=true` proves the track stopped | **No.** It only means the binder transaction was delivered. `RecordHandle::stop()` returns OK unconditionally. |
| Raising `REARM_WAIT_MS` | **Cannot help.** The two windows are sequential, so a longer timeout only fails more slowly. |

---

## 6. The reason this took seven rounds

Worth writing down, because the cause is structural and will repeat.

1. **We asked instead of reproducing.** Both of our own bugs were reproducible on our hardware in
   minutes once we tried. 4a needed no special device at all — it failed every time, anywhere, the
   moment recovery ran. We simply never ran that path.
2. **Our test phones are healthier than any user's.** We re-grant `WRITE_SECURE_SETTINGS` over the
   cable on every install. Real users lose it on every update and never get it back. Our devices
   *cannot* show us this class of failure unless we deliberately degrade them.
3. **The evidence channel itself was broken** (4b), so every round produced a half-report and we
   asked for another.
4. **logcat is not a foundation here.** Measured on the OP12: the 256 KiB ring sat at 252 KiB
   consumed, nearly all ROM spam (`OsenseCommonUtils`). Our lines rotate out within seconds.

---

## 7. Open questions

1. **Are A and B the same event?** Unknown. Never observed together.
2. **What triggers `CBLK_INVALID` on his device?** Unknown, and unreproduced by us. Route changes are
   excluded. Remaining candidates: something in his ROM build, his carrier/VoLTE path, another app,
   or the timing of capture setup on that hardware.
3. **Does the fixed recovery produce a *whole* recording?** Unknown. The rendezvous fix is unit-tested
   and the replacement capture was healthy before we discarded it, but the full path has never run on
   real hardware. It is in `2.3.0-rearm2` / `2.3.0-diagbinder`, which he now has.
4. **Which process and which op holds the microphone when stuck?** Now answerable — see §8.

---

## 8. What to do when the next report arrives

The tester has `2.3.0-diagbinder`, which contains both fixes and produces a complete report.

**Ask for both files, taken while the indicator is stuck.** The system report is the one that
matters; it is the only place the microphone app-op appears.

Then, in order:

1. **"Microphone app-op" section** — names the uid and package holding `RECORD_AUDIO`, with
   `Running start at:`. uid 2000 / `com.android.shell` is our helper. This is the answer to §7.4.
2. **"Recorder processes"** — more than one is an orphan that outlived its replacement.
3. **Capture ledger** in the debug report — if it is clean while the op is held, the holder is inside
   the daemon and not visible to our audit, which points at the daemon's own `AudioRecord`.
4. **`drain ended` line** — did `CBLK_INVALID` happen on that call, or is B independent of A?
5. **If it did**: did `re-armed capture parked for the supervisor to collect` appear, and did the
   recording come out full length? That answers §7.3 and is the first real test of `4e2ad94`.

---

## 9. Deliberately not built

A mitigation was designed and rejected for now, by decision on 2026-09-05.

Because stopping the helper releases the microphone (§3), the app could do deliberately what the
update did by accident: notice the microphone is still held after a call, and restart the helper.
That would remove the symptom the user actually notices, without knowing the cause.

**Not built, on purpose.** It would mask the one signal we have just gained the ability to see, and we
have sent this tester seven builds already. The evidence comes first.

If it is built later: never restart during a call (check the audio mode, not just `mCallState`), and
pair the automatic path with a manual action for the cases detection misses.

---

## 10. Conclusion

We know **what** breaks symptom A and it is not our code: AudioFlinger tears the record track down
about a second into some calls on this device. We know why it ruins the recording rather than healing
— Resilient recording bypasses the platform's own repair — and turning that feature off is a genuine
fallback for an affected user.

We know **where** symptom B lives: the shell helper process holds the microphone, and stopping it
releases it. We do not know why it is held, and nothing in the app's own accounting explains it.

Both of the bugs that actually cost this tester seven rounds were **ours**, and both were
reproducible on our own hardware once we bothered to try. They are fixed, and the second one is
confirmed fixed on his phone.

What remains unproven is the thing that matters most: **whether recovery now produces a whole
recording.** That cannot be settled by reasoning, only by the next affected call — which, for the
first time in this investigation, will produce a report that says so.

---

## 2026-09-07 — SOLVED (pending the tester's confirmation): the leak is the rebuild path

**🧪 VERIFYING.** The report this file was waiting for arrived, taken while the indicator was stuck,
and it names both the holder and the cause.

### What the report says

System report, while stuck:

```
MICROPHONE HELD: 1 running mic app-op(s), 1 of them as uid 2000 (shell)
    uid=2000 pack=com.android.shell op=RECORD_AUDIO running since +5m4s276ms
Recorder processes: shell 9767 app_process ... RecorderServer
--- Microphone activity --- No capture was left open. Every pairable recording that started also stopped.
```

No live record track; the **app-op alone** is stuck. Debug log, same phone (2.3.0-diagbinder):

```
10:51:25.694  capture#1 opened (handoff held record)
10:51:26.486  handoff drain ended EARLY — TRACK INVALIDATED by AudioFlinger (CBLK_INVALID)
10:51:26.999  capture#2 opened            ← the rebuild
10:55:42.53   handoff track stop() before release: accepted=true (finishes the mic app-op)
10:55:42.54   capture#2 released — no capture left open in this process
```

`+5m4s` before the report is **10:51:25** — capture#1. The op that is stuck belongs to the capture
AudioFlinger invalidated, not to the recording that just ended.

### The cause, and it is ours

`HandoffReceiver`'s rebuild overwrote `held` with the replacement binder and dropped the old one
**without stopping it**. `releaseRefs()` already documents why that leaks: releasing the last reference
runs `AudioPolicyService::releaseInput()`, which clears the client without `finishRecording()` — only
`stopInput()` finishes the app-op. The end-of-call path was fixed for exactly this; the rebuild path
reintroduced it.

Fixed by stopping the invalidated track before letting go of it, and forcing the reference collection,
mirroring `releaseRefs()`. **The result is logged (`accepted=`)** because whether AudioFlinger accepts a
stop on an already-invalidated track cannot be known from here — the next report answers it.

### Two open questions from this file, now answered

- **"Are the truncation and the stuck mic the same event?"** Same trigger, different outcomes.
  `CBLK_INVALID` causes both: a failed rebuild truncates the recording, a successful rebuild leaves a
  stuck dot. That is why they had never been seen together.
- **`4e2ad94` mid-call recovery, "still unproven end to end on real hardware":** proven. Invalidated at
  +0.79 s, rebuilt at +1.3 s, and the call came back whole — 256.0 s encoded of 256.7 s captured. The
  tester's recording is fine; only the indicator was wrong.

### Also seen in the same report, unrelated

`WRITE_SECURE_SETTINGS: false` on his phone, and the self-heal failed with `Stream closed`.

### How to read the NEXT report (2026-09-07, corrected by the maintainer)

**An update clearing the dot is not a fix, and it is recent.** Installing a build now kills the old
daemon process, and the app-op dies with it — but that only started with the last two builds, because
`PostUpdateRecovery` (and the stale-daemon kill) is what finally replaces that process reliably. Before
those, the tester had to kill the shell process by hand after every install. So earlier rounds were
**not** masked by our own APKs; those reports were sound.

Consequences for the rc14 round:

1. The dot vanishing when he installs rc14 says nothing — that is the daemon being replaced.
2. Only a **recurrence on rc14** is evidence, and only from a call whose log shows a rebuild
   (`handoff drain ended EARLY … CBLK_INVALID` followed by `capture#N opened`).
3. The line that decides it is `invalidated track stop() before rebuild: accepted=`.
   - `accepted=true` and no stuck dot → fixed.
   - `accepted=true` and the dot sticks → AudioFlinger takes the stop but does not finish the op; a
     different lever is needed.
   - `accepted=false` → it refuses to stop an invalidated track.

**The fallback is already proven in the field.** Killing the daemon process clears the dot every time —
that is exactly what an update does. So if the stop is refused, restarting the daemon when a stuck op is
detected after a call (`MicOpReport` already parses `dumpsys appops` for it) will work. It was withheld
from the tester's build on purpose: shipping it now would mask the one signal rc14 exists to produce.


---

## 2026-09-11 — ❌ NOT WORKING: rc14's stop does not release the torn-down capture

**The recurrence rc14 was built to catch.** Tester's OnePlus 13 (CPH2653, Android 16), **2.3.0-rc14 (20342)**,
resilient recording on, `WRITE_SECURE_SETTINGS: false`. Reports generated 2026-09-11 12:50:56.

System report, while stuck:

```
MICROPHONE HELD: 1 running mic app-op(s), 1 of them as uid 2000 (shell)
    uid=2000 pack=com.android.shell op=RECORD_AUDIO running since +51m53s658ms
No capture was left open. Every pairable recording that started also stopped.
1 recorder process running (pid 19317)
```

The dump ran at ~12:50:55.6, so the op has been running since **~11:59:02** — the 11:59 outgoing call:

```
11:59:02.617  capture#29 opened (handoff held record)
11:59:03.051  handoff drain ended EARLY — TRACK INVALIDATED by AudioFlinger (CBLK_INVALID)
11:59:03.052  HandoffSource: releasing the previous held record before re-arming      ← daemon side
11:59:03.487  capture#29 released
11:59:03.522  capture#30 opened                                                        ← the rebuild
11:59:03.528  invalidated track stop() before rebuild: accepted=true (finishes the mic app-op)
11:59:03.610  handoff capture input released (forced collection of the IAudioRecord ref)
11:59:23.875  handoff track stop() before release: accepted=true
11:59:23.880  capture#30 released — no capture left open in this process
```

The 12:48 call repeated the pattern (capture#31 invalidated after 25 ms, rebuilt as #32, `accepted=true` twice)
and may have added a second hold behind the older timestamp; `dumpsys appops` reports a single "running since".

**Verdict, by this file's own table:** `accepted=true` **and** the dot sticks → AudioFlinger accepts the stop
on an invalidated track but does not finish the op. Every lever available on that track was pulled — the
app stopped it, forced the reference collection, and the daemon released its own `AudioRecord` — and the op
still survived. rc14's change is correct hygiene but does not fix the dot. It stays in; it is not the fix.

**What is proven to work:** killing the daemon process clears the op every time (every update since
`PostUpdateRecovery` has shown it). That is the fallback §8 held back so rc14's signal would be readable. The
signal is now read.

**Next:** the daemon-restart auto-heal — after a call ends, if the mic app-op for uid 2000 is still running with
no capture live, replace the daemon. Design questions open before building: where the `dumpsys appops` read runs
(it needs shell privileges), what the restart costs on a phone with `WRITE_SECURE_SETTINGS: false`, and how to
avoid restarting while the next call is starting.

## 2026-09-11 — the daemon-restart auto-heal, built — 🧪 VERIFYING

`MicOpAutoHeal` + `MicOpHealPolicy`, hooked where a carrier recording and a VoIP recording end. Eight seconds after
the call it reads the shell uid's mic ops through the daemon (`diagnosticDump("appops_mic")`) and, if one is running
with nothing recording, calls the daemon's `destroy()`; the keep-alive relaunches on the binder death.

It will **not** act (and logs which rule stopped it): outside standalone mode; while a call or recording is up
(re-checked just before acting); when the keep-alive could not bring the daemon back — no Wireless debugging, no armed
loopback with adbd up, and no WRITE_SECURE_SETTINGS-plus-Wi-Fi; or within 10 minutes of a previous heal.

**The tester's phone matters here:** `WRITE_SECURE_SETTINGS: false` and Wireless debugging off, so the heal runs only
if his offline-recording loopback is armed. If it is not, the log says `NO_WAY_BACK` and the dot stays — on purpose.

**How to read the next report** — every line is tagged `CV:MicOpHeal`:

| Line | Meaning |
|---|---|
| `… 0 shell microphone op(s) running → NOTHING_HELD` | normal call, nothing stuck |
| `… → HEAL` then `daemon replaced; the microphone indicator is clear` | ✅ what this build is for |
| `… → NO_WAY_BACK` | stuck, but no safe way to relaunch on this phone; the dot stays by design |
| `daemon replaced but … still running` | the op belongs to something else running as shell |
| `the daemon is not back after 45000ms` | the relaunch failed — the most serious outcome; keep-alive keeps retrying |

**Regression call on rc20, OP12, 2026-09-11 14:28** (maintainer's call, log watched live): recording complete
(`12.9s encoded of 12.9s captured`, no invalidation, capture released, nothing left open), and 9 s after hang-up
`CV:MicOpHeal: after the carrier recording: 0 shell microphone op(s) running → NOTHING_HELD`. No errors, no daemon death.
The shared status notification held too: CallVault's notification entries stayed at Android's autogroup summary, the
debug-logging notice and id 4720 throughout, and afterwards only the keep-alive held 4720 ("Ready to record calls").
This clears the **no-regression** half only; the heal itself still waits on a stuck dot from the tester.
