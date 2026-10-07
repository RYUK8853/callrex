/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which backend starts the recorder, and what has to be torn down when the answer changes.
 *
 * Extracted from [RecorderBackend] so the rule can be tested without a device: the consequence of
 * getting it wrong is two recorders competing for the same audio input, which on a phone shows up as a
 * call recorded by neither.
 */
class BackendChoiceTest {

    @Test
    fun standalone_mode_starts_our_own_daemon() {
        assertEquals(BackendChoice.ADB, BackendChoice.of(PrivilegedMode.STANDALONE))
    }

    @Test
    fun shizuku_mode_starts_the_user_service() {
        assertEquals(BackendChoice.SHIZUKU, BackendChoice.of(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun switching_away_from_a_backend_tears_that_backend_down() {
        // The one that matters: leaving the old one running is how two recorders end up fighting.
        assertEquals(BackendChoice.ADB, BackendChoice.toTearDown(from = PrivilegedMode.STANDALONE, to = PrivilegedMode.SHIZUKU))
        assertEquals(BackendChoice.SHIZUKU, BackendChoice.toTearDown(from = PrivilegedMode.SHIZUKU, to = PrivilegedMode.STANDALONE))
    }

    @Test
    fun staying_in_the_same_mode_tears_nothing_down() {
        // Settings can re-save the same value; that must not kill a warm recorder mid-call.
        assertEquals(null, BackendChoice.toTearDown(from = PrivilegedMode.SHIZUKU, to = PrivilegedMode.SHIZUKU))
        assertEquals(null, BackendChoice.toTearDown(from = PrivilegedMode.STANDALONE, to = PrivilegedMode.STANDALONE))
    }
}
