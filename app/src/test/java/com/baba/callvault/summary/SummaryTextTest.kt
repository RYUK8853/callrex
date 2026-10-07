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
import org.junit.Test

class SummaryTextTest {

    @Test
    fun `removes a reasoning block and keeps the answer`() {
        val raw = "<think>\nThinking Process: the user wants a summary.\n</think>\n\nDani moved the delivery."
        assertEquals("Dani moved the delivery.", SummaryText.stripReasoning(raw))
    }

    @Test
    fun `removes a block that never closed`() {
        // Measured: the token budget ran out mid-thought, leaving an open tag and no answer. Shown
        // raw, the user would read the model's deliberations as the summary of their call.
        val raw = "<think>\nThinking Process:\n  1. Analyze the Request\n  2. I need to look at"
        assertEquals("", SummaryText.stripReasoning(raw))
    }

    @Test
    fun `leaves an ordinary answer alone`() {
        assertEquals("Sam moved the boiler service.", SummaryText.stripReasoning("Sam moved the boiler service."))
    }

    @Test
    fun `handles an empty block, which is what a model that declined to think emits`() {
        assertEquals("Dani moved the delivery.", SummaryText.stripReasoning("<think>\n\n</think>\n\nDani moved the delivery."))
    }

    @Test
    fun `trims the whitespace a block leaves behind`() {
        assertEquals("Answer.", SummaryText.stripReasoning("  <think>x</think>  \n Answer. \n "))
    }

    // ---- closing a document the token budget cut short ----

    @Test
    fun `closes an answer cut off inside the last list`() {
        // The measured shape of the loss: keyFacts is required and last, so before this the whole
        // chunk went, taking five keys of correct work with it.
        val cut = """{"intent":"a","summary":"b","keyPoints":["p"],"decisions":[],"actionItems":[],""" +
            """"keyFacts":["Invoice 4021","£1,2"""

        assertEquals(
            """{"intent":"a","summary":"b","keyPoints":["p"],"decisions":[],"actionItems":[],""" +
                """"keyFacts":["Invoice 4021"]}""",
            SummaryText.closeTruncated(cut)
        )
    }

    @Test
    fun `drops a list the generation never reached rather than inventing one`() {
        val cut = """{"intent":"a","summary":"b","keyPoints":["p"],"dec"""

        assertEquals("""{"intent":"a","summary":"b","keyPoints":["p"]}""", SummaryText.closeTruncated(cut))
    }

    @Test
    fun `leaves a whole object alone`() {
        // Nothing to close. Whatever else may be wrong with it, truncation is not it.
        assertNull(SummaryText.closeTruncated("""{"intent":"a","summary":"b"}"""))
        assertNull(SummaryText.closeTruncated("""Here you go: {"intent":"a"} — hope that helps."""))
    }

    @Test
    fun `refuses text with nothing finished in it`() {
        assertNull(SummaryText.closeTruncated("""{"intent":"Chasing an inv"""))
        assertNull(SummaryText.closeTruncated("I'm sorry, I cannot summarise this call."))
        assertNull(SummaryText.closeTruncated(""))
    }

    @Test
    fun `an escaped quote does not read as the end of a value`() {
        // Real speech is quoted back on real calls, and the grammar allows the escape. A repair that
        // mistook \" for a closing quote would cut a document in half at the wrong place.
        val cut = """{"intent":"He said \"pay it\"","summary":"b","keyPoints":["x"],"dec"""

        assertEquals(
            """{"intent":"He said \"pay it\"","summary":"b","keyPoints":["x"]}""",
            SummaryText.closeTruncated(cut)
        )
    }
}
