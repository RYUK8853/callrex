/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import java.util.Locale

/** Formats a position within a call, for the timestamp beside each transcript line. */
object TranscriptTimestamp {

    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L

    /**
     * Renders [millis] as `m:ss`, or `h:mm:ss` once a call passes an hour.
     *
     * Truncates rather than rounds: a timestamp is a seek target, and rounding up would point past the
     * start of the very line it labels. Hours are kept rather than wrapped, so a ninety-minute call
     * does not show "0:30" at its midpoint.
     */
    fun format(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L)) / MILLIS_PER_SECOND
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        val minutes = totalMinutes % MINUTES_PER_HOUR
        val hours = totalMinutes / MINUTES_PER_HOUR

        // Locale.ROOT: this is a digital clock reading, not localised prose, and must not pick up
        // locale-specific digits that would not line up in a column.
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** A stamp read off the front of a line, and the line without it. */
    data class Leading(val millis: Long, val text: String)

    /**
     * Reads a `[m:ss]` or `[h:mm:ss]` prefix, or returns null.
     *
     * Exists for summaries: the model is asked to prefix an item with the moment it refers to,
     * copied verbatim out of the transcript, and a stamp beside a decision is a jump into the call —
     * the one thing a summary offers that reading the transcript does not.
     *
     * **The input is model output and is not trusted.** Anything that is not exactly a stamp comes
     * back as no stamp rather than as a guess: a wrong seek lands somewhere the user was never
     * promised, in a recording they cannot easily check it against. `1:75` is refused rather than
     * normalised to `2:15`, because normalising would turn something the model made up into a
     * plausible-looking jump point. A stamp in the middle of a sentence is prose, not a prefix, and
     * is left as prose.
     */
    fun parseLeading(line: String): Leading? {
        val match = LEADING_STAMP.find(line) ?: return null
        val (hours, minutes, seconds) = when (match.groupValues[2].isEmpty()) {
            // Two parts: m:ss.
            true -> Triple(0L, match.groupValues[1].toLong(), match.groupValues[3].toLong())
            // Three parts: h:mm:ss.
            false -> Triple(
                match.groupValues[1].toLong(),
                match.groupValues[2].trimEnd(':').toLong(),
                match.groupValues[3].toLong()
            )
        }
        if (seconds >= SECONDS_PER_MINUTE) return null
        if (hours > 0 && minutes >= MINUTES_PER_HOUR) return null

        val text = line.substring(match.range.last + 1).trim()
        if (text.isEmpty()) return null

        return Leading(
            millis = ((hours * MINUTES_PER_HOUR + minutes) * SECONDS_PER_MINUTE + seconds) * MILLIS_PER_SECOND,
            text = text
        )
    }

    /**
     * `[1:30]` or `[1:05:09]` at the very start, allowing leading whitespace.
     *
     * Anchored deliberately. The prompt asks for the stamp as a prefix, so one found anywhere else
     * was not offered as a jump point and must not be treated as one.
     */
    private val LEADING_STAMP = Regex("""^\s*\[(\d{1,2}):(\d{1,2}:)?(\d{1,2})]""")
}
