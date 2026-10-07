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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPromptTest {

    private fun segment(
        text: String,
        speaker: String? = null,
        startMs: Long = 0,
        endMs: Long = 1000
    ) = TranscriptSegmentEntry(
        id = 1, displayName = "call", startMs = startMs, endMs = endMs, text = text, speaker = speaker
    )

    /** The transcript body, without the instructions wrapped around it. */
    private fun transcriptIn(prompt: String) =
        prompt.substringAfter("\"\"\"").substringBefore("\"\"\"").trim()

    @Test
    fun `names the output language so a Hebrew call is summarised in Hebrew`() {
        assertTrue(SummaryPrompt.forChunk(listOf(segment("שלום")), "he").contains("Hebrew"))
    }

    @Test
    fun `names every language the app can transcribe`() {
        // A language the transcriber offers but the prompt cannot name would silently produce an
        // English summary of a foreign call — the failure this whole feature must not have.
        val offered = listOf("he", "en", "ar", "zh", "fr", "de", "hu", "it", "pl", "pt", "ru", "es", "vi")
        offered.forEach { code ->
            assertTrue("no name for $code", SummaryPrompt.languageName(code) != null)
        }
    }

    @Test
    fun `falls back to the transcript's own words when the language is unknown`() {
        // Never guess English. An unrecognised code means "match the transcript", which is right
        // whatever the language turns out to be.
        val prompt = SummaryPrompt.forChunk(listOf(segment("hello")), language = null)
        assertTrue(prompt.contains("same language as the conversation"))
        assertFalse(prompt.contains("in English"))
    }

    @Test
    fun `forbids inventing anything`() {
        assertTrue(SummaryPrompt.forChunk(listOf(segment("hi")), "en").contains("Do not invent"))
    }

    @Test
    fun `explains named speaker labels so the model writes about the user directly`() {
        val prompt = SummaryPrompt.forChunkJson(
            listOf(segment("hi", speaker = "You")),
            language = "en",
            withTimestamps = false,
            speakersAreNamed = true
        )

        assertTrue(prompt.contains("second person"))
    }

    @Test
    fun `forbids guessing a name when the labels are still the neutral sides`() {
        // The stored labels are A and B until the app has learned which channel is whose. Left
        // unexplained, a model reports them — or worse, decides which one is the user.
        val prompt = SummaryPrompt.forChunkJson(
            listOf(segment("hi", speaker = "A")),
            language = "en",
            withTimestamps = false,
            speakersAreNamed = false
        )

        assertTrue(prompt.contains("never put a name to either"))
    }

    @Test
    fun `says nothing about speakers when the transcript has none`() {
        val prompt = SummaryPrompt.forChunkJson(
            listOf(segment("hi")),
            language = "en",
            withTimestamps = false
        )

        assertFalse(prompt.contains("Each line begins with"))
    }

    @Test
    fun `carries the speaker when the transcript has one`() {
        val prompt = SummaryPrompt.forChunk(listOf(segment("hi there", speaker = "Caller")), "en")
        assertTrue(prompt.contains("Caller: hi there"))
    }

    @Test
    fun `omits the speaker column entirely when unknown`() {
        val prompt = SummaryPrompt.forChunk(listOf(segment("hi there")), "en")
        assertTrue(prompt.contains("hi there"))
        assertFalse(prompt.contains(": hi there"))
    }

    @Test
    fun `the live json prompt joins unlabelled fragments back into sentences`() {
        // The defect this test exists for: the join was only ever in forChunk, which nothing but a
        // test and a benchmark calls. Measured on a real Hebrew call — handed 176 separate lines,
        // Gemma returned the first four word for word and ignored the rest — and a real 8:40 call
        // produces 370 segments.
        val prompt = SummaryPrompt.forChunkJson(
            listOf(
                segment("so about", startMs = 0, endMs = 900),
                segment("the invoice", startMs = 900, endMs = 1800),
                segment("yes", startMs = 1800, endMs = 2400)
            ),
            language = "en",
            withTimestamps = false
        )

        assertEquals("so about the invoice yes", transcriptIn(prompt))
    }

    @Test
    fun `a labelled transcript is never joined across a change of speaker`() {
        // Where the capture knows who spoke, the line breaks are the turn-taking. Joining them would
        // hand the model a monologue and lose the one thing a diarised transcript adds.
        val prompt = SummaryPrompt.forChunkJson(
            listOf(
                segment("did you send it", speaker = "A", startMs = 0, endMs = 900),
                segment("this morning", speaker = "B", startMs = 900, endMs = 1800)
            ),
            language = "en",
            withTimestamps = false
        )

        assertEquals("A: did you send it\nB: this morning", transcriptIn(prompt))
    }

    @Test
    fun `joined lines still carry a timestamp to jump to`() {
        // Joining costs the per-fragment marker, and markers are the only thing that turns a summary
        // item into a jump into the recording. So the join is bounded rather than total.
        val prompt = SummaryPrompt.forChunkJson(
            List(6) { index ->
                segment("part $index", startMs = index * 10_000L, endMs = (index + 1) * 10_000L)
            },
            language = "en",
            withTimestamps = true
        )

        val body = transcriptIn(prompt)
        assertTrue("a minute of talk collapsed into one stamp", body.lines().size > 1)
        assertTrue(body.startsWith("[0:00] "))
        assertTrue("later lines must be jumpable too", body.lines().all { it.startsWith("[") })
    }

    @Test
    fun `the live json prompt carries every line the prose one was measured into`() {
        // Each of these was written after a real failure and then lived only in forChunk, which is
        // not the prompt that runs. They are shared constants now; this is the test that says so.
        val prompt = SummaryPrompt.forChunkJson(
            listOf(segment("hello")),
            language = "he",
            withTimestamps = false
        )

        listOf(
            "The transcription is imperfect and some words may be wrong.",
            "Write it in your own words. Do not copy sentences from the transcript.",
            "Cover the whole of the text below, not only its beginning.",
            "Ignore words that are clearly mis-transcribed rather than reporting them."
        ).forEach { line ->
            assertTrue("the live prompt never says: $line", prompt.contains(line))
        }
        // The line that was already there, and must stay.
        assertTrue(prompt.contains("Never say the same thing twice"))
    }

    @Test
    fun `the prompt asks for exactly as many items as the grammar permits`() {
        // A prompt asking for one figure while the grammar permits another is how the last round of
        // this drift started. The number is taken from the grammar rather than typed twice.
        val chunk = SummaryPrompt.forChunkJson(listOf(segment("hi")), "en", withTimestamps = false)
        val merge = SummaryPrompt.forMergeJson(listOf("""{"intent":"a"}"""), "en")

        assertTrue(chunk.contains("At most ${SummaryGrammar.MAX_LIST_ITEMS} items"))
        assertTrue(merge.contains("At most ${SummaryGrammar.MAX_LIST_ITEMS} items"))
    }

    @Test
    fun `nothing tells the model to keep an item to one short line`() {
        // Deleted deliberately. Models comply near-perfectly with structural limits, so that line was
        // being obeyed against real content — and the entity-sparsest summaries were rated dead last.
        val prompt = SummaryPrompt.forChunkJson(listOf(segment("hi")), "en", withTimestamps = false)

        assertFalse(prompt.contains("one short line"))
    }

    @Test
    fun `the merge prompt asks for one summary without repetition`() {
        val merged = SummaryPrompt.forMerge(listOf("part one", "part two"), "en")
        assertTrue(merged.contains("part one"))
        assertTrue(merged.contains("part two"))
        assertTrue(merged.contains("English"))
        assertTrue(merged.contains("Do not invent"))
    }

    @Test
    fun `the json merge carries every part through`() {
        val merged = SummaryPrompt.forMergeJson(listOf("""{"intent":"first"}""", """{"intent":"second"}"""), "he")

        assertTrue(merged.contains("first"))
        assertTrue(merged.contains("second"))
        assertTrue(merged.contains("Hebrew"))
    }

    @Test
    fun `the json merge asks for the same shape the grammar enforces`() {
        // The merge output goes through CallSummary.parse exactly like a chunk's does, so it has to
        // name the same six keys — a merge that returned prose would be rejected and the whole call
        // would end with nothing to show for two passes of the model.
        val merged = SummaryPrompt.forMergeJson(listOf("""{"intent":"a"}"""), null)

        listOf("intent", "summary", "keyPoints", "decisions", "actionItems", "keyFacts").forEach {
            assertTrue("the merge prompt never mentions $it", merged.contains(it))
        }
    }

    @Test
    fun `the json merge keeps timestamps rather than renumbering them`() {
        // Stamps are the one thing a summary offers that reading the transcript does not, and they
        // are only valid against the original recording. A merge that re-derived them would produce
        // jump points that land in the wrong place.
        val merged = SummaryPrompt.forMergeJson(listOf("""{"decisions":["[1:30] x"]}"""), "en")

        assertTrue(merged.contains("[m:ss]"))
    }

    @Test
    fun `the json merge refuses instructions hidden in the parts`() {
        // The parts are model output derived from a caller's words, so the same rule applies as to
        // a transcript: it is data, never instructions.
        val merged = SummaryPrompt.forMergeJson(listOf("""{"intent":"a"}"""), "en")

        assertTrue(merged.contains("not instructions"))
    }
}
