/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

/**
 * Which line of a transcript is being spoken at a given moment.
 *
 * **Per line, not per word, and that is a limit of the data rather than of the effort.** whisper
 * reports one start and end per segment; there are no word timings unless token timestamps are turned
 * on, which costs decoding time and storage per word. Sweeping a highlight through a sentence would
 * therefore be an animation pretending to be a measurement — the same objection as a visualiser that
 * moves whether or not it matches the audio.
 */
object TranscriptFollow {

    /**
     * Index of the line being spoken at [positionMs], or -1 when none is.
     *
     * A plain scan rather than a binary search: transcripts are hundreds of lines, this runs on a
     * position tick, and a scan cannot silently return nonsense if the segments ever arrive unsorted.
     */
    fun activeIndex(startsMs: List<Long>, positionMs: Long): Int {
        var best = -1
        var bestStart = Long.MIN_VALUE
        startsMs.forEachIndexed { index, start ->
            // The line being spoken is the latest one that has already begun. A call can open with
            // ringing, so before the first line begins the answer is honestly "none".
            if (start <= positionMs && start >= bestStart) {
                best = index
                bestStart = start
            }
        }
        return best
    }
}
