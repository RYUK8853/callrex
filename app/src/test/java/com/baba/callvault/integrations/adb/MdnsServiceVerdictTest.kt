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
import org.junit.Test

/**
 * Why a discovered adb service was taken or dropped.
 *
 * The reason this is a value and not three inline conditions: on a phone we do not own, a dropped
 * service is invisible. Issue #23 (Xiaomi, HyperOS, Android 17) reported the recorder stuck at
 * "starting up" for good, and the exported log could not say whether mDNS had found nothing, found
 * something and rejected it, or been refused a connection — because this code path logged nothing at
 * all. Every branch below is a line in that log now.
 */
class MdnsServiceVerdictTest {

    @Test
    fun a_service_on_this_device_with_adbd_listening_is_accepted() {
        assertEquals(
            MdnsServiceVerdict.ACCEPTED,
            MdnsServiceVerdict.of("192.168.1.42", isOneOfOurAddresses = { true }, isLoopbackPortTaken = { true }),
        )
    }

    @Test
    fun a_resolve_without_an_address_is_named_rather_than_crashing() {
        // `NsdServiceInfo.host` can come back null; reading `.hostAddress` on it used to throw here,
        // and the throw was swallowed by the caller — a silent drop with a stack trace nobody saw.
        assertEquals(
            MdnsServiceVerdict.HOST_UNRESOLVED,
            MdnsServiceVerdict.of(null, isOneOfOurAddresses = { true }, isLoopbackPortTaken = { true }),
        )
        assertEquals(
            MdnsServiceVerdict.HOST_UNRESOLVED,
            MdnsServiceVerdict.of("", isOneOfOurAddresses = { true }, isLoopbackPortTaken = { true }),
        )
    }

    @Test
    fun another_phones_adb_service_on_the_same_network_is_dropped() {
        assertEquals(
            MdnsServiceVerdict.HOST_NOT_ON_THIS_DEVICE,
            MdnsServiceVerdict.of("192.168.1.99", isOneOfOurAddresses = { false }, isLoopbackPortTaken = { true }),
        )
    }

    @Test
    fun nothing_listening_on_loopback_is_its_own_verdict_not_a_shrug() {
        // The suspected shape of issue #23: adbd advertises, but binds only the Wi-Fi address, so our
        // 127.0.0.1 probe binds successfully and we conclude "not a real adb service". Naming it is the
        // whole point — it is indistinguishable from "found nothing" in a log that says neither.
        assertEquals(
            MdnsServiceVerdict.NOTHING_LISTENING_ON_LOOPBACK,
            MdnsServiceVerdict.of("192.168.1.42", isOneOfOurAddresses = { true }, isLoopbackPortTaken = { false }),
        )
    }

    @Test
    fun a_failed_gate_does_not_run_the_probes_after_it() {
        // The loopback probe opens a socket. An unresolved host must not reach it.
        var probed = false
        MdnsServiceVerdict.of(null, isOneOfOurAddresses = { true }, isLoopbackPortTaken = { probed = true; true })
        assertFalse("the loopback probe ran for a service we had already rejected", probed)

        probed = false
        MdnsServiceVerdict.of("10.0.0.5", isOneOfOurAddresses = { false }, isLoopbackPortTaken = { probed = true; true })
        assertFalse("the loopback probe ran for a service on another device", probed)
    }
}
