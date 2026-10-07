/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind issue #28b: audio the AudioRecord ring discarded because the capture loop was
 * late is invisible to Android, but it is not invisible to counting — a capture produces frames in real
 * time, so frames that the clock says should have arrived and did not are frames that were dropped.
 */
class RingOverrunLedgerTest {

    private val rate = 48_000

    @Test
    fun `reports nothing while every frame arrives on time`() {
        val ledger = RingOverrunLedger(rate, toleranceFrames = RING)
        var frames = 0L
        repeat(100) {
            frames += 1024
            ledger.sample(frames, frames.toNanos())
        }
        assertEquals(0L, ledger.lostFrames)
        assertEquals(0, ledger.overrunEvents)
        assertNull(ledger.summary())
    }

    @Test
    fun `counts audio that real time says never arrived`() {
        val ledger = RingOverrunLedger(rate, toleranceFrames = RING)
        ledger.sample(1024, 0)
        // A second of wall clock passed; only half a second of audio came back.
        assertTrue(ledger.sample(1024 + 24_000, 1_000_000_000L))
        assertEquals(24_000L, ledger.lostFrames)
        assertEquals(500L, ledger.lostMillis)
        assertEquals(1, ledger.overrunEvents)
    }

    @Test
    fun `start-up latency is baselined away rather than reported as loss`() {
        val ledger = RingOverrunLedger(rate, toleranceFrames = RING)
        // Measured on the OP9: the first sample already trails real time by ~5 000 frames, for ever,
        // simply because capture began after the clock did. Only what happens AFTER it is loss.
        ledger.sample(24_576, 657_000_000L)
        ledger.sample(24_576 + 48_000, 657_000_000L + 1_000_000_000L)
        assertEquals(0L, ledger.lostFrames)
        assertNull(ledger.summary())
    }

    @Test
    fun `lag the ring can still give back is not loss`() {
        val ledger = RingOverrunLedger(rate, toleranceFrames = RING)
        ledger.sample(1024, 0)
        // 100 ms behind, with a 160 ms ring: those frames are still in the ring, waiting to be read.
        assertFalse(ledger.sample(1024 + 43_200, 1_000_000_000L))
        assertEquals(0L, ledger.lostFrames)
    }

    @Test
    fun `an overrun already counted is not counted again`() {
        val ledger = RingOverrunLedger(rate, toleranceFrames = RING)
        ledger.sample(0, 0)
        assertTrue(ledger.sample(24_000, 1_000_000_000L))
        // Still 24 000 frames behind a second later — the same loss, not a second one.
        assertFalse(ledger.sample(72_000, 2_000_000_000L))
        assertEquals(1, ledger.overrunEvents)
        assertEquals(24_000L, ledger.lostFrames)
    }

    @Test
    fun `a second, larger overrun is counted as its own event`() {
        val ledger = RingOverrunLedger(rate, toleranceFrames = RING)
        ledger.sample(0, 0)
        ledger.sample(24_000, 1_000_000_000L)
        assertTrue(ledger.sample(24_000, 2_000_000_000L))
        assertEquals(2, ledger.overrunEvents)
        assertEquals(72_000L, ledger.lostFrames)
    }

    @Test
    fun `the summary names the loss in human terms`() {
        val ledger = RingOverrunLedger(rate, toleranceFrames = RING)
        ledger.sample(0, 0)
        ledger.sample(38_400, 1_000_000_000L)
        val summary = ledger.summary()!!
        assertTrue(summary, summary.contains("200 ms"))
        assertTrue(summary, summary.contains("1 event"))
    }

    private fun Long.toNanos(): Long = this * 1_000_000_000L / rate

    private companion object {
        /** A real ring: 7 680 frames (160 ms) on the OP9 at 48 kHz stereo. */
        const val RING = 7_680
    }
}
