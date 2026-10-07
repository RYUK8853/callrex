/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryChunkingTest {

    private fun segments(count: Int, chars: Int = 100): List<TranscriptSegmentEntry> =
        (0 until count).map { i ->
            TranscriptSegmentEntry(
                id = i.toLong(),
                displayName = "call",
                startMs = i * 1000L,
                endMs = (i + 1) * 1000L,
                text = "x".repeat(chars)
            )
        }

    @Test
    fun `keeps a short transcript in one chunk`() {
        assertEquals(1, SummaryChunking.chunk(segments(10, chars = 50), maxChars = 4000).size)
    }

    @Test
    fun `never splits a segment across chunks`() {
        val input = segments(100, chars = 200)
        assertEquals(input, SummaryChunking.chunk(input, maxChars = 1000).flatten())
    }

    @Test
    fun `keeps every chunk within the limit`() {
        val chunks = SummaryChunking.chunk(segments(100, chars = 200), maxChars = 1000)
        assertTrue(chunks.all { chunk -> chunk.sumOf { it.text.length } <= 1000 })
    }

    @Test
    fun `emits nothing for an empty transcript`() {
        assertTrue(SummaryChunking.chunk(emptyList(), maxChars = 4000).isEmpty())
    }

    @Test
    fun `keeps a segment that is longer than the whole limit rather than dropping it`() {
        // A single 5000-character segment cannot fit a 1000-character chunk. Dropping it would lose
        // part of the call silently, which is worse than one oversized chunk the model will truncate.
        val giant = segments(1, chars = 5000)
        assertEquals(giant, SummaryChunking.chunk(giant, maxChars = 1000).flatten())
    }

    @Test
    fun `fills chunks rather than making one per segment`() {
        // Ten 100-char segments into a 1000-char limit is one chunk, not ten. Each chunk costs a
        // whole model round trip, so needless splitting is minutes of a user's battery.
        assertEquals(1, SummaryChunking.chunk(segments(10, chars = 100), maxChars = 1000).size)
    }
}
