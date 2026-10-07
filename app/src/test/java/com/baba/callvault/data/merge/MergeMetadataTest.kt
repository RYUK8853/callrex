/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.db.MergePartEntry
import com.baba.callvault.data.transcripts.db.RecordingFlagEntry
import com.baba.callvault.data.transcripts.db.RecordingNoteEntry
import com.baba.callvault.data.transcripts.db.RecordingTagEntry
import com.baba.callvault.data.transcripts.db.SpeakerTurnsEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Carrying a merged call's transcript, marks, tags, star and note across from its parts.
 *
 * Everything here is silent when it goes wrong: a transcript whose second half is offset by the
 * wrong amount still displays, and marks that land in the wrong place look like marks. So the
 * assertions are on exact positions rather than on presence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergeMetadataTest {

    private lateinit var context: Context
    private val merged = "20260901_143200.001+0300_out_Dan.m4a"
    private val partA = "20260901_143200.000+0300_out_Dan.m4a"
    private val partB = "20260901_144000.000+0300_in_Dan.m4a"

    /** A five-second first part, then a three-second second part starting at 5 s. */
    private val parts = listOf(
        MergePartEntry(merged, 1, partA, 0, 236, 0L, 5_000_000L, 1L, 21_333L, 0L),
        MergePartEntry(merged, 2, partB, 236, 142, 5_000_000L, 3_000_000L, 2L, 21_333L, 0L),
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        // Row-by-row rather than clearAllTables(), which is a blocking call and trips Room's
        // main-thread assertion under Robolectric.
        listOf(merged, partA, partB).forEach { name ->
            MergeMetadata.onUnMerge(context, name)
        }
    }

    private fun db() = TranscriptDatabase.get(context)

    private suspend fun giveTranscript(name: String, vararg segments: Triple<Long, Long, String>) {
        db().transcriptDao().upsertTranscript(
            TranscriptEntry(name, TranscriptState.DONE, "model", "he", 1L, null)
        )
        db().transcriptDao().replaceSegments(
            name,
            segments.map { (s, e, text) -> TranscriptSegmentEntry(0, name, s, e, text, null) }
        )
    }

    @Test
    fun the_second_parts_transcript_is_shifted_onto_the_merged_timeline() = runBlocking {
        // Arrange
        giveTranscript(partA, Triple(0L, 1_000L, "hello"))
        giveTranscript(partB, Triple(0L, 900L, "sorry, we got cut off"))

        // Act
        MergeMetadata.onMerge(context, merged, parts)

        // Assert — part B started 5 s in, so its segment must too
        val segments = db().transcriptDao().segmentsFor(merged)
        assertEquals(listOf("hello", "sorry, we got cut off"), segments.map { it.text })
        assertEquals(listOf(0L, 5_000L), segments.map { it.startMs })
        assertEquals(listOf(1_000L, 5_900L), segments.map { it.endMs })
    }

    @Test
    fun a_merged_call_is_only_DONE_when_every_part_was_transcribed() = runBlocking {
        giveTranscript(partA, Triple(0L, 1_000L, "hello"))
        db().transcriptDao().upsertTranscript(
            TranscriptEntry(partB, TranscriptState.QUEUED, null, null, 1L, null)
        )

        MergeMetadata.onMerge(context, merged, parts)

        // DONE over a hole would mean the missing half never gets transcribed
        assertEquals(TranscriptState.QUEUED, db().transcriptDao().findTranscript(merged)!!.state)
    }

    @Test
    fun marks_shift_and_a_new_one_lands_on_the_seam() = runBlocking {
        // Arrange
        db().flagDao().upsertAll(listOf(RecordingFlagEntry(partA, 2_000L)))
        db().flagDao().upsertAll(listOf(RecordingFlagEntry(partB, 500L)))

        // Act
        MergeMetadata.onMerge(context, merged, parts)

        // Assert — A's mark stays, B's moves to 5500, and the join itself is marked at 5000
        assertEquals(listOf(2_000L, 5_000L, 5_500L), db().flagDao().flagsFor(merged))
    }

    @Test
    fun tags_are_unioned_and_the_star_carries_if_either_part_had_it() = runBlocking {
        db().tagDao().add(RecordingTagEntry(partA, "work"))
        db().tagDao().add(RecordingTagEntry(partB, "work"))
        db().tagDao().add(RecordingTagEntry(partB, "urgent"))
        db().favouriteDao().add(partB)

        MergeMetadata.onMerge(context, merged, parts)

        assertEquals(listOf("urgent", "work"), db().tagDao().tagsFor(merged))
        assertTrue(db().favouriteDao().isFavourite(merged))
    }

    @Test
    fun speaker_labels_are_dropped_rather_than_guessed_when_the_parts_disagree() = runBlocking {
        // An outgoing call learns the channel mapping from ringback; an incoming one cannot. Carrying
        // one part's mapping over the other would swap the speakers for half the conversation.
        db().speakerTurnsDao().upsert(SpeakerTurnsEntry(partA, "0:0;1200:1", outgoing = true, observedMap = "A", updatedAt = 1L))
        db().speakerTurnsDao().upsert(SpeakerTurnsEntry(partB, "0:1", outgoing = false, observedMap = "B", updatedAt = 1L))

        MergeMetadata.onMerge(context, merged, parts)

        assertNull(db().speakerTurnsDao().turnsFor(merged))
    }

    @Test
    fun speaker_labels_are_carried_and_shifted_when_the_parts_agree() = runBlocking {
        db().speakerTurnsDao().upsert(SpeakerTurnsEntry(partA, "0:0;1200:1", outgoing = true, observedMap = "A", updatedAt = 1L))
        db().speakerTurnsDao().upsert(SpeakerTurnsEntry(partB, "0:0;400:1", outgoing = true, observedMap = "A", updatedAt = 1L))

        MergeMetadata.onMerge(context, merged, parts)

        // part B's turns move by the 5 s it starts at
        assertEquals("0:0;1200:1;5000:0;5400:1", db().speakerTurnsDao().turnsFor(merged)!!.turns)
    }

    @Test
    fun un_merging_removes_what_the_merge_added_and_leaves_the_parts_untouched() = runBlocking {
        // Arrange
        giveTranscript(partA, Triple(0L, 1_000L, "hello"))
        giveTranscript(partB, Triple(0L, 900L, "again"))
        db().tagDao().add(RecordingTagEntry(partA, "work"))
        db().favouriteDao().add(partA)
        db().noteDao().upsertNote(RecordingNoteEntry(partA, "ring back", 1L))
        MergeMetadata.onMerge(context, merged, parts)
        assertNotNull(db().transcriptDao().findTranscript(merged))

        // Act
        MergeMetadata.onUnMerge(context, merged)

        // Assert — the merged rows are gone
        assertNull(db().transcriptDao().findTranscript(merged))
        assertTrue(db().transcriptDao().segmentsFor(merged).isEmpty())
        assertTrue(db().tagDao().tagsFor(merged).isEmpty())
        assertFalse(db().favouriteDao().isFavourite(merged))

        // ...and each part still has everything it always had, which is what makes un-merge safe
        assertEquals(listOf("hello"), db().transcriptDao().segmentsFor(partA).map { it.text })
        assertEquals(listOf("again"), db().transcriptDao().segmentsFor(partB).map { it.text })
        assertEquals(listOf("work"), db().tagDao().tagsFor(partA))
        assertTrue(db().favouriteDao().isFavourite(partA))
        assertEquals("ring back", db().noteDao().note(partA)!!.text)
    }
}
