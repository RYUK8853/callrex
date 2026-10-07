/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import com.baba.callvault.data.transcripts.db.TranscriptSearchHit
import com.baba.callvault.summary.CallSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What makes summaries and notes searchable without making search useless.
 *
 * Two failures are being guarded, and neither would crash: indexing the summary's JSON instead of its
 * prose, which returns *every* call for words like "decisions"; and preferring a hit that cannot be
 * seeked to, which renders a perfect row that jumps to the wrong place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchIndexingTest {

    private val summary = CallSummary(
        intent = "Chasing an unpaid invoice",
        summary = "He agreed to send the invoice on Tuesday.",
        keyPoints = listOf("Invoice 4021 is three weeks late"),
        decisions = listOf("Settle by bank transfer"),
        actionItems = listOf("Send a reminder on Monday"),
        keyFacts = listOf("Amount: 2,400")
    )

    @Test
    fun `searchable text carries every field a user might search for`() {
        val text = summary.searchableText()

        listOf(
            "Chasing an unpaid invoice",
            "He agreed to send the invoice on Tuesday.",
            "Invoice 4021 is three weeks late",
            "Settle by bank transfer",
            "Send a reminder on Monday",
            "Amount: 2,400"
        ).forEach { assertTrue("Missing from the index: $it", it in text) }
    }

    @Test
    fun `searchable text contains none of the JSON scaffolding`() {
        // The whole reason this function exists. `document` is JSON, so indexing it would make a
        // search for "keyPoints" or "decisions" match every call that has a summary at all — a search
        // box that returns everything, which reads to the user as one that returns nothing useful.
        val text = summary.searchableText()

        listOf("keyPoints", "actionItems", "keyFacts", "{", "}", "\":").forEach {
            assertFalse("JSON scaffolding leaked into the index: $it", it in text)
        }
    }

    @Test
    fun `a blank field does not become a blank line in the index`() {
        val sparse = summary.copy(keyPoints = emptyList(), decisions = listOf("", "  "))

        assertFalse(sparse.searchableText().contains("\n\n"))
    }

    @Test
    fun `a segment hit beats a summary hit for the same recording`() {
        // Only the segment knows where in the audio the match was; the summary hit is pinned at 0.
        // Keeping the wrong one sends the user to the start of an hour-long call.
        val merged = TranscriptRepository.mergeHits(
            segments = listOf(TranscriptSearchHit("call.ogg", startMs = 84_000, snippet = "spoken")),
            summaries = listOf(TranscriptSearchHit("call.ogg", startMs = 0, snippet = "summarised")),
            notes = listOf(TranscriptSearchHit("call.ogg", startMs = 0, snippet = "noted"))
        )

        assertEquals(1, merged.size)
        assertEquals(84_000, merged.single().startMs)
        assertEquals("spoken", merged.single().snippet)
    }

    @Test
    fun `a call found only in its summary is still returned`() {
        // The point of the feature: an outcome written down in words nobody said out loud.
        val merged = TranscriptRepository.mergeHits(
            segments = emptyList(),
            summaries = listOf(TranscriptSearchHit("call.ogg", startMs = 0, snippet = "summarised")),
            notes = emptyList()
        )

        assertEquals("summarised", merged.single().snippet)
    }

    @Test
    fun `each recording appears once however many indexes matched`() {
        val merged = TranscriptRepository.mergeHits(
            segments = listOf(TranscriptSearchHit("a.ogg", 10, "a")),
            summaries = listOf(TranscriptSearchHit("b.ogg", 0, "b")),
            notes = listOf(TranscriptSearchHit("a.ogg", 0, "a-note"), TranscriptSearchHit("c.ogg", 0, "c"))
        )

        assertEquals(listOf("a.ogg", "b.ogg", "c.ogg"), merged.map { it.displayName }.sorted())
    }
}
