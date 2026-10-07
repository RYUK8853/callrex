/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind the transport controls.
 *
 * Small, but the two things that would actually be noticed: a skip that runs off either end of the
 * recording, and a speed control that cannot be got back to normal.
 */
class PlaybackControlsTest {

    @Test
    fun skipping_forward_stops_at_the_end() {
        // Seeking past the end is not an error on MediaPlayer — it simply completes, which would make
        // "forward 10" near the end feel like "stop".
        assertEquals(300_000, PlaybackControls.skipTo(position = 295_000, deltaMs = 10_000, durationMs = 300_000))
    }

    @Test
    fun skipping_back_stops_at_the_start() {
        assertEquals(0, PlaybackControls.skipTo(position = 4_000, deltaMs = -10_000, durationMs = 300_000))
    }

    @Test
    fun an_ordinary_skip_just_moves() {
        assertEquals(75_000, PlaybackControls.skipTo(position = 65_000, deltaMs = 10_000, durationMs = 300_000))
        assertEquals(55_000, PlaybackControls.skipTo(position = 65_000, deltaMs = -10_000, durationMs = 300_000))
    }

    @Test
    fun skipping_is_safe_before_the_duration_is_known() {
        // Duration is 0 until the player is prepared, and a tap can land in that window.
        assertEquals(0, PlaybackControls.skipTo(position = 0, deltaMs = 10_000, durationMs = 0))
    }

    @Test
    fun the_speed_control_cycles_and_returns_to_normal() {
        // One button rather than a menu, so it must come back around: a user who speeds up a call and
        // cannot find their way back to 1x has been trapped by the control.
        var speed = 1f
        val seen = mutableListOf(speed)
        repeat(PlaybackControls.SPEEDS.size) {
            speed = PlaybackControls.nextSpeed(speed)
            seen += speed
        }

        assertEquals(PlaybackControls.SPEEDS + 1f, seen)
    }

    @Test
    fun an_unrecognised_speed_falls_back_to_normal() {
        // A restored or corrupted value must not leave the button doing nothing.
        assertEquals(PlaybackControls.SPEEDS.first(), PlaybackControls.nextSpeed(3.7f))
    }
}
