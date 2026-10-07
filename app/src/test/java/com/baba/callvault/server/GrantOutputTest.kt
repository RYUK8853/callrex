/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the answers back, because the commands lie.
 *
 * `appops set --uid … RECORD_AUDIO allow` exits **0** and changes nothing on Android 14+ — the system
 * logs "Ignored setUidMode call for runtime permission app op" and carries on. Measured on an emulator
 * on 2026-08-24. An exit code is therefore not evidence that a grant happened.
 */
class GrantOutputTest {

    // ---- appops ---------------------------------------------------------------------------------

    @Test
    fun an_allowed_package_op_is_read_as_allowed() {
        // Real `appops get --user 0 <pkg> RECORD_AUDIO` output.
        val output = "Uid mode: RECORD_AUDIO: ignore\nRECORD_AUDIO: allow\n"

        assertTrue(GrantOutput.appOpAllowed(output, "RECORD_AUDIO"))
    }

    @Test
    fun the_uid_mode_line_is_not_mistaken_for_the_package_mode() {
        // The trap: this is what a "successful" uid-level grant leaves behind — uid mode untouched and
        // no package line at all. Reading the first RECORD_AUDIO line would call this a success.
        val output = "Uid mode: RECORD_AUDIO: ignore\n"

        assertFalse(GrantOutput.appOpAllowed(output, "RECORD_AUDIO"))
    }

    @Test
    fun an_ignored_op_is_not_allowed() {
        val output = "RECORD_AUDIO: ignore\n"

        assertFalse(GrantOutput.appOpAllowed(output, "RECORD_AUDIO"))
    }

    @Test
    fun a_different_op_being_allowed_proves_nothing_about_ours() {
        val output = "CAMERA: allow\n"

        assertFalse(GrantOutput.appOpAllowed(output, "RECORD_AUDIO"))
    }

    @Test
    fun empty_output_is_not_allowed() {
        assertFalse(GrantOutput.appOpAllowed("", "RECORD_AUDIO"))
    }

    // ---- roles ----------------------------------------------------------------------------------

    @Test
    fun a_package_listed_as_role_holder_is_read_as_holding_it() {
        assertTrue(GrantOutput.holdsRole("com.baba.callvault\n", "com.baba.callvault"))
    }

    @Test
    fun another_app_holding_the_role_is_not_us() {
        // What the emulator actually returns for DIALER before anything is changed.
        assertFalse(GrantOutput.holdsRole("com.google.android.dialer\n", "com.baba.callvault"))
    }

    @Test
    fun a_holder_list_is_matched_entry_by_entry() {
        // Some roles allow several holders; `get-role-holders` separates them by semicolons.
        val output = "com.google.android.dialer;com.baba.callvault\n"

        assertTrue(GrantOutput.holdsRole(output, "com.baba.callvault"))
    }

    @Test
    fun a_package_that_merely_contains_our_name_is_not_us() {
        // com.baba.callvault.instrtest is a different package, and -PisolateTestApp really does install
        // one. A substring check would call that a success.
        assertFalse(GrantOutput.holdsRole("com.baba.callvault.instrtest\n", "com.baba.callvault"))
    }

    @Test
    fun empty_output_means_nobody_holds_it() {
        assertFalse(GrantOutput.holdsRole("", "com.baba.callvault"))
    }
}
