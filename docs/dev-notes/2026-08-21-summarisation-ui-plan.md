# Call summaries — UI plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the measured summarisation engine into something a user can actually use, without
promising more than the measurements support.

**Spec:** `docs/dev-notes/2026-08-21-summarisation-spike-plan.md` — read its Results and Verdict
first. Everything below assumes those numbers.

**Status (2026-08-23): Tasks 1–6 done, Task 7 all but the parts needing a second phone.** Shipped as
**2.0.0** — a major version, because it is the first release carrying transcription as well.
**662 unit tests, 0 failures**, 6 instrumented tests, all ten locales.

Task 4 was done **before** Task 3, because the worker needs a model path and paths come from the
catalogue — the plan's numbering was written before that dependency was visible.

**Summaries of real Hebrew calls now work on the maintainer's phone.** Everything below was found by
running it there, not by reasoning about it, and none of it would have been caught any other way.

| Found by running it | Fix |
|---|---|
| Summary came out in **English** for a Hebrew call | The prompt's fallback asked the model to infer the language. Pinned instead — see the language section below |
| A finished run **reverted to offering itself** | The card matched its job through `WorkInfo.progress`, which WorkManager clears on completion |
| **"Write it again" appeared to do nothing** | Two faults: an arbitrary tagged job was picked, and an appended job starts `BLOCKED` — a state the card did not count as work |
| The run that reverted had also **failed** | Token budget: six keys of Hebrew against a 420-token cap truncates, and a truncated document is refused |
| The first good summary **repeated itself badly** | The JSON prompt never said not to; only the unused prose prompt did. Instruction added *and* enforced |
| **Timestamps can be invented** | Verified against what the model was shown, and against the recording's length |

**Measured on a real 6:52 Hebrew call:** about five minutes, ~715% CPU, 3.0 GB resident, one chunk,
stored. That is slower than the spike's extrapolation and entirely usable as a background job.

---

## What the measurements dictate

These are not preferences. Each one closes off a design that would otherwise look reasonable.

| Measured | What it rules out |
|---|---|
| ~97 s for a real call, ~130 s with timestamps | Any "summarise" button that leaves the user watching a spinner |
| **3.0–3.5 GB peak PSS** | Running it while a call is being recorded, or on a phone that cannot spare it |
| 3.46 GB download | Shipping the model; it must be fetched, resumable, and deletable |
| Invents nothing on clean text, **reads noise as names on dirty text** | Presenting a summary as fact without the transcript one tap away |
| Quality is capped by the transcript | Offering summaries for recordings that have no transcript yet |

**The last one is the strongest constraint and the easiest to get wrong.** A summary of a bad
transcript is a bad summary, and the user cannot tell which they are looking at unless the transcript
is right there.

---

## The shape

**A summary is part of a recording, not a place of its own.** It goes on the playback screen, above
the transcript row, because it answers the question the transcript answers more slowly.

```
┌─────────────────────────────────────┐
│ ↗  גבריאל 2b    Yesterday, 15:57  📄🗑│   header (exists)
├─────────────────────────────────────┤
│ ▁▃▅▂▇▃▁  waveform, controls, speed  │   player (exists)
├─────────────────────────────────────┤
│ ✨ Summary                          │   NEW
│ השיחה התמקדה בדיון על פיטורי עובדת…│
│                                     │
│ Decisions                           │
│  • [1:30] מיגרציה של הפיירוול       │   ← tappable, seeks
│ To do                               │
│  • [2:15] לחזור ללקוח עד יום חמישי  │
│                                     │
│ Generated from the transcript ·  ⟳  │   provenance + redo
├─────────────────────────────────────┤
│ 📄 Read the transcript              │   (exists)
├─────────────────────────────────────┤
│ ✎ Note                              │   (exists)
└─────────────────────────────────────┘
```

**Why sections rather than a paragraph.** The structured prompt returns intent, decisions and action
items separately, and those are what someone returns to a call for. A paragraph buries them.

