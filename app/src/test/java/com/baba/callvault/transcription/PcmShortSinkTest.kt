/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder's accumulator.
 *
 * It exists because the previous one staged the whole call as bytes in a `ByteArrayOutputStream`,
 * then copied it with `toByteArray()`, then copied it again into a `ShortArray` — three full-length
 * buffers alive at overlapping moments, before a single sample reached the resampler.
 */
class PcmShortSinkTest {

    /** Little-endian PCM-16, the only layout MediaCodec produces here. */
    private fun bytesOf(vararg values: Short): ByteBuffer {
        val buf = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putShort(it) }
        buf.flip()
        return buf
    }

    @Test
    fun `samples come back in the order they went in`() {
        val sink = PcmShortSink(4)
        sink.append(bytesOf(1, -2, 3), 6)

        assertEquals(3, sink.size)
        assertEquals(1.toShort(), sink.array[0])
        assertEquals((-2).toShort(), sink.array[1])
        assertEquals(3.toShort(), sink.array[2])
    }

    @Test
    fun `little-endian byte pairs are decoded as signed shorts`() {
        val sink = PcmShortSink(2)
        // 0x8000 is the most negative short; a sign error here would read it as +32768 and wrap.
        sink.append(bytesOf(Short.MIN_VALUE, Short.MAX_VALUE), 4)

        assertEquals(Short.MIN_VALUE, sink.array[0])
        assertEquals(Short.MAX_VALUE, sink.array[1])
    }

    @Test
    fun `it grows past its initial capacity without losing anything`() {
        val sink = PcmShortSink(2)
        for (i in 0 until 100) sink.append(bytesOf(i.toShort()), 2)

        assertEquals(100, sink.size)
        for (i in 0 until 100) assertEquals(i.toShort(), sink.array[i])
    }

    @Test
    fun `a correctly pre-sized sink never reallocates`() {
        // The point of pre-sizing from the container's declared duration: no growth, so no moment
        // where two full-length buffers are alive at once.
        val sink = PcmShortSink(50)
        val before = sink.array
        for (i in 0 until 25) sink.append(bytesOf(i.toShort(), i.toShort()), 4)

        assertEquals(50, sink.size)
        assertTrue("pre-sized sink should not have reallocated", before === sink.array)
    }

    @Test
    fun `only the declared byte count is read, not the whole buffer`() {
        // MediaCodec hands back a buffer larger than the data in it; reading further would append
        // stale audio from the previous callback.
        val buf = bytesOf(7, 8, 9)
        val sink = PcmShortSink(4)
        sink.append(buf, 4)

        assertEquals(2, sink.size)
        assertEquals(7.toShort(), sink.array[0])
        assertEquals(8.toShort(), sink.array[1])
    }

    @Test
    fun `an odd byte count does not read half a sample`() {
        val sink = PcmShortSink(4)
        sink.append(bytesOf(5, 6), 3)

        assertEquals("a trailing half-sample is dropped", 1, sink.size)
        assertEquals(5.toShort(), sink.array[0])
    }

    @Test
    fun `a zero-length append is harmless`() {
        val sink = PcmShortSink(4)
        sink.append(bytesOf(1), 0)

        assertEquals(0, sink.size)
    }

    @Test
    fun `a zero or negative initial capacity still works`() {
        val sink = PcmShortSink(0)
        sink.append(bytesOf(42), 2)

        assertEquals(1, sink.size)
        assertEquals(42.toShort(), sink.array[0])
    }
}
