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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deciding how to reach a moment inside a recording.
 *
 * Worth its own pure function because getting it wrong is invisible: `MediaPlayer.seekTo` on a track
 * that has not been prepared is clamped against a duration of zero, so a mis-planned jump does not
 * fail — it quietly plays from the beginning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackJumpTest {

    private val callA: Uri = Uri.parse("content://recordings/a.ogg")
    private val callB: Uri = Uri.parse("content://recordings/b.ogg")

    @Test
    fun seeks_within_the_track_that_is_already_playing() {
        assertEquals(
            PlaybackJump.SEEK_NOW,
            PlaybackJump.planFor(activeUri = callA, phase = Phase.PLAYING, target = callA)
        )
    }

    @Test
    fun seeks_within_a_paused_track_rather_than_restarting_it() {
        // The player is still prepared while paused, so restarting would lose the user's position
        // for no reason and re-open the file.
        assertEquals(
            PlaybackJump.SEEK_NOW,
            PlaybackJump.planFor(activeUri = callA, phase = Phase.PAUSED, target = callA)
        )
    }

    @Test
    fun loads_a_different_recording_before_seeking_into_it() {
        // The case that makes search useful: the moment being jumped to is in a call that is not
        // the one currently loaded.
        assertEquals(
            PlaybackJump.LOAD_THEN_SEEK,
            PlaybackJump.planFor(activeUri = callA, phase = Phase.PLAYING, target = callB)
        )
    }

    @Test
    fun loads_when_nothing_is_playing_at_all() {
        assertEquals(
            PlaybackJump.LOAD_THEN_SEEK,
            PlaybackJump.planFor(activeUri = null, phase = Phase.IDLE, target = callA)
        )
    }

    @Test
    fun reloads_the_same_recording_when_it_is_still_loading() {
        // LOADING means no duration is known yet, so a seek would be clamped to zero. Re-issuing the
        // load carries the position through the prepared callback instead.
        assertEquals(
            PlaybackJump.LOAD_THEN_SEEK,
            PlaybackJump.planFor(activeUri = callA, phase = Phase.LOADING, target = callA)
        )
    }

    @Test
    fun reloads_a_recording_whose_last_attempt_errored() {
        // After an error the player has been released; seeking into it would silently do nothing.
        assertEquals(
            PlaybackJump.LOAD_THEN_SEEK,
            PlaybackJump.planFor(activeUri = callA, phase = Phase.ERROR, target = callA)
        )
    }
}
