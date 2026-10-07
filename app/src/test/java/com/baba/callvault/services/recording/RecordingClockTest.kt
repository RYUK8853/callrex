/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingClockTest {

    private var nowMs = 0L
    private val clock = RecordingClock { nowMs }

    @Test
    fun `reports null before a recording starts`() {
        assertNull(clock.audioElapsedMs())
    }

    @Test
    fun `counts elapsed time while running`() {
        clock.start()
        nowMs += 5_000

        assertEquals(5_000L, clock.audioElapsedMs())
    }

    @Test
    fun `does not advance while paused`() {
        // The whole reason this class exists. Wall time keeps moving; saved audio does not.
        clock.start()
        nowMs += 3_000
        clock.pause()
        nowMs += 60_000

        assertEquals(3_000L, clock.audioElapsedMs())
    }

    @Test
    fun `excludes the pause from time measured after resuming`() {
        clock.start()
        nowMs += 3_000
        clock.pause()
        nowMs += 60_000
        clock.resume()
        nowMs += 2_000

        // 3s before the pause plus 2s after it. The 60s pause is not in the file, so it is not here.
        assertEquals(5_000L, clock.audioElapsedMs())
    }

    @Test
    fun `banks every pause across several of them`() {
        clock.start()
        nowMs += 1_000
        clock.pause(); nowMs += 10_000; clock.resume()
        nowMs += 1_000
        clock.pause(); nowMs += 20_000; clock.resume()
        nowMs += 1_000

        assertEquals(3_000L, clock.audioElapsedMs())
    }

    @Test
    fun `a second pause while already paused does not restart the pause`() {
        // Would otherwise discard the first pause's start and undercount the gap.
        clock.start()
        nowMs += 1_000
        clock.pause()
        nowMs += 5_000
        clock.pause()
        nowMs += 5_000
        clock.resume()

        assertEquals(1_000L, clock.audioElapsedMs())
    }

    @Test
    fun `resume without a pause is ignored`() {
        clock.start()
        nowMs += 1_000
        clock.resume()

        assertEquals(1_000L, clock.audioElapsedMs())
    }

    @Test
    fun `starting again resets the banked pause total`() {
        // A reused instance must not carry the previous call's pauses into the next one.
        clock.start()
        nowMs += 1_000
        clock.pause(); nowMs += 10_000; clock.resume()

        clock.start()
        nowMs += 2_000

        assertEquals(2_000L, clock.audioElapsedMs())
    }

    @Test
    fun `reset returns it to reporting nothing`() {
        clock.start()
        nowMs += 1_000
        clock.reset()

        assertNull(clock.audioElapsedMs())
    }
}
