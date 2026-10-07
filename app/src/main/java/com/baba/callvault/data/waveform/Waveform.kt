/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.waveform

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The shape of a recording, reduced to something drawable.
 *
 * Drawn from the audio itself rather than animated: a bar that moves whether or not it matches what is
 * playing tells the user nothing, and implies a measurement that was never taken.
 *
 * A ninety-minute call is roughly 86 million samples against a few hundred pixels of screen, so all
 * that matters is which sample survives each bucket. It is the **loudest**, because averaging speech
 * against its own pauses flattens a conversation into one grey smear — and the reason to look at a
 * waveform at all is to see where the talking is.
 */
object Waveform {

    /** How many bars a waveform is stored as. Enough to show structure, small enough to cache as text. */
    const val BUCKETS = 240

    /** Peaks in 0..1, one per bucket, always exactly [buckets] long. */
    fun reduce(samples: FloatArray, buckets: Int): FloatArray {
        if (buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        if (samples.isEmpty()) return out

        // Fractional bucket width, so a recording shorter than the bar still spreads across it rather
        // than crowding into the first few bars.
        val width = samples.size.toDouble() / buckets
        for (i in 0 until buckets) {
            val from = (i * width).toInt().coerceIn(0, samples.lastIndex)
            val to = ((i + 1) * width).toInt().coerceIn(from + 1, samples.size)
            var peak = 0f
            for (s in from until to) {
                val magnitude = abs(samples[s])
                if (magnitude > peak) peak = magnitude
            }
            out[i] = peak.coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * Peaks as text for storage.
     *
     * Two digits each: the drawing is a few hundred pixels tall at most, so more precision would be
     * stored and never seen.
     */
    fun encode(peaks: FloatArray): String =
        peaks.joinToString(",") { (it * 99).roundToInt().coerceIn(0, 99).toString() }

    /** Peaks from [encoded]; empty when it is absent or unreadable, which callers treat as "not yet". */
    fun decode(encoded: String): FloatArray {
        if (encoded.isBlank()) return FloatArray(0)
        val parts = encoded.split(',')
        val out = FloatArray(parts.size)
        parts.forEachIndexed { i, part ->
            val value = part.trim().toIntOrNull() ?: return FloatArray(0)
            out[i] = (value / 99f).coerceIn(0f, 1f)
        }
        return out
    }
}
