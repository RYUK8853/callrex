/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose switch it is.
 *
 * CallVault turns Wireless debugging on when it needs it and off when it is done, and that was applied
 * to the setting itself rather than to the app's own use of it: a user who switched Wireless debugging
 * on for their own reasons — adb to a PC, in the reporter's case — had it taken away again within a
 * second (#30, mirror176). "Only undo what you did" is the rule, and it lives here so it can be tested
 * without a device.
 */
class WirelessDebuggingOwnershipTest {

    @Test
    fun what_we_switched_on_we_may_switch_off() {
        assertTrue(
            WirelessDebuggingPolicy.mayRelease(
                plan = WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD,
                weEnabledIt = true,
            )
        )
    }

    @Test
    fun what_the_user_switched_on_is_left_alone() {
        // The bug in #30, in one line.
        assertFalse(
            WirelessDebuggingPolicy.mayRelease(
                plan = WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD,
                weEnabledIt = false,
            )
        )
    }

    @Test
    fun our_own_switch_still_stays_on_when_it_is_the_only_transport() {
        // Ownership does not override the rule that kept the daemon alive: dropping adbd's last
        // transport kills it, and that is true whoever turned the switch on.
        assertFalse(
            WirelessDebuggingPolicy.mayRelease(
                plan = WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT,
                weEnabledIt = true,
            )
        )
    }

    @Test
    fun neither_ours_nor_droppable_is_still_left_alone() {
        assertFalse(
            WirelessDebuggingPolicy.mayRelease(
                plan = WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT,
                weEnabledIt = false,
            )
        )
    }
}
