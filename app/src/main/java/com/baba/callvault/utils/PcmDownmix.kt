/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

/** PCM-16 helpers shared by both capture pipelines (the daemon's direct recorder and the handoff). */
object PcmDownmix {

    /**
     * Downmixes interleaved stereo PCM-16 (little-endian) into mono by AVERAGING each L/R pair, writing
     * to [dst] and returning the number of mono bytes written (= [srcLen] / 2, rounded down to a whole
     * frame).
     *
     * A stereo call capture carries the two directions on separate channels (uplink on one, the remote
     * party's downlink on the other). Averaging rather than summing is deliberate: it can never clip,
     * and double-talk — both parties speaking at once — is common enough on real calls that summing
     * would hard-clip constantly. The cost is ~6 dB against a single full-scale voice, but both voices
     * stay intelligible through the overlap.
     *
     * A trailing partial frame in [src] is left unconsumed; callers read whole frames so this only
     * guards against a truncated final chunk.
     */
    fun stereoToMono(src: ByteArray, srcLen: Int, dst: ByteArray): Int {
        var di = 0
        var si = 0
        while (si + 3 < srcLen) {
            val l = (src[si].toInt() and 0xFF or (src[si + 1].toInt() shl 8)).toShort().toInt()
            val r = (src[si + 2].toInt() and 0xFF or (src[si + 3].toInt() shl 8)).toShort().toInt()
            val m = (l + r) / 2
            dst[di] = (m and 0xFF).toByte()
            dst[di + 1] = ((m shr 8) and 0xFF).toByte()
            di += 2
            si += 4
        }
        return di
    }
}
