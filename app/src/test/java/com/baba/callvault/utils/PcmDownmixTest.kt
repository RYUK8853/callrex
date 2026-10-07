/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the stereo→mono downmix both capture pipelines run every call. The properties that matter are
 * that the two call directions are averaged (never summed, which would clip on double-talk) and that a
 * truncated final chunk can't walk off the end of the buffer.
 */
class PcmDownmixTest {

    /** Builds interleaved little-endian stereo PCM-16 from (left, right) sample pairs. */
    private fun stereo(vararg pairs: Pair<Int, Int>): ByteArray {
        val out = ByteArray(pairs.size * 4)
        pairs.forEachIndexed { i, (l, r) ->
            out[i * 4] = (l and 0xFF).toByte()
            out[i * 4 + 1] = ((l shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = (r and 0xFF).toByte()
            out[i * 4 + 3] = ((r shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Reads the mono PCM-16 samples written to a destination buffer. */
    private fun monoSamples(dst: ByteArray, len: Int): List<Int> =
        (0 until len step 2).map { (dst[it].toInt() and 0xFF or (dst[it + 1].toInt() shl 8)).toShort().toInt() }

    @Test
    fun `averages each left-right pair`() {
        // Arrange
        val src = stereo(1000 to 2000, -400 to 400, 32767 to 32767)
        val dst = ByteArray(src.size / 2)

        // Act
        val written = PcmDownmix.stereoToMono(src, src.size, dst)

        // Assert
        assertEquals(src.size / 2, written)
        assertEquals(listOf(1500, 0, 32767), monoSamples(dst, written))
    }

    @Test
    fun `loud double-talk keeps the waveform intact instead of wrapping`() {
        // Arrange: both parties loud at once — the case that made summing unusable. A sum overflows
        // PCM-16 and wraps to the opposite sign, which is an audible click on every loud moment.
        val pairs = listOf(32767 to 32767, -32768 to -32768, 30000 to 20000, -30000 to -20000)
        val src = stereo(*pairs.toTypedArray())
        val dst = ByteArray(src.size / 2)

        // Act
        val written = PcmDownmix.stereoToMono(src, src.size, dst)

        // Assert: the mix stays on the same side of zero as the two inputs it came from.
        monoSamples(dst, written).zip(pairs).forEach { (mixed, pair) ->
            val bothPositive = pair.first > 0 && pair.second > 0
            assertTrue(
                "downmix of ${pair.first}/${pair.second} was $mixed — the sign flipped, so it wrapped",
                if (bothPositive) mixed > 0 else mixed < 0,
            )
        }
    }

    @Test
    fun `preserves a single active direction at half amplitude`() {
        // Arrange: only the far party is speaking, our uplink is silent.
        val src = stereo(0 to 10000)
        val dst = ByteArray(src.size / 2)

        // Act
        val written = PcmDownmix.stereoToMono(src, src.size, dst)

        // Assert
        assertEquals(listOf(5000), monoSamples(dst, written))
    }

    @Test
    fun `writes exactly half the consumed bytes`() {
        // Arrange
        val src = stereo(1 to 1, 2 to 2, 3 to 3, 4 to 4)
        val dst = ByteArray(src.size / 2)

        // Act + Assert
        assertEquals(8, PcmDownmix.stereoToMono(src, src.size, dst))
    }

    @Test
    fun `ignores a trailing partial frame instead of reading past it`() {
        // Arrange: one whole stereo frame plus three stray bytes (a truncated final chunk).
        val whole = stereo(100 to 300)
        val src = whole + byteArrayOf(1, 2, 3)
        val dst = ByteArray(src.size / 2)

        // Act
        val written = PcmDownmix.stereoToMono(src, src.size, dst)

        // Assert
        assertEquals(2, written)
        assertEquals(listOf(200), monoSamples(dst, written))
    }

    @Test
    fun `writes nothing for an empty chunk`() {
        assertEquals(0, PcmDownmix.stereoToMono(ByteArray(0), 0, ByteArray(8)))
    }

    @Test
    fun `honours the length argument rather than the buffer size`() {
        // Arrange: a reusable read buffer that is only partly filled.
        val src = stereo(1000 to 2000, 6000 to 8000) + ByteArray(64)
        val dst = ByteArray(src.size / 2)

        // Act: report only the first frame as read.
        val written = PcmDownmix.stereoToMono(src, 4, dst)

        // Assert
        assertEquals(2, written)
        assertEquals(listOf(1500), monoSamples(dst, written))
    }
}
