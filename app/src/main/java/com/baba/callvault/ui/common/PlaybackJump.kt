/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.net.Uri
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController.Phase

/** How to reach a moment inside a recording, given what the player is doing right now. */
enum class PlaybackJump {

    /** The track is loaded and its duration known — seek straight to the position. */
    SEEK_NOW,

    /** Load the recording, carrying the position through to the prepared callback. */
    LOAD_THEN_SEEK;

    companion object {

        /**
         * Decides which of the two a jump needs.
         *
         * This is a pure function rather than a branch inside the player because the failure mode is
         * silent: `MediaPlayer.seekTo` on a track that is not prepared gets clamped against a duration
         * of zero, so a wrong answer here does not throw — the recording simply plays from the start,
         * and the timestamp the user tapped is quietly ignored.
         */
        fun planFor(activeUri: Uri?, phase: Phase, target: Uri): PlaybackJump {
            val samePlayer = activeUri == target
            val prepared = phase == Phase.PLAYING || phase == Phase.PAUSED
            return if (samePlayer && prepared) SEEK_NOW else LOAD_THEN_SEEK
        }
    }
}
