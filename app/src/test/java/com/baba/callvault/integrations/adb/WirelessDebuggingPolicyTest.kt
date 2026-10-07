/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers when Wireless debugging may be switched off after the daemon starts.
 *
 * The rule exists because of a measured device failure, not a preference: `adbd` stops when its last
 * transport is removed, and the daemon is a child of an `adbd` shell, so switching off the only
 * transport kills it. On a Galaxy S24 FE with USB debugging off, that produced a two-minute loop —
 * launch, disable, daemon dies, relaunch, re-enable — and the app never reached "ready to record". The
 * same code looked correct on a OnePlus purely because USB debugging was on there.
 *
 * The case that must never regress is the last one: with neither transport, Wireless debugging stays on.
 */
class WirelessDebuggingPolicyTest {

    @Test
    fun `usb debugging keeps adbd alive, so wireless debugging can be switched off`() {
        // The only combination ever PROVEN on-device: toggling Wireless debugging on and off left
        // adbd's pid unchanged and the daemon alive.
        assertEquals(
            WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD,
            WirelessDebuggingPolicy.plan(isUsbDebuggingEnabled = true, isLoopbackArmed = false),
        )
    }

    @Test
    fun `an armed loopback is NOT enough to switch wireless debugging off`() {
        // The regression this file exists for. Assuming loopback was a live transport shipped in 1.4.8
        // and the churn came straight back: with loopback armed and USB debugging off, disabling
        // Wireless debugging still restarted adbd and killed the daemon 57 ms later. The tcp port is a
        // persisted setting, not a transport.
        assertEquals(
            WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT,
            WirelessDebuggingPolicy.plan(isUsbDebuggingEnabled = false, isLoopbackArmed = true),
        )
    }

    @Test
    fun `with neither transport, wireless debugging must stay on`() {
        assertEquals(
            WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT,
            WirelessDebuggingPolicy.plan(isUsbDebuggingEnabled = false, isLoopbackArmed = false),
        )
    }

    @Test
    fun `loopback does not change the answer when usb debugging is on`() {
        assertEquals(
            WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD,
            WirelessDebuggingPolicy.plan(isUsbDebuggingEnabled = true, isLoopbackArmed = true),
        )
    }

    @Test
    fun `the user is told only when wireless debugging has to stay on`() {
        assertTrue(
            WirelessDebuggingPolicy.mustKeepWirelessDebugging(WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT)
        )
        assertFalse(
            WirelessDebuggingPolicy.mustKeepWirelessDebugging(WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD)
        )
    }

    @Test
    fun `every combination is decided`() {
        // No input pair may fall through undecided — an unhandled case would silently disable WD.
        val plans = listOf(true, false).flatMap { usb ->
            listOf(true, false).map { loopback -> WirelessDebuggingPolicy.plan(usb, loopback) }
        }
        assertEquals(4, plans.size)
        // Only the two USB-enabled combinations may drop Wireless debugging.
        assertEquals(2, plans.count { it == WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT })
    }
}
