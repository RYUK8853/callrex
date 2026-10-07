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

class TranscriptionProgressTest {

    private fun display(reported: Int, elapsed: Long, estimated: Long, previous: Int = 0) =
        TranscriptionProgress.display(reported, elapsed, estimated, previous)

    @Test
    fun `never shows zero once a run has started`() {
        // The whole point. Zero and one say the same thing about the work and opposite things about
        // whether the phone is alive.
        assertEquals(1, display(reported = 0, elapsed = 0, estimated = 60_000))
    }

    @Test
    fun `moves while whisper is silent between chunks`() {
        // whisper still says 0; a quarter of the expected time has passed.
        assertTrue(display(reported = 0, elapsed = 15_000, estimated = 60_000) > 1)
    }

    @Test
    fun `keeps moving even when the run overruns its estimate`() {
        // The failure this replaced: a fixed cap thirty points ahead of whisper's last anchor left
        // the figure stuck at 30 on a short call, because the first anchor is a third of the way in.
        // Later must always exceed earlier, however far past the estimate the run goes.
        val at1x = display(reported = 0, elapsed = 60_000, estimated = 60_000)
        val at2x = display(reported = 0, elapsed = 120_000, estimated = 60_000, previous = at1x)
        val at4x = display(reported = 0, elapsed = 240_000, estimated = 60_000, previous = at2x)
        assertTrue("stalled at $at1x", at2x > at1x)
        assertTrue("stalled at $at2x", at4x > at2x)
    }

    @Test
    fun `reads about ninety percent when the estimate says it is done`() {
        // Issue #33. The old curve read 75% at the estimated finish, so on a phone whose estimate is
        // calibrated the run ended while the bar still said three quarters and then jumped to the
        // end — a quarter of the bar that was never used.
        val shown = display(reported = 0, elapsed = 60_000, estimated = 60_000)
        assertTrue("reached $shown at the estimated finish", shown in 88..91)
    }

    @Test
    fun `tracks the clock before the estimate instead of racing ahead`() {
        // Half the expected time has passed, so about half of the bar before the finishing stretch.
        // Racing ahead would only move the stall to the end, which is the complaint.
        val shown = display(reported = 0, elapsed = 30_000, estimated = 60_000)
        assertTrue("read $shown at half the estimate", shown in 40..48)
    }

    @Test
    fun `never reaches a hundred on prediction alone`() {
        // Saying "done" to someone who then waits destroys every number shown afterwards.
        assertTrue(display(reported = 0, elapsed = 100_000_000, estimated = 60_000) < 100)
    }

    @Test
    fun `a real anchor always wins over a slower prediction`() {
        // The phone was faster than expected: trust whisper, not the clock.
        assertEquals(78, display(reported = 78, elapsed = 5_000, estimated = 60_000))
    }

    @Test
    fun `never goes backwards`() {
        assertEquals(44, display(reported = 0, elapsed = 0, estimated = 60_000, previous = 44))
    }

    @Test
    fun `stops short of finished while work continues`() {
        assertTrue(display(reported = 90, elapsed = 600_000, estimated = 60_000) < 100)
    }

    @Test
    fun `only a finished run reaches a hundred`() {
        assertEquals(100, display(reported = 100, elapsed = 60_000, estimated = 60_000))
    }

    @Test
    fun `without an estimate only real anchors move it`() {
        // An unknown length must not be predicted from: there is nothing to predict against.
        assertEquals(39, display(reported = 39, elapsed = 999_000, estimated = 0))
    }
}
