/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Counts audio the `AudioRecord` ring discarded because the capture loop was late — issue #28b.
 *
 * **Why this has to be counted rather than caught.** When the ring overruns, Android drops the oldest
 * frames and tells nobody: no exception, no callback, no log. The next `read` simply returns the frames
 * that survived. Because every read returns a whole chunk, the audio either side of the loss still joins
 * on a chunk boundary — **the same splice signature as the encoder-drop fixed in 28a**. So a recording
 * cannot tell the two apart, and this ledger exists to answer, on the reporter's own device, which one
 * he is hitting.
 *
 * **The measurement is the clock, and that was decided by experiment, not by preference.** A capture is
 * a real-time producer: in a healthy second, 48 000 frames are read. Frames that the elapsed time says
 * should have arrived and did not are frames that fell out of the ring.
 *
 * The obvious-looking alternative — `AudioRecord.getTimestamp`, asking the hardware where it is — was
 * built first and **measured to be blind to exactly this failure** (OP9, 2026-09-05, a 500 ms stall
 * forced into the read loop every 25 chunks). The clock saw 3.5 s of audio missing; `framePosition`
 * stayed within a few hundred frames of what we had read the whole time, and twice went *negative*. It
 * reports frames delivered to the client, not frames the hardware produced, so it moves only when we
 * read — and a ledger built on it silently reports a clean run through a catastrophic one. Do not
 * reintroduce it.
 *
 * [toleranceFrames] is the lag that is normal rather than lost: reading can legitimately trail real time
 * by up to the ring's own occupancy and then catch up, because those frames are still in the ring. A
 * deficit LARGER than the ring cannot be caught up — that audio is gone.
 *
 * Single-threaded by design — only the reader thread touches it — so nothing here is synchronised.
 */
class RingOverrunLedger(
    private val sampleRate: Int,
    private val toleranceFrames: Int = 0,
) {

    private var baselined = false
    private var nanosBase = 0L
    private var readBase = 0L

    /** The largest deficit seen, i.e. how much audio never reached the encoder. */
    var lostFrames: Long = 0L
        private set

    /** How many times the deficit grew — one call losing 100 ms twice is two events. */
    var overrunEvents: Int = 0
        private set

    /** [lostFrames] in milliseconds, which is the unit a bug report can be read in. */
    val lostMillis: Long get() = lostFrames * 1_000L / sampleRate

    /**
     * Records one observation: [framesRead] is the running total of frames read from the ring and
     * [elapsedNanos] a monotonic reading taken beside it. The first call establishes the origin — the
     * frames not yet arrived when capture began are start-up latency, not loss.
     *
     * Returns true when this sample revealed a NEW loss, so the caller can log it once.
     */
    fun sample(framesRead: Long, elapsedNanos: Long): Boolean {
        if (!baselined) {
            baselined = true
            nanosBase = elapsedNanos
            readBase = framesRead
            return false
        }
        val expected = (elapsedNanos - nanosBase) * sampleRate / 1_000_000_000L
        val deficit = expected - (framesRead - readBase)
        if (deficit <= toleranceFrames || deficit <= lostFrames) return false
        lostFrames = deficit
        overrunEvents++
        return true
    }

    /** A line for the log, or null when the capture kept up — the normal case, which stays silent. */
    fun summary(): String? {
        if (lostFrames <= 0L) return null
        return "AudioRecord ring overrun: $lostFrames frame(s) ($lostMillis ms) never reached the " +
            "encoder, across $overrunEvents event(s) — the device could not keep up with the capture loop"
    }
}
