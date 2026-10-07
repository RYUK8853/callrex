/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedModeTest {

    @Test
    fun every_mode_round_trips_through_its_key() {
        PrivilegedMode.entries.forEach { mode ->
            assertEquals(mode, PrivilegedMode.fromKey(mode.key))
        }
    }

    @Test
    fun an_unknown_key_falls_back_to_standalone() {
        // A mode retired in a later version, or a preference file from a build that had more of them.
        assertEquals(PrivilegedMode.STANDALONE, PrivilegedMode.fromKey("root"))
    }

    @Test
    fun no_stored_preference_means_standalone() {
        // The important one: an existing install has never written this key, and must keep working
        // exactly as it does today rather than waking up in a mode it was never set to.
        assertEquals(PrivilegedMode.STANDALONE, PrivilegedMode.fromKey(null))
    }

    @Test
    fun only_shizuku_mode_depends_on_another_app() {
        assertTrue(PrivilegedMode.SHIZUKU.needsShizuku)
        assertFalse(PrivilegedMode.STANDALONE.needsShizuku)
    }

    @Test
    fun only_standalone_needs_our_own_adb_setup() {
        // What onboarding branches on, and what tells the wireless-debugging plumbing to stay quiet.
        assertTrue(PrivilegedMode.STANDALONE.needsAdbSetup)
        assertFalse(PrivilegedMode.SHIZUKU.needsAdbSetup)
    }
}
