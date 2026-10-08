/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a cold launch may skip the attempt budget entirely.
 *
 * The 2026-10-08 Nothing A001 field log is the reference case: adbd stopped, USB debugging off,
 * Wireless debugging off, no write allowed — and the launcher still spent ~85 s on attempts before
 * failing. The probe must catch exactly that shape, and must never catch a phone where any of the
 * readings was simply unknown (that is how a fixable phone becomes an unfixable one).
 */
class TransportReadinessTest {

    private fun verdict(
        connected: Boolean = false,
        loopbackArmed: Boolean = false,
        wirelessDebuggingOn: Boolean = false,
        adbd: AdbdState = AdbdState.STOPPED,
        usbDebugging: UsbDebuggingState = UsbDebuggingState.OFF,
        wifi: WifiState = WifiState.CONNECTED,
        hasGrant: Boolean = true,
        mayEnableWirelessDebugging: Boolean = false,
    ) = TransportReadiness.check(
        connected = connected,
        loopbackArmed = loopbackArmed,
        wirelessDebuggingOn = wirelessDebuggingOn,
        adbd = adbd,
        usbDebugging = usbDebugging,
        wifi = wifi,
        hasGrant = hasGrant,
        mayEnableWirelessDebugging = mayEnableWirelessDebugging,
    )

    @Test
    fun `an existing connection is reachable`() {
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(connected = true, adbd = AdbdState.STOPPED, usbDebugging = UsbDebuggingState.OFF),
        )
    }

    @Test
    fun `an armed loopback listener is reachable even with adbd stopped`() {
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(loopbackArmed = true, adbd = AdbdState.STOPPED, usbDebugging = UsbDebuggingState.OFF),
        )
    }

    @Test
    fun `wireless debugging already on is reachable`() {
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(wirelessDebuggingOn = true, adbd = AdbdState.STOPPED, usbDebugging = UsbDebuggingState.OFF),
        )
    }

    @Test
    fun `adbd running counts as reachable regardless of the switches`() {
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(adbd = AdbdState.RUNNING, usbDebugging = UsbDebuggingState.OFF),
        )
    }

    @Test
    fun `usb debugging on is a transport`() {
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(usbDebugging = UsbDebuggingState.ON),
        )
    }

    @Test
    fun `a blocked adbd with a legal write path is reachable, not a dead end`() {
        // The enable gate would let us turn Wireless debugging on — the launch attempts have a
        // working bootstrap to try, so the probe must not short-circuit them.
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(mayEnableWirelessDebugging = true),
        )
    }

    @Test
    fun `the field-log shape is a dead end`() {
        // Nothing A001, 2026-10-08: adbd stopped, USB off, WD off, Wi-Fi up, grant held, gate
        // refusing the write.
        assertEquals(
            TransportReadiness.Verdict.DEAD_END_WD_OFF,
            verdict(wifi = WifiState.CONNECTED, hasGrant = true, mayEnableWirelessDebugging = false),
        )
    }

    @Test
    fun `no grant is its own dead end`() {
        assertEquals(
            TransportReadiness.Verdict.DEAD_END_NO_GRANT,
            verdict(hasGrant = false),
        )
    }

    @Test
    fun `no wifi is its own dead end`() {
        assertEquals(
            TransportReadiness.Verdict.DEAD_END_NO_WIFI,
            verdict(wifi = WifiState.NOT_CONNECTED),
        )
    }

    @Test
    fun `an unknown adbd reading never declares a dead end`() {
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(adbd = AdbdState.UNKNOWN, usbDebugging = UsbDebuggingState.OFF),
        )
    }

    @Test
    fun `an unknown usb reading never declares a dead end`() {
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(adbd = AdbdState.STOPPED, usbDebugging = UsbDebuggingState.UNKNOWN),
        )
    }

    @Test
    fun `an unknown wifi reading falls through to the write check, never to a dead end`() {
        // Unknown Wi-Fi means the framework might accept the write — same rule as the enable gate.
        assertEquals(
            TransportReadiness.Verdict.REACHABLE,
            verdict(wifi = WifiState.UNKNOWN, mayEnableWirelessDebugging = true),
        )
        assertEquals(
            TransportReadiness.Verdict.DEAD_END_WD_OFF,
            // …and with the gate refusing, the (unknown-but-failing) write is still a dead end,
            // because adbd is proven stopped and USB proven off.
            verdict(wifi = WifiState.UNKNOWN, mayEnableWirelessDebugging = false),
        )
    }
}
