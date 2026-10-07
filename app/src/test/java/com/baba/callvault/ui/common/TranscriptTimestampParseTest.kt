/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading back a `[m:ss]` a model copied out of a transcript.
 *
 * These stamps are the one thing a summary offers that reading the transcript does not — a decision
 * with `[1:30]` beside it is a jump into the call. That makes a wrong parse worse than no parse: it
 * seeks somewhere the user was not promised, in a recording they cannot easily check against.
 *
 * The input is model output, so it is not to be trusted. Anything that is not exactly a stamp has
 * to come back as no stamp rather than as a guess.
 */
class TranscriptTimestampParseTest {

    @Test
    fun `reads a leading stamp and returns the rest of the line`() {
        val parsed = TranscriptTimestamp.parseLeading("[1:30] Resend the invoice")!!

        assertEquals(90_000L, parsed.millis)
        assertEquals("Resend the invoice", parsed.text)
    }

    @Test
    fun `reads an hours stamp from a long call`() {
        // format() keeps hours rather than wrapping, so a ninety-minute call produces these.
        val parsed = TranscriptTimestamp.parseLeading("[1:05:09] Agreed the price")!!

        assertEquals((3600 + 5 * 60 + 9) * 1000L, parsed.millis)
        assertEquals("Agreed the price", parsed.text)
    }

    @Test
    fun `round trips whatever format produces`() {
        listOf(0L, 9_000L, 90_000L, 3_599_000L, 3_600_000L, 7_265_000L).forEach { millis ->
            val rendered = "[${TranscriptTimestamp.format(millis)}] something"

            assertEquals("round trip of $millis", millis, TranscriptTimestamp.parseLeading(rendered)?.millis)
        }
    }

    @Test
    fun `a line with no stamp is left alone`() {
        assertNull(TranscriptTimestamp.parseLeading("Resend the invoice"))
    }

    @Test
    fun `a stamp that is not at the start is not a stamp`() {
        // The prompt asks for it as a prefix. One in the middle is prose the model wrote, and
        // seeking on it would be acting on something never promised to be a jump point.
        assertNull(TranscriptTimestamp.parseLeading("Agreed at [1:30] to resend it"))
    }

    @Test
    fun `nonsense inside the brackets is refused rather than guessed at`() {
        listOf("[] x", "[abc] x", "[1:] x", "[:30] x", "[1:2:3:4] x", "[-1:30] x").forEach {
            assertNull("parsed $it", TranscriptTimestamp.parseLeading(it))
        }
    }

    @Test
    fun `seconds beyond a minute are refused`() {
        // 1:75 is not a time. A model that emitted it has made something up, and quietly
        // normalising to 2:15 would turn a fabrication into a plausible-looking jump point.
        assertNull(TranscriptTimestamp.parseLeading("[1:75] x"))
    }

    @Test
    fun `a stamp with nothing after it has no line to show`() {
        assertNull(TranscriptTimestamp.parseLeading("[1:30]"))
        assertNull(TranscriptTimestamp.parseLeading("[1:30]   "))
    }

    @Test
    fun `surrounding whitespace does not defeat it`() {
        val parsed = TranscriptTimestamp.parseLeading("  [0:05]   Call back  ")!!

        assertEquals(5_000L, parsed.millis)
        assertEquals("Call back", parsed.text)
    }

    @Test
    fun `a right-to-left line keeps its stamp`() {
        // Hebrew is the language most of these calls are in, and the stamp is written left-to-right
        // inside a right-to-left line.
        val parsed = TranscriptTimestamp.parseLeading("[2:15] לחזור ללקוח")!!

        assertEquals(135_000L, parsed.millis)
        assertEquals("לחזור ללקוח", parsed.text)
    }
}
