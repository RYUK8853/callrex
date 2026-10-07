# Merging a dropped-and-redialled call into one recording — plan

🧪 **PLAN ONLY — nothing built.** Status of every item below is "not started".

## The problem

A call drops and you ring each other back. To you that is one conversation; to CallVault it is two
recordings, which is correct and also unhelpful. The user should be able to say "these were one
call" and get one recording — and be able to take it back.

## Decisions already taken

Settled with the maintainer on 2026-09-02, recorded so they are not relitigated:

- **Entry point is the recording card's ⋮ menu**, not a proactive suggestion on the list. The
  alternative (the app spots a likely continuation and offers) was considered and **declined**: it is
  inherently pairwise, so a call that dropped twice would prompt twice.
- **Order is the order you tick them in, not chronological.** The recording you opened the menu from
  is always ① and cannot be moved. Each one you tick takes the next number. Ticking the 3rd row in
  the list makes it ②, and it plays immediately after ①.
- **Merge deletes the originals**, leaving one file, with a setting to keep them. Un-merge is still
  required — which is only honest because the cut is frame-exact (measured below).

⚠️ Because order is selection order, a merge *can* be assembled out of chronological order. That is
allowed on purpose. The consequence is that the merged file's timeline is **playback order, not
wall-clock order** — so each part's header keeps its real date and time, and it is the running
position inside the merged audio that is re-based. The numbered badges plus the result strip are
what make the chosen order visible before it is committed.

## Un-merge without keeping the originals — MEASURED, not assumed

The maintainer's requirement is that a merge leaves **one file**, with un-merge still available. That
only works if the merged file can be cut back into the originals exactly. It can, and this was
verified on 2026-09-02 with ffmpeg rather than reasoned about:

**Why it works.** A stream-copy merge does not re-encode anything. It copies each part's encoded
frames into a new container and re-stamps their presentation times. The merged file therefore
*contains the original frames*, unchanged. Cutting at a frame boundary hands them straight back.

**AAC / `.m4a` — bit-exact.**

| Check | Result |
|---|---|
| frame count | 236 + 142 = **378**, exactly |
| raw frame bytes | merged file's first 42 083 bytes `cmp`-identical to part A; last 25 375 identical to part B |
| decoded PCM, part A vs its slice of the merge | **0 of 240 640 samples differ** |

The PCM comparison is only clean once a constant **1024-sample (21.33 ms)** offset is accounted for —
that is the AAC encoder's priming delay, which the container's edit list expresses and a naive
concatenation drops. It is a fixed, known quantity, not drift: recorded per part at merge time and
re-applied on the split, it cancels exactly. Left unrecorded, every un-merged part would come back
21 ms early.

**Opus / `.ogg` — packets exact, trim metadata needs recording.**

Packet count 251 + 151 = **402** exactly and every packet size matches in sequence. What
concatenation drops is the per-stream *pre-skip / end-trim* side data at the seam (312 and 648
samples in the test, ~6.5 ms and ~13.5 ms). Same treatment: record both values per part, re-apply on
split.

**Conclusion: deleting the originals loses nothing recoverable**, provided `merge_parts` stores the
frame boundary and the codec-delay values. Un-merge is a frame-exact cut, not a re-encode, so it is
as fast as the merge and equally lossless.

## Merge deletes the originals — with a verify step that cannot be skipped

Default is **delete**; a setting keeps them for anyone who wants the belt and braces.

🚨 The ordering is not negotiable, because it is the only thing standing between a bug and
unrecoverable data loss:

1. Write the merged file.
2. **Re-open it and verify**: frame count equals the sum of the parts, duration matches within one
   frame, and it decodes.
3. Only on success, delete the originals — device copies and Drive copies alike.

A failed verification keeps everything and reports the failure. Nothing is deleted on the strength of
a write that was never read back.

**Deleting a merged recording deletes the whole conversation**, since the parts no longer exist
separately. Its delete confirmation must say how many calls it contains.

### The honest limitation

Un-merge depends on the `merge_parts` rows. Lose `recordings.db` and a merged recording degrades to
an ordinary un-splittable one — the audio is all still there and plays fine, but the app no longer
knows where the seams were. That is the same exposure the catalog already carries for Drive URIs, and
`UntrackedRecordings` already rebuilds what it can from the files themselves. Worth stating plainly
rather than discovering later; not worth a second copy of the manifest in v1.

## Schema

`recordings.db` → **version 3** (version 2 is the duration cache on `fix/recording-duration-cache`).

```kotlin
@Entity(tableName = "merge_parts", primaryKeys = ["mergedName", "position"])
data class MergePartEntry(
    val mergedName: String,      // the merged recording's displayName
    val position: Int,           // 1 = the primary, then selection order
    val partName: String,        // the original's displayName — restores date, direction, number
    val frameStart: Int,         // first frame index of this part within the merged stream
    val frameCount: Int,         // how many frames it owns
    val startTimeUs: Long,       // its start on the merged timeline
    val durationUs: Long,
    val originalLastModified: Long,
    val encoderDelayUs: Long,    // AAC priming / Opus pre-skip — re-applied on split
    val encoderPaddingUs: Long,  // end trim, same
)
```

