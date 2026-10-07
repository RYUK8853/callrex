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
 * Removing the same point said twice.
 *
 * Observed on the first real Hebrew summary this app produced: "Decisions" held four items, three of
 * them the same sentence, and "Key points" repeated one line three times. The model pads towards its
 * token budget, and raising that budget — which is what stopped runs being lost to truncation — gave
 * it more room to do so.
 *
 * The prompt now asks it not to. That is a request, and small models ignore requests about counts;
 * this is the part that does not. Same reasoning as the timestamp check.
 *
 * **Near-identical, not identical.** The repeats were not byte-equal — one differed by a single
 * mis-transcribed word. Exact matching would have removed none of them.
 */
class SummaryDedupeTest {

    @Test
    fun `an exact repeat is removed`() {
        val deduped = SummaryDedupe.apply(
            summaryWith(decisions = listOf("Resend the invoice", "Resend the invoice"))
        )

        assertEquals(listOf("Resend the invoice"), deduped.decisions)
    }

    @Test
    fun `a repeat differing by one word is removed`() {
        // The real case: "הסכום המבוקש" against "הסום המבוקש", one word mis-transcribed. Byte
        // equality sees two different facts; a reader sees the same sentence twice.
        val deduped = SummaryDedupe.apply(
            summaryWith(
                decisions = listOf(
                    "The requested sum is between half and one percent of the company",
                    "The requested amount is between half and one percent of the company"
                )
            )
        )

        assertEquals(1, deduped.decisions.size)
    }

    @Test
    fun `the first wording is the one kept`() {
        val deduped = SummaryDedupe.apply(
            summaryWith(decisions = listOf("Resend the invoice today", "Resend the invoice today please"))
        )

        assertEquals("Resend the invoice today", deduped.decisions.single())
    }

    @Test
    fun `genuinely different points both survive`() {
        // The failure that would matter. Over-eager matching would delete real content, and a
        // summary missing a decision is worse than one that repeats itself.
        val deduped = SummaryDedupe.apply(
            summaryWith(
                decisions = listOf(
                    "Resend the invoice to accounts",
                    "Call back on Thursday if it is still unpaid"
                )
            )
        )

        assertEquals(2, deduped.decisions.size)
    }

    @Test
    fun `a timestamp prefix does not make two repeats look different`() {
        val deduped = SummaryDedupe.apply(
            summaryWith(decisions = listOf("[1:30] Resend the invoice", "[2:15] Resend the invoice"))
        )

        assertEquals(1, deduped.decisions.size)
        // The stamp on the surviving item is kept — it is still a real jump point.
        assertTrue(deduped.decisions.single().startsWith("[1:30]"))
    }

    @Test
    fun `a key point already said as a decision is dropped`() {
        // Decisions and follow-ups are the specific claims; a key point repeating one adds nothing
        // and makes the card look padded.
        val deduped = SummaryDedupe.apply(
            summaryWith(
                decisions = listOf("Resend the invoice to accounts"),
                keyPoints = listOf("Resend the invoice to accounts", "The invoice went to the wrong address")
            )
        )

        assertEquals(listOf("The invoice went to the wrong address"), deduped.keyPoints)
    }

    @Test
    fun `decisions are never dropped for repeating a key point`() {
        // The precedence has to run one way only. A decision is the more specific claim, so it wins.
        val deduped = SummaryDedupe.apply(
            summaryWith(
                decisions = listOf("Resend the invoice"),
                keyPoints = listOf("Resend the invoice")
            )
        )

        assertEquals(listOf("Resend the invoice"), deduped.decisions)
    }

    @Test
    fun `a fact already said as a key point is dropped`() {
        // Observed on a real call: "Worth keeping" repeated four of the six "Key points" word for
        // word. Every list drops what the lists above it already said, in one pass down the card.
        val deduped = SummaryDedupe.apply(
            summaryWith(
                keyPoints = listOf("The candidate needs product-manager experience"),
                keyFacts = listOf("The candidate needs product-manager experience", "Invoice 4021")
            )
        )

        assertEquals(listOf("Invoice 4021"), deduped.keyFacts)
    }

    @Test
    fun `key points are never dropped for repeating a fact`() {
        val deduped = SummaryDedupe.apply(
            summaryWith(
                keyPoints = listOf("The candidate needs product-manager experience"),
                keyFacts = listOf("The candidate needs product-manager experience")
            )
        )

        assertEquals(1, deduped.keyPoints.size)
    }

    @Test
    fun `short items are compared strictly`() {
        // Two- or three-word items share words easily. "Pay now" and "Pay later" are 50% identical
        // by words and mean opposite things.
        val deduped = SummaryDedupe.apply(summaryWith(keyFacts = listOf("Pay now", "Pay later")))

        assertEquals(2, deduped.keyFacts.size)
    }

    @Test
    fun `a summary with nothing repeated is untouched`() {
        val original = summaryWith(
            decisions = listOf("a decision"),
            keyPoints = listOf("a point"),
            keyFacts = listOf("a fact")
        )

        assertEquals(original, SummaryDedupe.apply(original))
    }

    private fun summaryWith(
        decisions: List<String> = emptyList(),
        keyPoints: List<String> = emptyList(),
        keyFacts: List<String> = emptyList()
    ) = CallSummary(
        intent = "Chasing an invoice",
        summary = "The caller rang about invoice 4021.",
        keyPoints = keyPoints,
        decisions = decisions,
        actionItems = emptyList(),
        keyFacts = keyFacts
    )
}
