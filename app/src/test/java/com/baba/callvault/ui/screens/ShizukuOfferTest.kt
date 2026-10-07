/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the setup screen's Shizuku card is allowed to claim, and to offer.
 *
 * The rule lives outside the composable because the wrong branch renders perfectly: a card headed
 * "Shizuku is running on this phone" over a stopped server, with a button that ends onboarding on a
 * phone that cannot record anything at all.
 */
class ShizukuOfferTest {

    @Test
    fun a_stopped_server_is_reported_as_stopped_even_when_shizuku_is_the_chosen_mode() {
        // The branch that told a Shizuku user "there is nothing to pair — setup is done" while their
        // server was down was reached by consulting the mode and never the liveness.
        assertEquals(
            ShizukuOffer.NotRunning,
            ShizukuOffer.from(ShizukuStatus.NOT_RUNNING, PrivilegedMode.SHIZUKU)
        )
        assertEquals(
            ShizukuOffer.NotRunning,
            ShizukuOffer.from(ShizukuStatus.NOT_RUNNING, PrivilegedMode.STANDALONE)
        )
    }

    @Test
    fun shizuku_is_never_offered_while_it_cannot_serve_a_recorder() {
        // The whole defect in one assertion: choosing Shizuku here passes the onboarding gate, because
        // a chosen Shizuku mode counts as a set-up transport whether or not its server is alive.
        assertFalse(ShizukuOffer.NotRunning.canChooseShizuku)
        assertFalse(ShizukuOffer.NeedsPermission.canChooseShizuku)
        assertFalse(ShizukuOffer.Active.canChooseShizuku)
        assertTrue(ShizukuOffer.Fork.canChooseShizuku)
    }

    @Test
    fun a_missing_shizuku_never_claims_to_be_installed() {
        // The card is not rendered at all in this state, but "not installed" must not fall through to
        // the fork if it ever is — that would offer a switch to an app that is not on the phone.
        assertEquals(
            ShizukuOffer.NotRunning,
            ShizukuOffer.from(ShizukuStatus.NOT_INSTALLED, PrivilegedMode.STANDALONE)
        )
    }

    @Test
    fun a_usable_shizuku_that_has_not_been_chosen_is_the_fork() {
        assertEquals(
            ShizukuOffer.Fork,
            ShizukuOffer.from(ShizukuStatus.READY, PrivilegedMode.STANDALONE)
        )
        // Running but unpermitted is still a real choice: granting is one tap inside Shizuku, and the
        // card walks the user through it once the mode is chosen.
        assertEquals(
            ShizukuOffer.Fork,
            ShizukuOffer.from(ShizukuStatus.NO_PERMISSION, PrivilegedMode.STANDALONE)
        )
    }

    @Test
    fun a_chosen_shizuku_reports_the_one_step_that_is_left() {
        assertEquals(
            ShizukuOffer.NeedsPermission,
            ShizukuOffer.from(ShizukuStatus.NO_PERMISSION, PrivilegedMode.SHIZUKU)
        )
    }

    @Test
    fun a_chosen_and_running_shizuku_is_the_only_state_that_may_say_setup_is_done() {
        assertEquals(
            ShizukuOffer.Active,
            ShizukuOffer.from(ShizukuStatus.READY, PrivilegedMode.SHIZUKU)
        )
    }
}
