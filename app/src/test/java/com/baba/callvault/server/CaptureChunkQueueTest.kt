/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * The hand-off between the reader thread and the encoder thread (issue #28b). The reader must never
 * wait on the encoder: whatever it waits for, the AudioRecord ring is filling behind it.
 */
class CaptureChunkQueueTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `a chunk comes out holding what went in`() {
        val q = CaptureChunkQueue(chunkBytes = 8, capacityChunks = 2)
        assertTrue(q.offer(bytes(1, 2, 3, 4), 4))
        val chunk = q.take(TIMEOUT_MS)!!
        assertEquals(4, chunk.length)
        assertArrayEquals(bytes(1, 2, 3, 4), chunk.bytes.copyOf(4))
    }

    @Test
    fun `offer never blocks once the queue is full, and says so`() {
        val q = CaptureChunkQueue(chunkBytes = 4, capacityChunks = 2)
        assertTrue(q.offer(bytes(1), 1))
        assertTrue(q.offer(bytes(2), 1))
        assertFalse("a full queue must refuse, not block", q.offer(bytes(3), 1))
        assertEquals(1L, q.droppedChunks)
    }

    @Test
    fun `recycled buffers are handed back out instead of allocating fresh ones`() {
        val q = CaptureChunkQueue(chunkBytes = 4, capacityChunks = 2)
        q.offer(bytes(1, 2), 2)
        val first = q.take(TIMEOUT_MS)!!
        q.recycle(first)
        q.offer(bytes(3, 4), 2)
        val second = q.take(TIMEOUT_MS)!!
        assertTrue("the pool should have returned the same array", first.bytes === second.bytes)
    }

    @Test
    fun `take returns null rather than waiting for ever when nothing arrives`() {
        val q = CaptureChunkQueue(chunkBytes = 4, capacityChunks = 2)
        assertNull(q.take(timeoutMs = 5))
    }

    @Test
    fun `depth is reported so a bug report can show how close the encoder ran to the edge`() {
        val q = CaptureChunkQueue(chunkBytes = 4, capacityChunks = 4)
        repeat(3) { q.offer(bytes(1), 1) }
        q.take(TIMEOUT_MS)
        assertEquals(3, q.peakDepth)
        assertEquals(2, q.depth)
    }

    @Test
    fun `a waiting taker is released when the queue closes`() {
        val q = CaptureChunkQueue(chunkBytes = 4, capacityChunks = 2)
        var woke = false
        val t = thread { q.take(timeoutMs = 10_000); woke = true }
        Thread.sleep(50)
        q.close()
        t.join(2_000)
        assertTrue("close() must wake a blocked taker", woke)
    }

    @Test
    fun `a closed queue accepts nothing further`() {
        val q = CaptureChunkQueue(chunkBytes = 4, capacityChunks = 2)
        q.close()
        assertFalse(q.offer(bytes(1), 1))
    }

    private companion object {
        const val TIMEOUT_MS = 1_000L
    }
}
