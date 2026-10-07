/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import com.baba.callvault.services.recording.handoff.AudioHandoffNative.DrainExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codes here must match the `DRAIN_EXIT_*` defines in `audiohandoff.cpp`.
 *
 * Nothing in the build checks that across the JNI boundary, and a silent drift would mislabel the one
 * line that says whether a recording is whole — reporting a truncated call as a clean stop. These
 * assertions are the contract, written out so a change on either side has to change them too.
 */
class DrainExitTest {

    @Test
    fun `codes match the native defines`() {
        assertEquals(0, DrainExit.STOPPED.code)
        assertEquals(1, DrainExit.INVALIDATED.code)
        assertEquals(2, DrainExit.STALLED.code)
        assertEquals(3, DrainExit.MMAP_FAILED.code)
        assertEquals(4, DrainExit.MAX_SECONDS.code)
        assertEquals(5, DrainExit.PIPE_BROKEN.code)
    }

    @Test
    fun `only a stop request counts as a clean end`() {
        // Every other exit means the capture ended while the call was still going, i.e. the user's
        // recording is short. None of them may be reported as normal.
        assertTrue(DrainExit.STOPPED.isClean)
        DrainExit.entries.filter { it != DrainExit.STOPPED }.forEach {
            assertFalse("$it must not be treated as a clean end", it.isClean)
        }
    }

    @Test
    fun `an unrecognised code degrades to UNKNOWN rather than throwing`() {
        // A drain that ends in a way this version does not know about must still be reportable — an
        // exception here would run on the drain thread at the exact moment something went wrong.
        assertEquals(DrainExit.UNKNOWN, DrainExit.of(99))
        assertEquals(DrainExit.UNKNOWN, DrainExit.of(-7))
        assertFalse(DrainExit.UNKNOWN.isClean)
    }

    @Test
    fun `every native code maps back to its own entry`() {
        DrainExit.entries.filter { it != DrainExit.UNKNOWN }.forEach {
            assertEquals(it, DrainExit.of(it.code))
        }
    }

    @Test
    fun `drain stats read back in the order native writes them`() {
        // The four int64s native fills: bytesStreamed, droppedFrames, overrunEvents, elapsedMs.
        // Getting this order wrong would report loss as duration and read plausibly either way.
        val buf = java.nio.ByteBuffer
            .allocateDirect(AudioHandoffNative.STATS_BYTES)
            .order(java.nio.ByteOrder.nativeOrder())
        buf.asLongBuffer().apply { put(0, 1_000L); put(1, 480L); put(2, 2L); put(3, 61_000L) }

        val stats = AudioHandoffNative.DrainStats.read(buf)

        assertEquals(1_000L, stats.bytesStreamed)
        assertEquals(480L, stats.droppedFrames)
        assertEquals(2L, stats.overrunEvents)
        assertEquals(61_000L, stats.elapsedMs)
        assertTrue("dropped frames are audio loss and must be flagged", stats.hasLoss)
    }

    @Test
    fun `no dropped frames is not reported as loss`() {
        val buf = java.nio.ByteBuffer
            .allocateDirect(AudioHandoffNative.STATS_BYTES)
            .order(java.nio.ByteOrder.nativeOrder())
        buf.asLongBuffer().apply { put(0, 9_999L); put(1, 0L); put(2, 0L); put(3, 30_000L) }

        assertFalse(AudioHandoffNative.DrainStats.read(buf).hasLoss)
    }
}
