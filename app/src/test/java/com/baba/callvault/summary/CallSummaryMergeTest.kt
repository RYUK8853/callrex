/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Combining chunk summaries without asking the model again.
 *
 * The merge pass is a second generation, and a second chance to fail: it can truncate against its
 * token budget, and the result is then no summary at all after two minutes of work the user watched.
 * This is the floor under that — the parts already parsed, so something real can always be shown.
 *
 * Not a replacement for the model's merge, which reads the parts and writes one account of the call.
 * This concatenates. It is what happens when the good version did not survive.
 */
class CallSummaryMergeTest {

    private fun part(intent: String, decision: String, fact: String) = CallSummary(
        intent = intent,
        summary = "Something was said about $intent.",
        keyPoints = listOf("point about $intent"),
        decisions = listOf(decision),
        actionItems = emptyList(),
        keyFacts = listOf(fact)
    )

    @Test
    fun `one part merges to itself`() {
        val only = part("the invoice", "resend it", "4021")

        assertEquals(only, CallSummary.concatenate(listOf(only)))
    }

    @Test
    fun `nothing to merge is nothing`() {
        assertNull(CallSummary.concatenate(emptyList()))
    }

    @Test
    fun `the whole call keeps every part's decisions`() {
        val merged = CallSummary.concatenate(
            listOf(
                part("the invoice", "resend it", "4021"),
                part("the delivery", "move it to Thursday", "£1,240")
            )
        )!!

        assertEquals(listOf("resend it", "move it to Thursday"), merged.decisions)
        assertEquals(listOf("4021", "£1,240"), merged.keyFacts)
    }

    @Test
    fun `the prose reads as one account rather than two`() {
        val merged = CallSummary.concatenate(
            listOf(
                part("the invoice", "resend it", "4021"),
                part("the delivery", "move it", "£1,240")
            )
        )!!

        assertTrue(merged.summary.contains("the invoice"))
        assertTrue(merged.summary.contains("the delivery"))
    }

    @Test
    fun `the first part names the call`() {
        // The opening is where a caller says why they rang, so the earliest intent is the call's.
        val merged = CallSummary.concatenate(
            listOf(part("chasing an invoice", "a", "b"), part("small talk", "c", "d"))
        )!!

        assertEquals("chasing an invoice", merged.intent)
    }

    @Test
    fun `a repeated point is not listed twice`() {
        // Chunks overlap in subject far more than in wording, but an identical line across two parts
        // is the same fact — and a summary that says it twice reads as though the app is padding.
        val merged = CallSummary.concatenate(
            listOf(
                part("the invoice", "resend it", "4021"),
                part("the invoice", "resend it", "4021")
            )
        )!!

        assertEquals(listOf("resend it"), merged.decisions)
        assertEquals(listOf("4021"), merged.keyFacts)
    }

    @Test
    fun `an empty intent does not become the call's intent`() {
        val merged = CallSummary.concatenate(
            listOf(
                part("", "a", "b"),
                part("chasing an invoice", "c", "d")
            )
        )!!

        assertEquals("chasing an invoice", merged.intent)
    }
}
