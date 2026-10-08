/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context

/**
 * Whether an ADB transport can exist at all right now — decided BEFORE spending the launch
 * attempts on it.
 *
 * The 2026-10-08 device log (Nothing A001, Callrex 2.4.8) measured the cost of not asking this
 * first: adbd stopped, USB debugging off, Wireless debugging off, and the enable gate refusing —
 * and the launcher still ran its full 3-attempt budget (~84 s) before reporting failure. The call
 * had long ended by then, the recording notification was removed, and the user read it as a crash.
 * Zero recordings on a phone where nobody had switched anything off: the app's own auto-recovery
 * was the thing that had quietly stopped working.
 *
 * The rule mirrors the existing style in this package: only a *positive* reading may call a
 * transport dead. Every `UNKNOWN` (property unreadable, setting state neither proven on nor off)
 * falls through to [Verdict.REACHABLE], because blocking a write that would have worked is exactly
 * the dead end this package exists to remove.
 *
 * Free of `Context` in [check] so every branch is unit-tested; [forContext] is the thin live reader.
 */
object TransportReadiness {

    /** Whether any ADB transport can be brought up right now. */
    enum class Verdict {
        /** Something exists (or will): keep the normal launch/retry path. */
        REACHABLE,

        /** adbd stopped, USB debugging proven off, and the app holds no WRITE_SECURE_SETTINGS. */
        DEAD_END_NO_GRANT,

        /** adbd stopped, USB debugging proven off, Wi-Fi proven not connected: the framework would
         * write `adb_wifi_enabled` straight back to 0, so enabling Wireless debugging is impossible. */
        DEAD_END_NO_WIFI,

        /** adbd stopped, USB debugging proven off, Wi-Fi is up — but the enable gate refuses the
         * write (the user switched Wireless debugging off with the override setting off). */
        DEAD_END_WD_OFF,
    }

    fun check(
        connected: Boolean,
        loopbackArmed: Boolean,
        wirelessDebuggingOn: Boolean,
        adbd: AdbdState,
        usbDebugging: UsbDebuggingState,
        wifi: WifiState,
        hasGrant: Boolean,
        mayEnableWirelessDebugging: Boolean,
    ): Verdict = when {
        // A transport exists right now, or adbd is already running under something.
        connected || loopbackArmed || wirelessDebuggingOn || adbd == AdbdState.RUNNING ->
            Verdict.REACHABLE
        // USB debugging keeps adbd alive and is itself a transport (cable or not).
        usbDebugging == UsbDebuggingState.ON -> Verdict.REACHABLE
        // The enable gate will let us write Wireless debugging on, so the launch attempts have a
        // working path to try (the transient WD bootstrap that arms the loopback listener).
        mayEnableWirelessDebugging -> Verdict.REACHABLE
        // Nothing exists, nothing is coming, and both reading answers are positive.
        adbd == AdbdState.STOPPED && usbDebugging == UsbDebuggingState.OFF -> when {
            !hasGrant -> Verdict.DEAD_END_NO_GRANT
            wifi == WifiState.NOT_CONNECTED -> Verdict.DEAD_END_NO_WIFI
            else -> Verdict.DEAD_END_WD_OFF
        }
        // Any unknown reading (adbd unreadable, USB debugging unproven, Wi-Fi state unknown): do
        // not cut a path that might work.
        else -> Verdict.REACHABLE
    }

    /**
     * Reads the live device state and runs [check]. [connected] is the caller's own knowledge of
     * the ADB session (the launcher knows best); everything else is read from the device now, so
     * the answer is as fresh as the launch attempt it guards.
     */
    fun forContext(context: Context, connected: Boolean): Verdict = check(
        connected = connected,
        loopbackArmed = AdbShell.isLoopbackArmed(context),
        wirelessDebuggingOn = AdbShell.isWirelessDebuggingEnabled(context),
        adbd = AdbShell.adbdState(),
        usbDebugging = AdbShell.usbDebuggingState(context),
        wifi = WifiState.of(context),
        hasGrant = AdbShell.hasWriteSecureSettings(context),
        mayEnableWirelessDebugging = AdbShell.wirelessDebuggingWriteAllowed(context),
    )
}