**Why the timestamps are the point.** `[1:30]` next to a decision is a jump into the call. The
transcript sheet already seeks on tap; a summary made of jump points is a table of contents for a
conversation, and it is the one thing this feature can offer that reading the transcript cannot.

**Why "Generated from the transcript" is not optional.** It is the honest label for a machine's
reading of a machine's transcription of a call. It also explains, without an apology, why a summary
can be wrong — and it is the affordance that sends someone to the transcript when it looks off.

---

## What NOT to build

- **No summary in the recordings list.** The list is already tight; the name barely fits. A summary
  belongs where someone has decided to deal with the call.
- **No automatic summarisation of the back catalogue.** It is a minute and a half or more, and about
  3.5 GB of memory, per call. Sweeping a
  library is hours of CPU for pages nobody asked for — the same mistake the waveform pass made.
- **No `participants` field, until diarisation exists.** Measured: it read the ringtone marker
  `*ביב*` as a person and named them. Confident wrong names are worse than no names.
- **No `sentiment`.** Low value, and being confidently wrong about the tone of someone's private
  conversation is a bad trade.

---

## Global Constraints

- **Never while recording.** `TranscriptionEngine.isRunning` already exists for the same reason;
  summarisation needs the same guard and one of its own.
- **Never without a transcript.** The action does not appear for a recording that has none.
- **The model is downloaded, never bundled.** `ModelRepository` + `ModelDownloadWorker` already do
  resumable, size-verified, progress-reporting downloads. Reuse, do not rewrite.
- **Summaries are private.** Stored in `transcripts.db`, deleted by `TranscriptCascade` with the
  recording, and never logged.
- **Grammar-constrained output.** `llama_sampler_init_grammar` exists in llama.cpp. Without it the
  JSON is invalid often enough to break the screen — measured, not theorised.
- No `Co-Authored-By` trailers. Branch stays local.

---

## Task 1: Make the JSON impossible to malform — **DONE**

**Files:** `app/src/main/cpp/llamacv.cpp`, `LlamaNative.kt`, `SummaryEngine.kt`
**Test:** `app/src/androidTest/.../SummaryGrammarTest.kt`

Measured: Gemma returned `"summary": "…` with no closing quote. `JSON.parse` throws, and no prompt
wording fixes it — a 2B model under a token cap drops a quote. A grammar makes the bad token
unsampleable.

- [x] **Step 1–2: Prove it fails without a grammar.** Done differently and better than planned: the
      grammar was exercised through a desktop `llama-cli` build of the vendored submodule rather
      than an instrumented test. That is not a shortcut — it is what caught the real bug. An invalid
      grammar makes `llama-cli` refuse to start, whereas on Android `llama_sampler_init_grammar`
      returns null, the native side logs it, and generation carries on **unconstrained**, so the
      output still looks like JSON and nothing says the constraint was never applied.
- [x] **Step 3: Add a GBNF string parameter to `generate`**, and when present:

```cpp
llama_sampler_chain_add(sampler,
    llama_sampler_init_grammar(vocab, grammar_text, "root"));
```

- [x] **Step 4: Write the grammar** as a Kotlin constant beside the prompt — `SummaryGrammar.JSON`,
      six keys, `sentiment` and `participants` both omitted. **Every rule on one line:** GBNF ends a
      rule at the newline, so a `root` spread over several lines parses as several broken rules.
- [x] **Step 5: Verified.** Grammar-constrained output parsed on every run.
- [x] **Step 6: Committed** — `95414ee`, fixed at `7bb166a`.

**Still open from this task:** an on-device `SummaryGrammarTest` that runs the constrained path ten
times over. The desktop harness proved the grammar is valid; it does not prove the Android bridge
applies it, and that bridge is exactly where the silent failure lived. Fold it into Task 7.

## Task 2: Parse, store, and cascade — **DONE** (`5ba3c6f`, `c64e977`)

**Files:** `summary/CallSummary.kt`, `data/transcripts/db/CallSummaryEntry.kt`, `CallSummaryDao.kt`,
`TranscriptDatabase.kt` (v2→v3), `TranscriptCascade.kt`
**Test:** `CallSummaryTest.kt` (11), `CallSummaryStorageTest.kt` (7), `TranscriptSchemaMigrationTest.kt` (3)