`frameStart` / `frameCount` are what make the cut exact: `MediaExtractor` yields one encoded frame per
`advance()`, so the boundary is an integer, never a rounded millisecond. The two encoder-delay fields
are what the measurement above proved necessary — without them every un-merged part returns 21 ms
early on AAC.

Since the originals are gone by default, there is nothing to hide from the Home list: the merged
recording is simply the only one there.

## What happens to the metadata

Everything keys off `displayName` across ten tables. Each is **copied, never moved**, so un-merge is
a delete:

| Table | Rule on merge |
|---|---|
| `transcripts`, `transcript_segments` | concatenated; part *n*'s times shifted by its `offsetMs` |
| `speaker_turns` | concatenated and shifted |
| `recording_flags` (marks) | concatenated and shifted, **plus a seam mark per boundary** |
| `recording_tags` | union |
| `recording_favourites` | starred if **any** part was starred |
| `recording_notes` | concatenated with a separator naming each part |
| `recording_waveforms` | peaks concatenated |
| `call_summaries` | **not inherited.** A summary of half a conversation is worse than none; the merged call offers re-summarise |

Everything is **copied, never moved**: each part keeps its own rows even though its audio is gone.
They are a few kilobytes of text, and it makes un-merge a matter of deleting what the merge added,
with each part's own data already sitting where it always was. Redistributing rows back by time
range would be more code and another chance to lose something.

This is why the merged recording gets a name of its own rather than reusing the primary's: if it
shared that name, deleting "what the merge added" would delete the primary's own transcript.

**Speaker labels are the one thing that can be dropped.** The channel mapping is learned per call
from ringback, which only an outgoing call has, so an outgoing call merged with an incoming one has
one half whose mapping was never established. Concatenating anyway would confidently label half the
transcript with the speakers swapped. Dropping is recoverable and obvious; swapping is neither.

## Build order

0. **Progress, 2026-09-02:** the whole invisible half is built and green — schema, `AudioConcat`,
   `AudioSplit`, `MergeService` (verify-then-delete, keep-originals setting), `MergeManifest`
   (flattening arithmetic, pure and unit-tested) and `MergeMetadata` (transcript, marks, tags, star,
   note, speaker labels). **Remaining: the UI and its strings.** 1108 unit tests and 6 instrumented,
   all passing.

1. `MergePartEntry` + DAO + `MIGRATION_2_3`, with tests. No UI.
2. `AudioConcat` — the stream-copy join, plus format-compatibility check. Unit-tested against real
   fixtures from both capture paths.
3. `MergeService` — orchestration: write the file, copy the metadata, write `merge_parts`, and the
   inverse for un-merge. Transactional; a failed merge must leave nothing behind.
4. `AudioSplit` — the inverse cut, re-applying the codec delays. **Round-trip test is the gate:**
   merge two fixtures, un-merge, assert the decoded PCM matches the originals sample for sample.
5. The verify-then-delete ordering, plus the "keep the original calls" setting.
5b. **The metadata migration** — transcript, segments, speaker turns, marks, tags, star, note and
   waveform, moved onto the merged name with each part's times shifted, and redistributed back by
   time range on un-merge. Listed separately because it is the one remaining piece that silently
   loses something a user cares about if it is skipped.
6. The modal, the ⋮ entry, un-merge + its confirm dialog.
7. Strings across all ten locales via `scripts/merge-translations.py`.

Steps 1–5 are invisible to the user and carry all the risk; the UI is the cheap half.

## Chaining: a merged call CAN be merged again

Settled 2026-09-02. Refusing this was proposed and **rejected** — the maintainer asked why, and the
honest answer was that it is barely any work, so the caution was not earned.

**Flattening is what makes it cheap.** Merging M1 = [A, B] with C does not nest. M1's frames simply
*are* A's frames followed by B's, so the new manifest is the concatenation of M1's part rows with
C's, and the only work is re-basing `frameStart` when M1 is not the primary:

```
  M1 = [A, B]                          merge M1 (primary) with C
  M2.parts = [A, B, C]                 A, B keep their offsets; C appends
  M2 = [C, A, B]  (C primary)          A, B shift by C's frameCount
```

`encoderDelayUs` / `encoderPaddingUs` need no adjustment at all: they are properties of each part's
own original encode, captured once when it was first merged, and re-merging does not touch them.

**The one real consequence, which the UI must not hide:** un-merging M2 returns **A, B and C** — three
calls — not "M1 and C", because M1 no longer exists as a thing. That is more useful than the
alternative and avoids two-level un-merges entirely, but it *is* a surprise if unannounced. The
un-merge confirmation therefore lists exactly which calls will come back, by date and time. No
partial or one-level-at-a-time un-merge; nobody asked for it and it doubles the state.

Nothing can appear twice, because an original is deleted once merged and so can only live inside one
merged recording.

## The candidate list

Every call with that number, newest first, loaded lazily. No date cut-off: a cut-off can only ever
hide the row someone is looking for, and the list is already cheap now that duration is a column
rather than a file read per row.
