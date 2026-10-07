/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which `[m:ss]` citations in a summary are real.
 *
 * The prompt asks for the timestamp "copied verbatim from the transcript" and ends with "never
 * invent a timestamp". Models invent them anyway — a sibling project measured, over its stored
 * summaries, **11 carrying a marker that appears nowhere in their transcript and 3 citing a moment
 * past the end of the recording**, including `[24:50]` on a call lasting 8:49.
 *
 * That matters more here than it did there, because these markers are **tappable and seek the
 * player**. A fabricated one is a false citation on the very surface whose value is that it can be
 * checked against the recording.
 *
 * The claim itself may well be right — the model read the transcript, it just could not point at
 * where. So the marker goes and the text stays: deleting a whole item over an unverifiable citation
 * would throw away good analysis to punish a formatting error.
 */
class SummaryCitationsTest {

    private val offered = setOf(0L, 90_000L, 135_000L)

    private fun summary(vararg decisions: String) = CallSummary(
        intent = "Chasing an invoice",
        summary = "The caller rang about invoice 4021.",
        keyPoints = emptyList(),
        decisions = decisions.toList(),
        actionItems = emptyList(),
        keyFacts = emptyList()
    )

    @Test
    fun `a marker the transcript vouches for is kept`() {
        val checked = SummaryCitations.strip(summary("[1:30] Resend the invoice"), offered, durationMs = 200_000L)

        assertEquals("[1:30] Resend the invoice", checked.summary.decisions.single())
        assertTrue(checked.removed.isEmpty())
    }

    @Test
    fun `a marker that appears nowhere in the transcript is removed`() {
        val checked = SummaryCitations.strip(summary("[7:11] Resend the invoice"), offered, durationMs = 600_000L)

        assertEquals("Resend the invoice", checked.summary.decisions.single())
        assertEquals(listOf("[7:11]"), checked.removed)
    }

    @Test
    fun `a marker past the end of the recording is removed`() {
        // The second gate, for what the first cannot see. Measured elsewhere: [24:50] cited on a
        // call lasting 8:49. A seek there lands on nothing.
        val checked = SummaryCitations.strip(summary("[1:30] Resend it"), offered, durationMs = 60_000L)

        assertEquals("Resend it", checked.summary.decisions.single())
        assertEquals(listOf("[1:30]"), checked.removed)
    }

    @Test
    fun `the claim survives its citation being removed`() {
        // The model read the transcript; it just could not point at where. Deleting the analysis to
        // punish the formatting would cost the user more than the wrong marker did.
        val checked = SummaryCitations.strip(summary("[9:99] Ring back on Thursday"), offered, durationMs = 600_000L)

        assertEquals("Ring back on Thursday", checked.summary.decisions.single())
    }

    @Test
    fun `an item that was only a marker is dropped entirely`() {
        // Stripping leaves an empty bullet, which reads as a fact the app has lost.
        val checked = SummaryCitations.strip(summary("[7:11]", "[1:30] Real one"), offered, durationMs = 200_000L)

        assertEquals(listOf("[1:30] Real one"), checked.summary.decisions)
    }

    @Test
    fun `an unknown duration does not delete good citations`() {
        // A missing marker is an inconvenience; a wrongly deleted one destroys something the user
        // could have checked. With no duration to test against, only the transcript gate applies.
        val checked = SummaryCitations.strip(summary("[2:15] Call back"), offered, durationMs = 0L)

        assertEquals("[2:15] Call back", checked.summary.decisions.single())
    }

    @Test
    fun `a citation exactly at the end is allowed a little slack`() {
        // A marker names the START of a segment and the duration comes from a decoder, so an
        // exact-boundary citation on the last line is legitimate.
        val checked = SummaryCitations.strip(summary("[2:15] Call back"), offered, durationMs = 135_000L)

        assertEquals("[2:15] Call back", checked.summary.decisions.single())
    }

    @Test
    fun `every field is checked, not just the lists`() {
        val invented = CallSummary(
            intent = "[9:00] Chasing an invoice",
            summary = "They agreed at [8:00] to resend it.",
            keyPoints = listOf("[7:00] a point"),
            decisions = emptyList(),
            actionItems = listOf("[6:00] a follow-up"),
            keyFacts = listOf("[5:00] a fact")
        )

        val checked = SummaryCitations.strip(invented, offered, durationMs = 600_000L)

        assertEquals("Chasing an invoice", checked.summary.intent)
        assertEquals("They agreed at to resend it.", checked.summary.summary)
        assertEquals(listOf("a point"), checked.summary.keyPoints)
        assertEquals(listOf("a follow-up"), checked.summary.actionItems)
        assertEquals(listOf("a fact"), checked.summary.keyFacts)
        // Five: one in each of intent, summary, keyPoints, actionItems and keyFacts.
        assertEquals(5, checked.removed.size)
    }

    @Test
    fun `removals are reported so they can be logged`() {
        // Silence here would make this the kind of invisible edit it exists to catch: a stripped
        // summary looks perfectly clean, so nothing else would ever say the model was inventing.
        val checked = SummaryCitations.strip(summary("[7:11] one", "[8:22] two"), offered, durationMs = 600_000L)

        assertEquals(listOf("[7:11]", "[8:22]"), checked.removed)
    }
}