- [x] Failing test: a malformed summary is rejected rather than stored half-parsed.
- [x] `CallSummary` data class — **`org.json`, not `kotlinx.serialization`.** The plan named a
      library the project does not have; six known keys under a grammar do not justify adding a
      compiler plugin to the build. Unknown keys are ignored either way, so nothing is lost.
- [x] Room v2→v3, hand-written like `MIGRATION_1_2`. No destructive fallback.
- [x] Extend `TranscriptCascade` so a deleted recording takes its summary. Tested.
- [x] **Beyond the plan:** a drift guard comparing both migrations against Room's exported
      `createSql`. `MIGRATION_1_2` had never been tested. The guard catches the realistic failure —
      an entity gaining a column while its migration silently does not — and was itself verified by
      dropping a column and watching it fire. It does **not** open a v2 database and upgrade it;
      that needs Room's `MigrationTestHelper`, and is worth adding the day a migration does more
      than add a table, because one that transforms rows can be well-formed and still lose data.
- [x] Commit.

## Task 3: The job — **DONE** (`9f4dbb4`, `87b2cf5`)

**Files:** `summary/SummaryQueue.kt`, `SummaryRunner.kt`, `SummaryWorker.kt`, `SummaryScheduler.kt`,
`SummaryProgress.kt`, plus `SummaryPrompt.forMergeJson`
**Test:** `SummaryQueueTest` (10), `SummaryRunnerTest` (10), `SummaryProgressTest` (9)

- [x] The gate: no summary without a finished, non-empty transcript, none while
      `TranscriptionEngine.isRunning`, none while another is running, none without the model. The
      order the reasons are checked in is a UI decision — durable problems before transient ones.
- [x] Worker with `setProgress`, honouring `isStopped`, calling `SummaryEngine.requestAbort`. It
      re-checks `TranscriptionEngine.isRunning` on start as well as at the tap, because a queued job
      can begin long after it was asked for.
- [x] Reuse `TranscriptionProgress` for the figure shown. Chunks stop at **85%** so the merge — the
      longest single generation of the run — does not happen behind a bar that already reads
      finished. Same defect as the transcription that vanished at 70%.
- [x] **Beyond the plan:** `forMergeJson`. The existing merge prompt returns prose, but the merge's
      output goes through `CallSummary.parse` under the same grammar, so it has to name the same six
      keys or two passes of the model produce nothing. Timestamps are copied verbatim, never
      recomputed — a stamp is only valid against the original recording.
- [x] Commit.

**Not yet reachable from anywhere.** `SummaryScheduler.runNow` has no caller until Task 6 adds the
card, so none of this has run on a device.

## Task 4: The model, in Settings — **in progress**

**Files:** `summary/SummaryModel.kt`, `transcription/model/DownloadableModel.kt`, `SettingsScreen.kt`

**Do this before Task 3.** The worker needs a model *path*, and paths come from the catalogue — the
plan's original numbering was written before that dependency was visible.

- [x] Add Gemma 4 E2B to the model catalogue: URL, exact size, checksum (`0da4ac1`). A sibling enum
      rather than a case in `TranscriptionModel`, which is described by a real-time factor against
      audio and has no meaning here. `ModelRepository` was widened to `DownloadableModel` first, as
      its own revertable commit (`60b6b8c`).
- [ ] Generalise `ModelDownloadWorker` the same way. **The open risk:** that path was built for
      190–574 MB and this is 3.46 GB. Resume, progress reporting and a download long enough to
      cross a network change all behave differently at six times the size.
- [ ] A **Summaries** section in Settings, next to Transcription: download/delete, size on disk, and
      the state of the download.
- [ ] Name the licence where the user can see it before downloading: Gemma is under Google's Terms
      of Use, not an OSI-free licence. The app stays clean because it never ships the weights, but
      the person accepting them deserves to be told.
- [ ] A **Summaries** section in Settings, next to Transcription: download/delete, size on disk, and
      the state of the download.
