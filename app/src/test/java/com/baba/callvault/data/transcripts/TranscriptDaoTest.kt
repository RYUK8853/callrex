/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.transcripts.db.TranscriptDao
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transcript store, which is deliberately NOT part of [com.baba.callvault.data.recordings.db.RecordingDatabase].
 *
 * That database is built with `fallbackToDestructiveMigration` because the recordings catalog is a
 * rebuildable cache. A transcript is not rebuildable — it costs minutes of device CPU — so it lives
 * here, keyed by the catalog's natural key so it survives the catalog being dropped and re-seeded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class TranscriptDaoTest {

    private lateinit var db: TranscriptDatabase
    private lateinit var dao: TranscriptDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.transcriptDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun stores_segments_and_returns_them_in_start_order() = runBlocking {
        // Arrange
        dao.upsertTranscript(transcript("call-a.ogg"))
        dao.replaceSegments(
            "call-a.ogg",
            listOf(
                segment("call-a.ogg", startMs = 1000, endMs = 2000, text = "second"),
                segment("call-a.ogg", startMs = 0, endMs = 1000, text = "first")
            )
        )

        // Act
        val stored = dao.observe("call-a.ogg").first()

        // Assert
        assertEquals(listOf("first", "second"), stored!!.segments.map { it.text })
    }

    @Test
    fun full_text_search_finds_a_hebrew_word_inside_a_segment() = runBlocking {
        // Arrange — Hebrew is the point: the porter tokenizer stems English and would be wrong here.
        dao.upsertTranscript(transcript("call-b.ogg"))
        dao.replaceSegments(
            "call-b.ogg",
            listOf(segment("call-b.ogg", 0, 1000, "נתראה בפגישה מחר בבוקר"))
        )

        // Act
        val hits = dao.search("בפגישה")

        // Assert
        assertEquals(listOf("call-b.ogg"), hits.map { it.displayName })
    }

    @Test
    fun deleting_a_transcript_removes_its_segments_and_its_search_rows() = runBlocking {
        // Arrange
        dao.upsertTranscript(transcript("call-c.ogg"))
        dao.replaceSegments("call-c.ogg", listOf(segment("call-c.ogg", 0, 1, "ephemeral")))

        // Act
        dao.deleteFor("call-c.ogg")

        // Assert — a deleted recording must not leave a searchable text of the conversation behind.
        assertNull(dao.observe("call-c.ogg").first())
        assertTrue("search still returned a deleted transcript", dao.search("ephemeral").isEmpty())
    }

    @Test
    fun replacing_segments_does_not_accumulate_duplicates_on_re_transcription() = runBlocking {
        // Arrange
        dao.upsertTranscript(transcript("call-d.ogg"))
        dao.replaceSegments("call-d.ogg", listOf(segment("call-d.ogg", 0, 1, "old")))

        // Act — re-transcribing the same recording must overwrite, never append.
        dao.replaceSegments("call-d.ogg", listOf(segment("call-d.ogg", 0, 1, "new")))

        // Assert
        val stored = dao.observe("call-d.ogg").first()
        assertEquals(listOf("new"), stored!!.segments.map { it.text })
    }

    @Test
    fun reports_display_names_for_a_given_state() = runBlocking {
        // Arrange
        dao.upsertTranscript(transcript("done.ogg", TranscriptState.DONE))
        dao.upsertTranscript(transcript("failed.ogg", TranscriptState.FAILED))

        // Act
        val failed = dao.displayNamesWithState(TranscriptState.FAILED)

        // Assert
        assertEquals(listOf("failed.ogg"), failed)
    }

    @Test
    fun observing_a_recording_with_no_transcript_emits_null() = runBlocking {
        // Absence is the common case on first run and must be a state, not an error.
        assertNull(dao.observe("never-transcribed.ogg").first())
    }

    private fun transcript(name: String, state: TranscriptState = TranscriptState.DONE) =
        TranscriptEntry(
            displayName = name,
            state = state,
            modelId = "small-q5_1",
            language = "he",
            updatedAt = 0L,
            errorMessage = null
        )

    private fun segment(name: String, startMs: Long, endMs: Long, text: String) =
        TranscriptSegmentEntry(
            displayName = name,
            startMs = startMs,
            endMs = endMs,
            text = text,
            speaker = null
        )
}
