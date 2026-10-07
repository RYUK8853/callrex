/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

/**
 * The arithmetic behind the transport controls, kept out of the player so it can be tested.
 *
 * Both rules exist because their failure is quiet rather than loud: a skip that overshoots the end
 * does not error, it just completes the track, and a speed control that cannot reach 1× again leaves
 * the user stuck with a chipmunk.
 */
object PlaybackControls {

    /** How far the skip buttons move. Ten seconds re-hears a sentence; five rarely does. */
    const val SKIP_MS = 10_000

    /**
     * The speeds the one-button control cycles through.
     *
     * A cycle rather than a menu because the button lives in a row of transport controls, and 1.25×
     * is included because a call at 1.5× is often just past the edge of comfortable.
     */
    val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

    /** Where a skip of [deltaMs] from [position] lands, clamped inside the recording. */
    fun skipTo(position: Int, deltaMs: Int, durationMs: Int): Int =
        (position + deltaMs).coerceIn(0, durationMs.coerceAtLeast(0))

    /** The next speed in the cycle; an unrecognised value returns to the first. */
    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOfFirst { it == current }
        if (index < 0) return SPEEDS.first()
        return SPEEDS[(index + 1) % SPEEDS.size]
    }
}
