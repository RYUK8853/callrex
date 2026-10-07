/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every branch of [MinDurationPolicy.shouldDiscard]; each false is a recording that is NOT deleted. */
class MinDurationPolicyTest {

    @Test
    fun `discards a recording shorter than the threshold`() {
        assertTrue(MinDurationPolicy.shouldDiscard(durationMs = 3_000L, minSeconds = 5))
    }

    @Test
    fun `keeps a recording longer than the threshold`() {
        assertFalse(MinDurationPolicy.shouldDiscard(durationMs = 9_000L, minSeconds = 5))
    }

    @Test
    fun `keeps a recording exactly at the threshold`() {
        // "Shorter than 5 seconds" has to mean shorter than, or the setting deletes the very call it
        // says it keeps.
        assertFalse(MinDurationPolicy.shouldDiscard(durationMs = 5_000L, minSeconds = 5))
    }

    @Test
    fun `keeps everything when the setting is off`() {
        assertFalse(MinDurationPolicy.shouldDiscard(durationMs = 1L, minSeconds = 0))
    }

    @Test
    fun `keeps everything when the threshold is negative`() {
        // Not reachable from the UI, but a corrupt preference must not start deleting recordings.
        assertFalse(MinDurationPolicy.shouldDiscard(durationMs = 1L, minSeconds = -30))
    }

    @Test
    fun `keeps a recording whose duration could not be read`() {
        // Zero here means the extractor could not parse the container, NOT that the call was
        // instantaneous. Discarding on an unreadable duration would throw away a real recording on
        // the strength of a failed metadata read; the genuinely empty capture is caught by bytes.
        assertFalse(MinDurationPolicy.shouldDiscard(durationMs = 0L, minSeconds = 30))
        assertFalse(MinDurationPolicy.shouldDiscard(durationMs = -1L, minSeconds = 30))
    }

    @Test
    fun `the presets start with off`() {
        // The UI maps an unknown stored value onto the first option, so the first option has to be
        // the one that deletes nothing.
        assertTrue(MinDurationPolicy.PRESET_SECONDS.first() == 0)
    }
}
