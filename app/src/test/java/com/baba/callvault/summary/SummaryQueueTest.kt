/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.db.TranscriptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a call may be summarised, and when it may not.
 *
 * Every refusal here is protecting something specific. Summarising costs roughly ninety seconds of
 * a phone's full CPU and about two gigabytes of memory, so the expensive mistakes are starting a run
 * that cannot produce anything and starting one on top of a transcription that is already using the
 * same cores.
 */
class SummaryQueueTest {

    @Test
    fun `a finished transcript with words in it can be summarised`() {
        assertNull(blocker())
    }

    @Test
    fun `refuses a recording that has never been transcribed`() {
        // The summary is read from the transcript, so there is nothing to read.
        assertEquals(SummaryBlocker.NO_TRANSCRIPT, blocker(state = null))
    }

    @Test
    fun `refuses a transcript that has not finished`() {
        listOf(TranscriptState.QUEUED, TranscriptState.RUNNING, TranscriptState.FAILED).forEach {
            assertEquals("state $it", SummaryBlocker.TRANSCRIPT_UNFINISHED, blocker(state = it))
        }
    }

    @Test
    fun `refuses a finished transcript that came out empty`() {
        // This is not hypothetical: a bug in language auto-detect made every transcript empty while
        // still marking it done. Summarising one costs ninety seconds to produce a summary of
        // nothing, and the user would rightly read that as the summariser being broken.
        assertEquals(SummaryBlocker.TRANSCRIPT_EMPTY, blocker(segmentCount = 0))
    }

    @Test
    fun `refuses while the model has not been downloaded`() {
        assertEquals(SummaryBlocker.MODEL_MISSING, blocker(isModelInstalled = false))
    }

    @Test
    fun `refuses while a transcription is running`() {
        // Both saturate the same performance cores through ggml. Run together, each makes the other
        // slower than running them in turn, and the phone gets hot enough to be throttled.
        assertEquals(SummaryBlocker.TRANSCRIBING, blocker(isTranscribing = true))
    }

    @Test
    fun `refuses while another summary is running`() {
        // The engine holds a mutex around the model, so a second run would not fail — it would sit
        // and wait, invisibly, with the user looking at a button that appears to have done nothing.
        assertEquals(SummaryBlocker.SUMMARISING, blocker(isSummarising = true))
    }

    @Test
    fun `reports the missing transcript before anything else`() {
        // Ordering is a UI decision, not an accident. Whatever else is wrong, "this call has no
        // transcript yet" is the one the user can act on, and the action differs from the others.
        assertEquals(
            SummaryBlocker.NO_TRANSCRIPT,
            blocker(state = null, isModelInstalled = false, isTranscribing = true)
        )
    }

    @Test
    fun `reports the missing model before a transient busy state`() {
        // Downloading is something the user must do; being busy resolves itself. Telling them to
        // wait, and only then telling them to download 3.5 GB, wastes the wait.
        assertEquals(
            SummaryBlocker.MODEL_MISSING,
            blocker(isModelInstalled = false, isTranscribing = true, isSummarising = true)
        )
    }

    @Test
    fun `an existing summary does not block a redo`() {
        // Redo exists precisely because the first attempt was not good enough.
        assertNull(blocker(isAlreadySummarised = true))
    }

    private fun blocker(
        state: TranscriptState? = TranscriptState.DONE,
        segmentCount: Int = 12,
        isModelInstalled: Boolean = true,
        isTranscribing: Boolean = false,
        isSummarising: Boolean = false,
        isAlreadySummarised: Boolean = false
    ): SummaryBlocker? = SummaryQueue.blocker(
        state = state,
        segmentCount = segmentCount,
        isModelInstalled = isModelInstalled,
        isTranscribing = isTranscribing,
        isSummarising = isSummarising,
        isAlreadySummarised = isAlreadySummarised
    )
}
