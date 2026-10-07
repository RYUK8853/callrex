/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the ADB operations currently in flight, so Wireless debugging is switched off only when the
 * **last** one finishes.
 *
 * Wireless debugging is enabled in one place — `AdbShell.connectViaWirelessDebugging`, the only writer
 * of `adb_wifi_enabled=1` — but it used to be switched off in quite another: the recorder launcher, once
 * the daemon's binder arrived. Every other caller that needed an ADB connection (the USB-default probe
 * behind the screen-lock warning, the log collector, the updater, the permissions screen) therefore
 * turned Wireless debugging on and left it on. Measured on the OP9: a reconnect at 19:45:19 with no
 * launch after it, and `adb_wifi_enabled` still 1 three minutes later. That is an open network port
 * outliving the thing that needed it, which is exactly the exposure that got the off-Wi-Fi loopback work
 * parked.
 *
 * A plain "disable when you are done" would be worse than the leak: `disableWirelessDebugging` drops the
 * app's embedded ADB connection, so a UI screen finishing its USB probe could cut the connection out
 * from under a recording being armed on another thread. Hence the count — releasing is safe only when
 * nobody else is mid-operation.
 *
 * Separate from [AdbShell] and free of Android types so the counting itself can be tested; the policy
 * for *whether* it is safe to switch off at all stays in [WirelessDebuggingPolicy].
 */
object WirelessDebuggingLease {

    private val inFlight = AtomicInteger(0)

    /** Marks the start of an operation that needs the ADB connection alive. */
    fun acquire() {
        inFlight.incrementAndGet()
    }

    /**
     * Marks the end of one operation.
     *
     * @return true when this was the last one in flight, and Wireless debugging may now be released.
     *   Never counts below zero: an unbalanced release must not make the next acquire look like the
     *   second user and leave the setting on for ever.
     */
    fun release(): Boolean = inFlight.updateAndGet { if (it > 0) it - 1 else 0 } == 0

    /** Whether nothing is currently using the ADB connection. */
    val isIdle: Boolean get() = inFlight.get() == 0

    @VisibleForTesting
    fun reset() {
        inFlight.set(0)
    }
}
