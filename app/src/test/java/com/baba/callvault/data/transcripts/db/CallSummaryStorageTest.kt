/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.summary.CallSummary
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
 * Storage for summaries, against a real Room database rather than a mock.
 *
 * The point of interest is not that a row can be written — it is that a summary survives the trip
 * through SQLite unchanged, including the quotation marks and newlines that occur in ordinary
 * speech, and that deleting a recording actually takes it. The second is a privacy defect if it is
 * wrong: an orphaned summary is a readable account of a call the user deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallSummaryStorageTest {

    private lateinit var db: TranscriptDatabase
    private lateinit var dao: CallSummaryDao

    private val summary = CallSummary(
        intent = "Chasing an unpaid invoice",
        summary = "He said \"pay it, or else\".\nThen he rang off.",
        keyPoints = listOf("Invoice 4021 is overdue"),
        decisions = listOf("[1:30] Resend it to accounts@"),
        actionItems = emptyList(),
        keyFacts = listOf("£1,240")
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TranscriptDatabase::class.java
        ).build()
        dao = db.summaryDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a summary survives the round trip through the database`() = runBlocking {
        dao.upsert(CallSummaryEntry("call.ogg", summary.toJson(), MODEL, createdAt = 12L))

        val restored = CallSummary.parse(dao.summary("call.ogg")!!.document)

        assertEquals(summary, restored)
    }

    @Test
    fun `no summary reads as null rather than an empty one`() = runBlocking {
        assertNull(dao.summary("never-summarised.ogg"))
        assertNull(dao.observe("never-summarised.ogg").first())
    }

    @Test
    fun `redoing a summary replaces the old one instead of keeping both`() = runBlocking {
        dao.upsert(CallSummaryEntry("call.ogg", summary.toJson(), MODEL, createdAt = 1L))
        val better = summary.copy(summary = "A second, better attempt.")

        dao.upsert(CallSummaryEntry("call.ogg", better.toJson(), MODEL, createdAt = 2L))

        val stored = dao.summary("call.ogg")!!
        assertEquals(2L, stored.createdAt)
        assertEquals("A second, better attempt.", CallSummary.parse(stored.document)!!.summary)
    }

    @Test
    fun `deleting a recording takes its summary and leaves the others`() = runBlocking {
        dao.upsert(CallSummaryEntry("gone.ogg", summary.toJson(), MODEL))
        dao.upsert(CallSummaryEntry("kept.ogg", summary.toJson(), MODEL))

        dao.deleteFor("gone.ogg")

        assertNull(dao.summary("gone.ogg"))
        assertEquals("kept.ogg", dao.summary("kept.ogg")?.displayName)
    }

    @Test
    fun `reports which recordings already have a summary`() = runBlocking {
        dao.upsert(CallSummaryEntry("done.ogg", summary.toJson(), MODEL))

        val summarised = dao.summarised(listOf("done.ogg", "pending.ogg"))

        assertEquals(listOf("done.ogg"), summarised)
    }

    @Test
    fun `the model that wrote a summary is kept with it`() = runBlocking {
        // Summaries are not comparable across models, and the user saying "this one is wrong" is
        // only actionable if we know which model wrote it.
        dao.upsert(CallSummaryEntry("call.ogg", summary.toJson(), MODEL))

        assertEquals(MODEL, dao.summary("call.ogg")!!.model)
    }

    @Test
    fun `the summary arrives on the open screen without a revisit`() = runBlocking {
        // The playback screen is open while the worker runs, so the card has to appear on its own.
        assertNull(dao.observe("call.ogg").first())

        dao.upsert(CallSummaryEntry("call.ogg", summary.toJson(), MODEL))

        assertTrue(dao.observe("call.ogg").first() != null)
    }

    private companion object {
        const val MODEL = "gemma-4-E2B-it-Q4_K_M"
    }
}
