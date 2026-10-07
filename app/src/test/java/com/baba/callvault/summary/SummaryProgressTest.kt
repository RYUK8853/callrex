/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.transcription.TranscriptionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The figure a summary shows while it runs.
 *
 * A summary reports far less often than a transcription: whisper emits a segment every few seconds,
 * this produces one anchor per chunk, and a chunk is about two minutes. Everything here is about
 * that gap.
 */
class SummaryProgressTest {

    @Test
    fun `nothing finished reads as nothing`() {
        assertEquals(0, SummaryProgress.chunkAnchor(completed = 0, total = 3))
    }

    @Test
    fun `chunks never claim the whole bar`() {
        // The merge runs after the last chunk and is the longest single generation of the run.
        // Reporting 100 there would park the bar at finished for all of it — the same defect as the
        // transcription that vanished at 70% because segment timestamps stop at the last words.
        assertEquals(SummaryProgress.CHUNKS_SHARE, SummaryProgress.chunkAnchor(completed = 3, total = 3))
        assertTrue(SummaryProgress.CHUNKS_SHARE < 100)
    }

    @Test
    fun `partway through reads partway`() {
        assertEquals(SummaryProgress.CHUNKS_SHARE / 2, SummaryProgress.chunkAnchor(completed = 2, total = 4))
    }

    @Test
    fun `more completed than exist cannot overshoot`() {
        assertEquals(SummaryProgress.CHUNKS_SHARE, SummaryProgress.chunkAnchor(completed = 9, total = 3))
    }

    @Test
    fun `an unknown chunk count does not divide by zero`() {
        // The total is zero until the runner has chunked, which is after the model has loaded — and
        // loading alone is seconds during which the bar is already on screen.
        assertEquals(0, SummaryProgress.chunkAnchor(completed = 0, total = 0))
        assertEquals(0, SummaryProgress.chunkAnchor(completed = 1, total = 0))
    }

    @Test
    fun `the estimate scales with the number of chunks`() {
        assertEquals(SummaryProgress.MILLIS_PER_CHUNK * 3, SummaryProgress.estimatedMs(3))
    }

    @Test
    fun `an unknown chunk count still estimates something`() {
        // Zero would tell TranscriptionProgress to predict nothing, and the bar would sit at 1%
        // through the entire model load with nothing to say it was working.
        assertEquals(SummaryProgress.MILLIS_PER_CHUNK, SummaryProgress.estimatedMs(0))
    }

    @Test
    fun `the figure keeps moving between chunk anchors`() {
        // The point of borrowing the transcription curve: two minutes can pass between anchors.
        val estimated = SummaryProgress.estimatedMs(2)

        val early = TranscriptionProgress.display(0, elapsedMs = 5_000, estimatedMs = estimated, previous = 0)
        val later = TranscriptionProgress.display(0, elapsedMs = 60_000, estimatedMs = estimated, previous = early)

        assertTrue("the bar stood still while the model worked", later > early)
    }

    @Test
    fun `a real anchor pulls the figure forward`() {
        val estimated = SummaryProgress.estimatedMs(2)
        val predicted = TranscriptionProgress.display(0, elapsedMs = 1_000, estimatedMs = estimated, previous = 0)

        val anchored = TranscriptionProgress.display(
            reportedPercent = SummaryProgress.chunkAnchor(completed = 1, total = 2),
            elapsedMs = 1_000,
            estimatedMs = estimated,
            previous = predicted
        )

        assertTrue("finishing a chunk must show", anchored > predicted)
    }
}
