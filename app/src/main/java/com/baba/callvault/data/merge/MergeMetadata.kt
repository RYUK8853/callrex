/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.content.Context
import com.baba.callvault.data.recordings.db.MergePartEntry
import com.baba.callvault.data.transcripts.db.RecordingFlagEntry
import com.baba.callvault.data.transcripts.db.RecordingNoteEntry
import com.baba.callvault.data.transcripts.db.RecordingTagEntry
import com.baba.callvault.data.transcripts.db.SpeakerTurnsEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.utils.AppLogger

/**
 * Carries a merged recording's transcript, marks, tags, star and note across from its parts.
 *
 * **Copies, never moves.** Each part keeps its own rows, untouched, even though its audio file is
 * gone. They are a few kilobytes of text, and keeping them makes un-merge trivial and safe: putting
 * the calls back is a matter of deleting what the merge added, with everything the parts had already
 * sitting where it always was. Redistributing rows back by time range would be the alternative, and
 * it would be both more code and a chance to lose something.
 *
 * This relies on the merged recording having a name of its own, distinct from every part — see
 * `MergeService.freeName`. If it shared the primary's name, deleting "what the merge added" would
 * delete the primary's own transcript.
 */
object MergeMetadata {

    private const val TAG = "CV:MergeMeta"

    /**
     * Builds the merged recording's side-data from [parts].
     *
     * Times shift by each part's position on the merged timeline, so a transcript reads continuously
     * and a mark still points at the moment it was dropped.
     */
    suspend fun onMerge(context: Context, mergedName: String, parts: List<MergePartEntry>) {
        val db = TranscriptDatabase.get(context)

        // --- transcript and its segments -------------------------------------------------------
        val segments = parts.flatMap { part ->
            val offsetMs = part.startTimeUs / 1000
            db.transcriptDao().segmentsFor(part.partName).map { segment ->
                segment.copy(
                    id = 0,
                    displayName = mergedName,
                    startMs = segment.startMs + offsetMs,
                    endMs = segment.endMs + offsetMs,
                )
            }
        }
        if (segments.isNotEmpty()) {
            // A merged call is only fully transcribed if every part was. Anything less is marked
            // QUEUED so the missing stretches get filled in, rather than DONE over a hole.
            val states = parts.map { db.transcriptDao().findTranscript(it.partName)?.state }
            val allDone = states.all { it == com.baba.callvault.data.transcripts.db.TranscriptState.DONE }
            val template = parts.firstNotNullOfOrNull { db.transcriptDao().findTranscript(it.partName) }
            db.transcriptDao().upsertTranscript(
                TranscriptEntry(
                    displayName = mergedName,
                    state = if (allDone) com.baba.callvault.data.transcripts.db.TranscriptState.DONE
                    else com.baba.callvault.data.transcripts.db.TranscriptState.QUEUED,
                    modelId = template?.modelId,
                    language = template?.language,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            db.transcriptDao().replaceSegments(mergedName, segments)
        }

        // --- marks, plus one at each seam ------------------------------------------------------
        val marks = parts.flatMap { part ->
            val offsetMs = part.startTimeUs / 1000
            db.flagDao().flagsFor(part.partName).map { it + offsetMs }
        }.toMutableList()
        // The gap between two calls is not padded with silence, so without this the join would be
        // invisible. A mark keeps the information and makes the seam somewhere you can jump to.
        parts.drop(1).forEach { marks += it.startTimeUs / 1000 }
        if (marks.isNotEmpty()) {
            db.flagDao().upsertAll(marks.distinct().sorted().map { RecordingFlagEntry(mergedName, it) })
        }

        // --- tags: the union, since every part's labels describe the same conversation ----------
        parts.flatMap { db.tagDao().tagsFor(it.partName) }.distinct().forEach { tag ->
            db.tagDao().add(RecordingTagEntry(mergedName, tag))
        }

        // --- star: if any part was worth keeping, so is the whole call -------------------------
        if (parts.any { db.favouriteDao().isFavourite(it.partName) }) {
            db.favouriteDao().add(mergedName)
        }

        // --- note: joined, labelled, in playback order -----------------------------------------
        val notes = parts.mapNotNull { part ->
            db.noteDao().note(part.partName)?.text?.takeIf { it.isNotBlank() }
        }
        if (notes.isNotEmpty()) {
            db.noteDao().upsertNote(
                RecordingNoteEntry(mergedName, notes.joinToString("\n\n"), System.currentTimeMillis())
            )
        }

        carrySpeakerTurns(context, mergedName, parts)

        // Deliberately NOT carried: the summary, which described half a conversation and would be
        // worse than none; and the waveform, which is a cache the playback screen redraws anyway.
        AppLogger.i(
            TAG,
            "Merged metadata onto $mergedName: ${segments.size} segments, ${marks.size} marks, " +
                "${notes.size} note(s)"
        )
    }

    /**
     * Speaker labels, carried only when every part agrees about which channel is whose.
     *
     * The channel mapping is learned per call from the ringback, which only an outgoing call has. So
     * an outgoing call merged with an incoming one has one half whose mapping is not established, and
     * concatenating anyway would confidently label half the transcript with the speakers swapped.
     * Dropping the labels is recoverable and obvious; swapping them is neither.
     */
    private suspend fun carrySpeakerTurns(context: Context, mergedName: String, parts: List<MergePartEntry>) {
        val db = TranscriptDatabase.get(context)
        val turns = parts.map { db.speakerTurnsDao().turnsFor(it.partName) }
        if (turns.any { it == null }) return

        val present = turns.filterNotNull()
        val agree = present.map { it.outgoing }.distinct().size == 1 &&
            present.map { it.observedMap }.distinct().size == 1
        if (!agree) {
            AppLogger.i(TAG, "Not carrying speaker labels to $mergedName: the parts disagree on channels")
            return
        }

        val shifted = parts.mapIndexedNotNull { i, part ->
            val offsetMs = part.startTimeUs / 1000
            present.getOrNull(i)?.turns?.takeIf { it.isNotBlank() }?.split(";")?.mapNotNull { pair ->
                val at = pair.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
                val channel = pair.substringAfter(':', "")
                if (channel.isEmpty()) null else "${at + offsetMs}:$channel"
            }?.joinToString(";")
        }.filter { it.isNotBlank() }
        if (shifted.isEmpty()) return

        db.speakerTurnsDao().upsert(
            SpeakerTurnsEntry(
                displayName = mergedName,
                turns = shifted.joinToString(";"),
                outgoing = present.first().outgoing,
                observedMap = present.first().observedMap,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Removes everything the merge created, leaving each part's own rows as they always were.
     *
     * That is the whole of un-merging the metadata, and it is why [onMerge] copies rather than moves.
     */
    suspend fun onUnMerge(context: Context, mergedName: String) {
        val db = TranscriptDatabase.get(context)
        db.transcriptDao().deleteFor(mergedName)
        db.flagDao().deleteFor(mergedName)
        db.tagDao().deleteFor(mergedName)
        db.favouriteDao().deleteFor(mergedName)
        db.noteDao().deleteNote(mergedName)
        db.noteDao().deleteWaveform(mergedName)
        db.speakerTurnsDao().deleteFor(mergedName)
        db.summaryDao().deleteFor(mergedName)
        AppLogger.i(TAG, "Cleared the merged metadata for $mergedName; the parts kept their own")
    }
}
