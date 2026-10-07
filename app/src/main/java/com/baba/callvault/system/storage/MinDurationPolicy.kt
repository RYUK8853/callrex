/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * Whether a just-finished recording is too short to be worth keeping.
 *
 * A misdial, a call that rang out, a wrong number answered and hung up — each leaves a file the user
 * has to delete by hand, and enough of them bury the calls that matter. This drops them at the moment
 * they finish, before anything is catalogued, transcribed or uploaded.
 *
 * A pure function with no Android dependency, because it is applied on **both** capture paths — the
 * carrier one through `RecordingForegroundService` and the app-call one through
 * `VoipRecordingCoordinator` — and the two must not be able to disagree about what "too short" means.
 */
object MinDurationPolicy {

    /** The presets offered in Settings, in seconds. 0 is "keep everything". */
    val PRESET_SECONDS = listOf(0, 5, 10, 30, 60)

    /**
     * True when a recording of [durationMs] should be discarded under a [minSeconds] threshold.
     *
     * Two deliberate refusals to discard:
     *
     * - **[minSeconds] of 0 or less** is the feature being off, and off must never delete.
     * - **[durationMs] of 0 or less** means the duration could not be read — a container the extractor
     *   would not parse, or a destination that has not finished writing. That is not evidence of a
     *   short call, and treating "unknown" as "discard" would throw away a real recording on the
     *   strength of a failed metadata read. An empty capture is caught separately, by the byte count.
     */
    fun shouldDiscard(durationMs: Long, minSeconds: Int): Boolean {
        if (minSeconds <= 0) return false
        if (durationMs <= 0L) return false
        return durationMs < minSeconds * 1_000L
    }
}
