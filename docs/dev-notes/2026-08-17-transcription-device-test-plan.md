# Transcription — everything that needs the phone, or needs you

**Purpose:** one place for every check that cannot be answered from the desk, so the phone is borrowed
**once**, at the end, rather than a dozen times. Nothing here blocks further coding — it accumulates
until we install a build on the OP12 and work through it.

**Status:** Plan 1 verified on device. Plans 2 / 2A / 3 have items waiting here.

> **Why batched:** the OP12 is a daily driver running a real release build that records real calls.
> Every session on it costs recording time and risks the setup. See
> `instrumented-tests-on-the-daily-driver` and `ask-before-assuming-phone-connected`.

---

## A. Decisions — ✅ ALL ANSWERED 2026-08-17

- [x] **A1. Language: auto-detect, with manual override.** Shipped as built — auto-detect is the
  default and the Language dropdown pins Hebrew (or five others) when wanted. No change was needed.

- [x] **A2. Default tier: Best (574 MB).** Changed in `1a759af`. Fast produces Hebrew *gist*, Best
  produces clean Hebrew, and a transcript you cannot trust is not worth the battery it cost. Cost
  accepted: ~2.2× real time, so a 30-minute call takes about 65 minutes.

- [x] **A3. Silent, but with a progress pill beside the Home title.** Same slot and shape as the
  existing `SupportPill` (`HomeScreen.kt:212`), visible **only while running**, showing position in
  the backlog, tapping through to a sheet with the current call and a Cancel button. → Plan 3, Task 6.

- [x] **A4. Calls per run is configurable** — 5 / 10 / 25 / 50 / No limit, default 25. Shipped in
  `1a759af`.

- [x] ~~**A5. Detect the channel mapping automatically from ringback**~~ — **SUPERSEDED 2026-08-24.**
  The ringback never reaches the capture on the reference phone (silence through the whole ringing
  phase), and the alternative measurement — a second `VOICE_DOWNLINK` capture — costs the recording
  its near side. Both detectors are deleted. What ships: a convention (on a call you placed, the
  first voice is whoever answered), corroborated across two calls, and above all the user's own
  answer, which the transcript asks for outright. → see B7 below.

  **VoIP needs no calibration at all**: `VoipCaptureSession.kt:40` interleaves the two streams
  itself — *"LEFT near, RIGHT far"* — so for app calls the mapping is known by construction. Only
  carrier calls are ambiguous, because Android documents `VOICE_CALL` as "uplink + downlink" without
  ever specifying the channel order, leaving it an OEM decision.

---

## B. On-device tests

### How to install

`adb install -r` **preserves** the `WRITE_SECURE_SETTINGS` grant (the installer is shell-privileged),
so installing over your release build does **not** break recording — unlike the Obtainium path. See
`reinstall-drops-write-secure-settings`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SERIAL=6011b07e
./gradlew assembleDebug         # or assembleRelease for a true shipping check
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Use a release build for anything about speed.** Debug builds compile whisper at `-O0` unless the
> `-O3` line in `app/src/main/cpp/CMakeLists.txt` is present — that line is what took a 47 s clip from
> 10m26s to 49 s. A timing measured on a build missing it is meaningless.

### B1. Model download — Plan 2, Task 2

- [ ] Settings → Transcription → **Download model** over Wi-Fi. Completes and shows "Downloaded and ready".
- [ ] **Resume:** start the 574 MB Best download, turn Wi-Fi off part-way, turn it back on. It must
      *continue*, not restart. Check the file size climbs from where it stopped rather than from zero.
- [ ] **Mobile data is refused:** on mobile data only, the download does not start (unmetered constraint).
- [ ] **Delete model** frees the space and the row flips back to "Download model".
- [ ] **Corrupt file is rejected:** truncate the downloaded `.bin` on device, then trigger a
      transcription. It must fail cleanly, not crash the app — a bad ggml file crashes in native code,
      which is exactly what the digest gate exists to prevent.

### B2. Manual transcription end-to-end — Plans 2 + 3

- [ ] Transcribe one real Hebrew call. Output is **Hebrew script**, not Latin.
- [ ] Timings look right — the last segment lands near the call's real duration, not at 1/10th of it
      (that would mean the centisecond→millisecond conversion regressed).
- [ ] A 30-minute call takes roughly 30 minutes at Fast tier. Materially slower means the `-O3` line
      is missing from the build.
- [ ] **Quality judgement (yours):** is Fast tier good enough to be useful, or is Best needed? This
      settles A2.

### B3. Automatic mode — Plan 2, Tasks 5 + 6

- [ ] Set Automatic with a run time a few minutes ahead, put the phone on charge. The run fires.
- [ ] With **Only while charging** on and the phone unplugged, it does **not** run.
- [ ] Backlog drains **oldest first**.
- [ ] **Interrupt it:** force-stop the app mid-backlog. On the next run it resumes at the next
      recording rather than re-transcribing what was already done. This is the checkpoint, and it is
      the difference between an hour wasted and an hour saved.
- [ ] Switching back to **Manual** cancels the scheduled work (nothing runs at the chosen time next day).

