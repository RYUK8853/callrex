/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * What [AdbMdns] decided about one discovered adb service, and why.
 *
 * Discovery drops a service for three quite different reasons and used to do it in a single silent
 * `if`. On our own phones that is invisible and harmless; on a reporter's phone it is the difference
 * between a diagnosis and a shrug. Issue #23 — a Xiaomi on HyperOS stuck at "Call recorder starting
 * up" for good — arrived with a log that could not say whether mDNS had found nothing at all, found
 * our adb service and rejected it, or found someone else's. Each branch here is now one log line.
 */
enum class MdnsServiceVerdict {
    /** A genuine local adb daemon: this is the one to connect to. */
    ACCEPTED,

    /** The resolve came back without an address. Reading `.hostAddress` on it used to throw. */
    HOST_UNRESOLVED,

    /** Some other device's adb on the same network — a normal thing to see, and not ours. */
    HOST_NOT_ON_THIS_DEVICE,

    /**
     * The service advertises a port, but nothing is listening on `127.0.0.1:<port>`.
     *
     * The suspected shape of issue #23: a ROM whose `adbd` binds only the Wi-Fi address. Our probe
     * (bind loopback, expect it to fail) then *succeeds*, so the service reads as fake and discovery
     * reports nothing — with no error anywhere. If a report ever shows this line, that guess becomes a
     * measurement, and the fix is to dial the resolved address instead of probing loopback.
     */
    NOTHING_LISTENING_ON_LOOPBACK;

    companion object {

        /**
         * The gates, in order, with the reason the service was dropped.
         *
         * The probes are lambdas so a service rejected early never pays for the ones after it —
         * [isLoopbackPortTaken] opens a socket, and running that for another phone's adb service would
         * be a needless connection on every mDNS record on the network.
         */
        fun of(
            hostAddress: String?,
            isOneOfOurAddresses: () -> Boolean,
            isLoopbackPortTaken: () -> Boolean,
        ): MdnsServiceVerdict = when {
            hostAddress.isNullOrBlank() -> HOST_UNRESOLVED
            !isOneOfOurAddresses() -> HOST_NOT_ON_THIS_DEVICE
            !isLoopbackPortTaken() -> NOTHING_LISTENING_ON_LOOPBACK
            else -> ACCEPTED
        }
    }
}