- [ ] Commit.

## Task 5: The requirements modal — **DONE** (`f59421c`)

**Files:** `ui/common/SummaryRequirementsDialog.kt`, strings in 11 locales

Shown once before the first download. The numbers are measured, so quote them.

- [x] Copy naming: **3.5 GB to download**, about **3.5 GB of memory while it runs**, roughly **a
      minute and a half for a short call**, and that it needs a recent phone.

      **These numbers were wrong in an earlier draft and matter more than the wording around them.**
      Memory is 3.0–3.5 GB peak PSS measured while the model was loaded, not the ~2 GB first written
      here — that reading was taken after the model had been freed. 3.5 GB resident genuinely does
      rule out smaller phones, which is the whole reason this dialog exists. Timing for a long call
      is still an **extrapolation** (2–4 minutes for ten minutes of audio), so the copy must not
      quote a per-minute rate as though it had been measured. See `SummaryModel.peakMemoryBytes`.
- [x] "Don't ask again", reflected as a Settings switch — the pattern the transcription warning
      already uses.
- [x] Translations. Commit.

## Task 6: The summary on the playback screen — **DONE** (`f59421c`)

**Files:** `ui/screens/PlaybackScreen.kt`, `ui/common/SummaryCard.kt`

- [x] `SummaryCard`: intent as a headline, summary as prose, then Decisions and To-do as lists.
- [x] Items beginning `[m:ss]` render the stamp as a chip and **seek on tap**, reusing
      `TranscriptTimestamp` and the seek path the transcript sheet already uses.
- [x] Empty arrays render as nothing at all — never "No decisions", which is noise.
- [x] Absent summary: a single row offering to make one, in the same shape as the transcript row.
- [x] While running: the percentage, not a spinner.
- [x] Footer: "Generated from the transcript", and a redo action.
- [x] RTL: each item through `BidiText`, per line, as the transcript sheet does.
- [x] Commit.

## Task 7: On-device pass — **PARTLY DONE**

**Done on an emulator (2026-08-23), because it can be hammered:**

- [x] The migrations, against real SQLite. v1→v3 keeps a transcript, v2→v3 keeps a note, the new
      table is usable immediately, and a second upgrade is a no-op. This is the one that protects an
      existing phone — no destructive fallback means a wrong migration is a crash on first launch.
- [x] **The whole 3.46 GB download**, end to end: fetched, resumed through an unrelated APK install,
      verified against its SHA-256, renamed into place. Plus cancel, resume-from-offset (server
      answers HTTP 206 at exactly the right byte), discard, and the free-space guard.
- [x] The JNI bridge loads on arm64 and refuses a missing model without crashing.
- [x] **The model loads and tokenises on 2.5 GB of RAM** — far under the measured 3.5 GB peak PSS,
      because llama.cpp memory-maps the weights. See the open questions.
- [x] Every Settings state rendered and photographed: absent, waiting, downloading, paused with
      bytes banked, installed, and the requirements dialog.

**Done on the maintainer's phone (2026-08-23):**

- [x] A **Hebrew call, end to end from the button**, twice. About five minutes for 6:52 of audio,
      ~715% CPU, 3.0 GB resident, one chunk, stored. Log confirms `Summarising in he` — the language
      is resolved before the prompt is built, here by script detection, since transcription is on
      auto-detect and nothing else pinned it.
- [x] **Stop mid-run.** Stopped on request; nothing partial stored, and the card fell back to the
      previous summary rather than blanking.
- [x] **The grammar holds on the Android bridge.** Not the ten formal runs Task 1 asked for, but
      every real run so far returned a document that parsed — which is the thing that check was for.
      The failure it was written to catch (a grammar silently not applied) would show as unparseable
      output, and has not occurred.

**Still open, and each needs something not to hand:**

- [ ] **An English call**, to confirm the language resolution is not accidentally Hebrew-shaped.
- [ ] **Delete the recording; the summary goes with it.** Covered by unit tests and the cascade's own
      test, but not exercised on a device — doing so means destroying one of the maintainer's real
      recordings, which is not a test worth running on a daily driver.