### B4. Settings UI — Plan 2, Task 6

- [ ] The mode dropdown reads **Manually / Automatically**, and Hour / Minute / Only-while-charging
      appear **only** under Automatically.
- [ ] Model and Language remain visible in both modes.
- [ ] Check one non-English locale renders without clipping — the German and Russian strings are long.

### B5. Deletion and privacy — Plan 2, Task 1

The cascade is enforced by code rather than a foreign key, so it deserves a real check rather than
only the unit tests.

- [ ] Transcribe a call, delete the recording, confirm the transcript is gone and **searching for a
      word from it returns nothing**.
- [ ] Same via the **retention sweep** (the per-copy delete path, which is how expiring calls
      actually go). Set a short retention, let it expire, confirm no transcript survives.
- [ ] For a call synced to Drive: deleting **only the device copy** must *keep* the transcript, since
      the recording still exists.

**A hole found and closed on 2026-08-24, before any of the above was run.** The sweep has two halves:
it deletes catalogued copies through `RecordingsRepository.deleteFile` (which drops the row, which
cascades), and it deletes *untracked* files — ones the catalog has no row for — straight through SAF,
where nothing was dropped and so nothing cascaded. The transcript, the note and the summary outlived
the recording, searchable.

Reachable rather than theoretical: `RecordingDatabase` uses a **destructive migration**, so a single
catalog schema bump turns every existing recording untracked while the transcripts database — which
migrates properly — keeps every word of them. `RetentionSweepWorker` now cascades for the untracked
half too, but only for names *no copy of which is left anywhere* (`UntrackedCascade`, unit-tested):
untracked-ness is per folder, so a name can be untracked on the device and catalogued on Drive, and
taking that transcript would destroy the user's data rather than protect it.

The device check for it: transcribe a call, delete its **catalog** (clear the app's data is too blunt —
instead let a schema bump do it, or delete the row) so the file is untracked, set a short retention,
let the sweep run, then search for a word from the call.

**Do not run the sweep half of B5 on the daily driver.** The shortest retention preset is *Daily*, so
turning it on to force a sweep deletes every real recording older than a day — a far worse outcome than
the bug being tested. The sweep's catalogued half calls the same `RecordingsRepository.deleteFile` as
the in-app delete, so the in-app delete below exercises that code path; the untracked half is covered by
`UntrackedCascadeTest`. What is left for a device is the in-app delete and the search after it.

### B6. Survival — Plan 2, Task 1

- [ ] Transcripts survive an app **update** (install a newer build over the top).
- [ ] Transcripts survive the recordings catalog being rebuilt. This is the whole reason they live in
      their own database.

### B7. Speaker naming — Plan 2A

**Rewritten 2026-08-24.** This section used to test detection from the ringback tone. That detector
is deleted: on the reference phone the capture is silent through the entire ringing phase, so it
never worked there, and a second `VOICE_DOWNLINK` probe (the alternative measurement) costs the
recording its near side — see the hard constraint in the Plan 2A notes. What ships instead is a
convention plus the user's own answer, so that is what to test.

- [ ] After a call with speech from both people, the transcript shows **two sides** with a bar asking
      *Which one is you?* Tapping either label names both sides, on that transcript and every other.
- [ ] The names are **right**: your own words are attributed to you. You know who said what; the app
      does not.
- [ ] After answering, the bar is gone and **"Swap names"** is in the action row. Tapping it flips
      both sides and it stays flipped. *This is the escape hatch — a mis-tap on the bar must never be
      permanent.*
- [ ] Left unanswered across **two or three outgoing calls**, names appear on their own from the
      convention (on a call you placed, whoever speaks first is the person who answered), and are
      **stable** rather than flip-flopping between calls.
- [ ] A call where only **one** side is ever heard produces neutral "Speaker A / B" and no names —
      a wrong attribution is far worse than none, and a one-sided capture is what a broken recording
      looks like.
- [ ] **Incoming** calls reuse the mapping rather than guessing: the convention points the other way
      on an incoming call and is deliberately not read there.
- [ ] A transcript that came back as **one segment** shows no speaker names and **no bar**. Correct,
      not a bug: a line both people share belongs to neither. Worth knowing so it is not re-reported.
- [ ] **VoIP (WhatsApp): expect NO speaker names**, and confirm that is what happens rather than
      something worse. Checked in the code on 2026-08-24: `VoipCaptureSession` is a **third capture
      path** alongside the daemon's direct one and the app's handoff one, and it is the only one with
      no `SpeakerTurnDetector` in it — so an app call produces no turns and its transcript cannot be
      labelled.

      Galling, because VoIP is the *easy* case: the two directions arrive as separate streams and are
      interleaved LEFT near, RIGHT far, so the mapping is known by construction with nothing to infer.
      Adding the detector there is a small change; the real question it raises is that the trusted
      mapping is currently one global answer for the device, while VoIP's is fixed and the carrier's
      is an OEM detail — so the two cannot share a single value on a phone where they disagree.

      **Not a regression and not scheduled.** Logged here so it is not rediscovered as a bug.

### B8. Speaker track must not harm recording — Plan 2A

