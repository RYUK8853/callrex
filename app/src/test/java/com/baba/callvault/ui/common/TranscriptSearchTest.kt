/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.db.TranscriptSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Turning raw FTS hits into rows a person can act on.
 *
 * The search itself is tested in `TranscriptRepositoryTest` — including the quoting that stops a typed
 * apostrophe becoming a syntax error. What is tested here is the join back onto the recordings list,
 * which is where a hit can become untappable without anything appearing to go wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSearchTest {

    private fun recording(
        displayName: String,
        contactName: String? = null,
        number: String? = null
    ) = RecordingItem(
        uri = Uri.parse("content://recordings/$displayName"),
        displayName = displayName,
        sizeBytes = 1_000L,
        lastModified = 0L,
        direction = null,
        displayDate = null,
        startedAtMillis = null,
        number = number,
        contactName = contactName
    )

    private fun hit(displayName: String, startMs: Long = 0L, snippet: String = "hello") =
        TranscriptSearchHit(displayName = displayName, startMs = startMs, snippet = snippet)

    @Test
    fun a_hit_becomes_a_row_naming_the_contact_and_the_moment() {
        val rows = TranscriptSearch.rowsFor(
            hits = listOf(hit("call-a.ogg", startMs = 12_000L, snippet = "the invoice is paid")),
            recordings = listOf(recording("call-a.ogg", contactName = "Dana"))
        )

        assertEquals(1, rows.size)
        assertEquals(BidiText.isolate("Dana"), rows[0].title)
        assertEquals(12_000L, rows[0].startMs)
        assertEquals("the invoice is paid", rows[0].snippet)
    }

    @Test
    fun a_hit_whose_recording_is_gone_is_dropped_rather_than_shown_dead() {
        // A transcript can outlive its recording for a moment — the cascade runs on the delete path,
        // and a hit with nothing to open would be a row that does nothing when tapped.
        val rows = TranscriptSearch.rowsFor(
            hits = listOf(hit("deleted.ogg"), hit("kept.ogg")),
            recordings = listOf(recording("kept.ogg"))
        )

        assertEquals(listOf("kept.ogg"), rows.map { it.displayName })
    }

    @Test
    fun rows_follow_the_recordings_order_not_the_order_the_database_returned() {
        // FTS returns rows in storage order, which would scatter results through time. The list the
        // user already sees is sorted the way they expect, so results adopt that order.
        val rows = TranscriptSearch.rowsFor(
            hits = listOf(hit("older.ogg"), hit("newer.ogg")),
            recordings = listOf(recording("newer.ogg"), recording("older.ogg"))
        )

        assertEquals(listOf("newer.ogg", "older.ogg"), rows.map { it.displayName })
    }

    @Test
    fun falls_back_to_the_number_and_then_the_filename_when_there_is_no_contact() {
        val rows = TranscriptSearch.rowsFor(
            hits = listOf(hit("by-number.ogg"), hit("by-name.ogg")),
            recordings = listOf(
                recording("by-number.ogg", number = "+972500000000"),
                recording("by-name.ogg")
            )
        )

        val titles = rows.associate { it.displayName to it.title }
        assertEquals(BidiText.isolate("+972500000000"), titles["by-number.ogg"])
        assertEquals(BidiText.isolate("by-name.ogg"), titles["by-name.ogg"])
    }

    @Test
    fun a_snippet_spanning_several_lines_is_collapsed_onto_one() {
        // Segment text can carry newlines; a row that grows to three lines breaks the list rhythm.
        val rows = TranscriptSearch.rowsFor(
            hits = listOf(hit("call.ogg", snippet = "first line\n  second line\t third")),
            recordings = listOf(recording("call.ogg"))
        )

        assertEquals("first line second line third", rows[0].snippet)
    }

    @Test
    fun an_unmatched_query_produces_no_rows_rather_than_every_recording() {
        val rows = TranscriptSearch.rowsFor(
            hits = emptyList(),
            recordings = listOf(recording("call-a.ogg"), recording("call-b.ogg"))
        )

        assertTrue(rows.isEmpty())
    }
}