- [ ] **A phone that is not the OP12.** The OP9 is parked (see the backlog).
- [ ] **A multi-chunk call.** Every real run so far has been a single chunk, so the merge pass and
      its concatenating fallback have run only in tests. A call over about ten minutes would be the
      first to exercise them.

---

## What the real runs taught, that the spike could not

Kept because each of these cost a run to find, and every one of them is the same shape: **an
instruction to the model is a request, and the enforcement has to sit in code.**

**Never ask a model to infer the output language.** The fallback "write in the same language as the
conversation" is what produced an English summary of a Hebrew call. Handed a garbled or
language-mixed transcript there is no identifiable main language, and a small model resolves that by
writing English. A sibling project measured this over real Hebrew calls — 35 English summaries in
196, 23 of them from Hebrew transcripts — and fixed it the same way: pin the language. Its prompt
also carries a line worth keeping: *"Never switch language because the transcript was hard to read."*

**A token cap does not truncate gracefully.** The grammar prevents malformed JSON, not incomplete
JSON, and an incomplete document is refused on the way out — so the entire run is lost to protect
the user from half a summary. Six keys of Hebrew cost far more tokens than the same summary in
English. Raising the cap is nearly free, because generation stops when the model closes the object.

**Raising the cap invites padding.** The first successful summary repeated one sentence three times.
The fix is both halves: tell it not to, and then remove what it repeats anyway, matching fuzzily
because real repeats are not byte-equal — one differed by a single mis-transcribed word.

**WorkManager's `progress` is cleared when a worker finishes**, so anything identifying a job by its
progress stops recognising it exactly when the result matters. Tags survive. And a job appended to an
existing unique-work chain starts `BLOCKED`, not `ENQUEUED` — a state that is easy to forget and is
precisely the one a rewrite produces.

**Timestamps get invented, and here they are tappable.** Measured elsewhere: markers citing moments
past the end of the recording, including `[24:50]` on a call lasting 8:49. A fabricated citation on a
surface whose whole value is being checkable is worse than no citation, so they are verified against
what the model was shown.

---

## Open questions

1. **Does it run on 8 GB? PARTLY ANSWERED — and it no longer gates Task 5, which shipped.**

   **New evidence (2026-08-23):** the model loads and tokenises on a **2.5 GB** emulator. It is
   memory-mapped, so the 3.46 GB never has to fit in RAM — the kernel pages it in on demand, and
   the measured 3.5 GB peak PSS counts mapped file pages that are reclaimable rather than a hard
   floor. **Generation on an under-spec device is still untested.** That gives a better hypothesis
   for the OP9 stall than deadlock: generation walks the whole model per token, so a device without
   room pages from storage continuously and looks stopped while crawling.

   Earlier attempt, for the record: Attempted on the OP9 Pro
   (7.4 GB, Snapdragon 888) on 2026-08-21 and abandoned after several runs. What is known:

   - The model **loads** — 1.7 GB resident, so memory is not obviously the wall.
   - Generation then **never starts**: ~35 s of CPU for the load, then every thread sleeping at 0%
     CPU with the instrumentation still marked active. Not frozen by the OS (freezer cgroup is `/`,
     cpuset `foreground`), not killed, not out of memory, no native crash in logcat.
   - One real cause was found and fixed along the way: a killed run leaves `SummaryEngine`'s mutex
     held, and because the app process survives, the *next* instrumentation attaches to the same
     process and blocks on it for ever. **Force-stop the app between runs.** That got the model
     loading; it did not get generation running.

   Worth trying next: run it as a foreground activity rather than an instrumented test, and check
   whether the OP12 shows the same stall when its process is reused. Do not assume the phone is too
   small — nothing measured says that yet.
2. **Chunk size is a quality dial.** Measured: smaller chunks produced *better* summaries, because
   less is compressed at once. Worth tuning deliberately rather than defaulting to "as few as fit".
3. **Does the merge prompt hold up over a ninety-minute call?** Never tested past two chunks.