**The one that matters, and the only one a person can run: record a two-sided call and LISTEN to it.**
Can you hear *both* your own voice and the other person's? That is the whole test. The failure this
guards against has happened once already — the downlink probe silently took the near side off the
recording — and it was invisible in the logs, in the file size and in the waveform. Nothing but an ear
catches it.

- [ ] A two-sided call plays back with **both voices audible**.
- [ ] A call recorded with the capture tap has the **same codec, channel count and bit rate** as one
      recorded before it. If the audio changed at all, the tap is wrong. (Needs the two files side by
      side on a computer; not something to eyeball on the phone.)
- [ ] A fallback/mono capture produces no speaker data and **no error**.
- [ ] A warm daemon from the previous version produces no speaker data and **no error** (the AIDL
      method it does not have must not break a recording).

### B9. Transcript UI — Plan 3

- [ ] The row button walks **Transcribe → spinner → open**.
- [ ] The modal opens near full height and Hebrew renders **right-to-left**, with timestamps and
      speaker columns not colliding with the text.
- [ ] Tapping a segment **seeks playback** to that moment.
- [ ] Searching a word you know was spoken finds the call and jumps to it.
- [ ] A call with no speech shows **"no speech detected"**, not a blank sheet.

---

## C. Only answerable by living with it

Not pass/fail — things to notice over a few days.

- [ ] **Heat and battery** after an overnight backlog. Sustained all-core work on 190–574 MB models is
      the heaviest thing this app has ever done.
- [ ] **Storage**: 574 MB of model plus transcripts, against whatever headroom you keep.
- [ ] **Is auto mode actually wanted?** Once the backlog is done it only handles new calls; you may
      find manual is enough.

---

## Recording results

Fill in as we go, so a later session does not re-run what already passed.

All on the **OnePlus 12 (CPH2581, Android 16)**, 2026-08-20, release build 1.5.8/10721 built from
`worktree-feat+transcription-engine` (merged with v1.5.8 first, so the daemon-recovery fix was not
lost from the daily driver).

| test | result |
|---|---|
| B1 download | ✅ 574 MB Best model over Wi-Fi, row flips to "Downloaded and ready" |
| B1 resume | ✅ **proven**: JobScheduler killed and restarted the download at least twice, yet only 597 MB was received against a 574 MB file. A restart-from-zero would have cost ~1.7 GB. |
| B2 Hebrew script | ✅ full Hebrew transcript, right-to-left |
| B2 timings | ✅ timestamps progress linearly and reach 4:24 on a 5:13 call — the centisecond→millisecond conversion is intact |
| B2 speed | ✅ ~16 min for a 5:13 call ⇒ RTF ≈ 3, close to the predicted 2.16 for Best on this hardware |
| B2 auto-detect | ❌ **→ then fixed.** See below. |
| B3 Manual cancels the sweep | ✅ the TranscriptionWorker job leaves JobScheduler |
| B4 progressive disclosure | ✅ Hour / Minute / Calls-per-run / Only-while-charging appear only under "Automatically"; Model and Language stay in both |
| B4 long locales | ✅ German and Russian render without clipping; the charging subtitle wraps to three lines |
| B9 modal + RTL | ✅ near-full-height, Hebrew right-to-left, timestamp column does not collide with the text |
| B9 no-speech empty state | ✅ "No speech was recognised in this recording", not a blank sheet |
| Task 5 delete-transcript | ✅ error-tinted "Delete text" present in the sheet |
| Task 6 pill | ✅ appears while running, takes the slot from the support pill, single-item form (no "1/1"), taps through to a sheet with Stop |

### The bug this pass existed to find

**Every transcript came back empty, for every user, by default.**

`whispercv.cpp` set `params.detect_language = true` whenever the language was left on auto. Despite the
name that is not "please detect the language" — whisper.cpp treats it as *exit after detecting*:

```c
if (params.detect_language) { return 0; }   // whisper_full_with_state
```

so `whisper_full` returned success having produced **zero segments**. The runner stored that as a
completed transcript and the sheet said *"No speech was recognised in this recording"* — which reads as
a poor recording, not a defect. Auto-detect is the default, so this affected everything.

Measured either side of the fix on the same recording:

| language setting | time | result |
|---|---|---|
| Detect automatically (before) | ~40 s | zero segments |
| Hebrew, forced | ~16 min | full transcript |

Fixed in `e9858b5`: `params.language = "auto"` and `detect_language` left false — auto-detection already
happens for a null/empty/"auto" language, so the flag was never needed.

### Still not run

B5 (deletion cascade), B6 (survival across update/catalog rebuild), B7 (speaker mapping — needs real
outgoing calls), B8 (capture unchanged), B9 seek-on-tap and search-finds-a-spoken-word, and every item
in section C.

### Two smaller defects found

- **The in-app log viewer truncates every line** and cannot wrap or scroll sideways, so the log is
  unreadable on the phone. Since the OnePlus also suppresses this app's logcat, that left no way to read
  a log on-device — it cost most of an hour here, and it would defeat any bug report from a user.
- **The transcribing sheet showed the raw file name** instead of the contact name. Fixed in `e9858b5`.
