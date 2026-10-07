/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The hand-off between the thread that empties the `AudioRecord` ring and the thread that encodes —
 * issue #28b.
 *
 * **Why it exists.** Reading, downmixing, encoding, and writing to the file used to run on one thread,
 * so any stall anywhere in that chain stopped the ring being emptied, and the ring holds only ~80 ms.
 * Whatever overflows is discarded silently. The native handoff path solved this years ago by copying
 * frames out of the ring and advancing immediately (`audiohandoff.cpp`, *"DECOUPLE ring consumption
 * from downstream"*); this is the same move in Kotlin for the direct path.
 *
 * **The one rule: [offer] never waits.** Anything the reader waits for is time the ring is filling. A
 * full queue therefore drops the chunk and says so, rather than blocking — the loss is the same either
 * way, but this way it is counted rather than silent. The queue is sized so that only a stall far longer
 * than any encoder hiccup can fill it.
 *
 * Buffers are pooled because this runs 47 times a second for the length of a call, and the alternative
 * is a garbage-collection pause inside the capture loop — the very thing being defended against.
 */
class CaptureChunkQueue(
    private val chunkBytes: Int,
    capacityChunks: Int,
) {

    /** One PCM chunk. [bytes] is pooled and valid only until it is handed back with [recycle]. */
    class Chunk(val bytes: ByteArray, val length: Int)

    private val ready = ArrayBlockingQueue<Chunk>(capacityChunks + 1) // +1 so [close] can always signal
    private val pool = ArrayBlockingQueue<ByteArray>(capacityChunks + 1)
    private val capacity = capacityChunks
    private val poison = Chunk(ByteArray(0), -1)

    @Volatile private var closed = false

    /** Chunks the reader had to throw away because the encoder was too far behind. */
    @Volatile var droppedChunks: Long = 0L
        private set

    /** The deepest the queue ever got — how close a call ran to the edge, for a bug report. */
    @Volatile var peakDepth: Int = 0
        private set

    /** Chunks waiting to be encoded right now. */
    val depth: Int get() = ready.count { it !== poison }

    /**
     * Copies [len] bytes of [src] into the queue. Returns false when the chunk was dropped — either the
     * encoder is too far behind, or the queue is closed. NEVER blocks. Call from the reader thread only.
     */
    fun offer(src: ByteArray, len: Int): Boolean {
        if (closed) return false
        if (depth >= capacity) {
            droppedChunks++
            return false
        }
        val buf = pool.poll() ?: ByteArray(chunkBytes)
        System.arraycopy(src, 0, buf, 0, len)
        if (!ready.offer(Chunk(buf, len))) {
            pool.offer(buf)
            droppedChunks++
            return false
        }
        val d = depth
        if (d > peakDepth) peakDepth = d
        return true
    }

    /**
     * Waits up to [timeoutMs] for the next chunk. Returns null when nothing arrived, or when the queue
     * has closed and everything already in it has been handed out. Call from the encoder thread only.
     */
    fun take(timeoutMs: Long): Chunk? {
        val chunk = ready.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        if (chunk === poison) {
            ready.offer(poison) // stays put, so every later take also ends
            return null
        }
        return chunk
    }

    /** Hands a chunk's buffer back for reuse. */
    fun recycle(chunk: Chunk) {
        if (chunk === poison) return
        pool.offer(chunk.bytes)
    }

    /** Stops further offers and releases a taker that is waiting on an empty queue. */
    fun close() {
        closed = true
        ready.offer(poison)
    }
}
