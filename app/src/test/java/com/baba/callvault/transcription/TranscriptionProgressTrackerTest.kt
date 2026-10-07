/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock behind the percentage, which belongs to the run rather than to whatever screen happens
 * to be watching it (mirror176, issue #34).
 */
class TranscriptionProgressTrackerTest {

    private val estimate = 100_000L

    @Test
    fun a_run_that_has_started_never_reads_as_zero() {
        val tracker = TranscriptionProgressTracker()
        tracker.startRecording("call.ogg", estimate, nowMs = 0L)

        assertEquals(1, tracker.percent(reportedPercent = 0, nowMs = 0L))
    }

    @Test
    fun the_figure_climbs_between_whispers_anchors() {
        val tracker = TranscriptionProgressTracker()
        tracker.startRecording("call.ogg", estimate, nowMs = 0L)

        val early = tracker.percent(reportedPercent = 0, nowMs = 10_000L)
        val later = tracker.percent(reportedPercent = 0, nowMs = 50_000L)

        assertTrue("the figure stood still between anchors: $early then $later", later > early)
    }

    @Test
    fun the_figure_never_goes_backwards() {
        val tracker = TranscriptionProgressTracker()
        tracker.startRecording("call.ogg", estimate, nowMs = 0L)
        val high = tracker.percent(reportedPercent = 60, nowMs = 50_000L)

        val next = tracker.percent(reportedPercent = 0, nowMs = 51_000L)

        assertTrue("the figure retreated from $high to $next", next >= high)
    }

    /**
     * The rotation case. Asking again for the same recording — however often, and from wherever —
     * must not restart the clock, because the run behind it did not restart either.
     */
    @Test
    fun starting_the_same_recording_again_does_not_restart_the_clock() {
        val tracker = TranscriptionProgressTracker()
        tracker.startRecording("call.ogg", estimate, nowMs = 0L)
        val before = tracker.percent(reportedPercent = 40, nowMs = 50_000L)

        tracker.startRecording("call.ogg", estimate, nowMs = 50_000L)
        val after = tracker.percent(reportedPercent = 40, nowMs = 50_500L)

        assertEquals("the same recording restarted its own progress", before, after)
    }

    /** A different recording is a different clock: a batch must not open at the last one's figure. */
    @Test
    fun a_new_recording_starts_from_the_beginning_again() {
        val tracker = TranscriptionProgressTracker()
        tracker.startRecording("first.ogg", estimate, nowMs = 0L)
        val first = tracker.percent(reportedPercent = 80, nowMs = 50_000L)
        assertTrue("precondition: the first recording got somewhere", first > 50)

        tracker.startRecording("second.ogg", estimate, nowMs = 50_000L)

        assertEquals(1, tracker.percent(reportedPercent = 0, nowMs = 50_000L))
    }

    /**
     * A recording whose length is unknown gets no prediction, only whisper's real anchors — the
     * curve would otherwise be drawn against an estimate of nothing.
     */
    @Test
    fun without_an_estimate_only_real_progress_moves_the_figure() {
        val tracker = TranscriptionProgressTracker()
        tracker.startRecording("call.ogg", estimatedMs = 0L, nowMs = 0L)

        assertEquals(1, tracker.percent(reportedPercent = 0, nowMs = 60_000L))
        assertEquals(30, tracker.percent(reportedPercent = 30, nowMs = 60_000L))
    }

    @Test
    fun nothing_is_shown_before_a_recording_is_announced() {
        val tracker = TranscriptionProgressTracker()

        assertEquals(0, tracker.percent(reportedPercent = 40, nowMs = 10_000L))
    }
}
