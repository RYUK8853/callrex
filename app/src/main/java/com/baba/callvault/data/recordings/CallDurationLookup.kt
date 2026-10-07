/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import com.baba.callvault.data.health.CallLogReader
import kotlin.math.abs

/**
 * How long each recorded call lasted, taken from the system call log.
 *
 * The duration is not ours to know: nothing in a recording's name or file records it, and reading it
 * back out of the media would mean opening every file — impossible for a Drive-only copy, which has
 * no local bytes at all. The call log already holds the exact figure for both, so it is read once per
 * refresh and matched by start time.
 *
 * **What this deliberately cannot answer**, in which case the caller shows no duration rather than a
 * guess:
 *  - **VoIP calls.** They never appear in the system call log.
 *  - **Calls older than the device's call-log retention.** The log is finite; recordings are not.
 *  - **Anything, without `READ_CALL_LOG`.** The permission is optional, and its absence must degrade
 *    to "unknown" rather than to an error.
 */
object CallDurationLookup {

    /**
     * How far a recording's start may sit from a call-log entry's and still be the same call.
     *
     * Recording starts after the call connects, and the filename timestamp is truncated to the second
     * while the call log stores millis, so an exact match never happens. Two minutes is comfortably
     * wider than that gap and far narrower than the space between two real calls.
     */
    private const val MATCH_TOLERANCE_MS = 120_000L

    /**
     * Durations in seconds, keyed by the recording start time they were matched to.
     *
     * @param startedAt every recording's start time; entries that match nothing are simply absent.
     */
    fun durationsFor(context: Context, startedAt: List<Long>): Map<Long, Long> {
        if (startedAt.isEmpty()) return emptyMap()

        // One query covering every recording on screen, rather than one per row.
        val oldest = startedAt.min() - MATCH_TOLERANCE_MS
        val entries = CallLogReader.entriesSince(context, oldest)
        if (entries.isEmpty()) return emptyMap()

        return startedAt.distinct().mapNotNull { start ->
            // Nearest entry within tolerance. Nearest rather than first, so back-to-back calls cannot
            // steal each other's duration when both fall inside the window.
            val best = entries.minByOrNull { abs(it.startedAt - start) } ?: return@mapNotNull null
            if (abs(best.startedAt - start) > MATCH_TOLERANCE_MS) return@mapNotNull null
            // A zero-second entry is a call that never connected; it has no duration worth showing.
            best.durationSeconds.takeIf { it > 0 }?.let { start to it }
        }.toMap()
    }
}
