/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoipAppPolicyTest {

    @Test
    fun `records an app that has not been excluded`() {
        assertTrue(VoipAppPolicy.shouldRecord("com.whatsapp", setOf("org.telegram.messenger")))
    }

    @Test
    fun `does not record an excluded app`() {
        assertFalse(VoipAppPolicy.shouldRecord("org.telegram.messenger", setOf("org.telegram.messenger")))
    }

    @Test
    fun `records everything when nothing is excluded`() {
        // The upgrade path. An empty set must mean "record all", never "record none" — otherwise
        // every existing user silently stops recording app calls, with a screen that looks fine.
        assertTrue(VoipAppPolicy.shouldRecord("com.whatsapp", emptySet()))
    }

    @Test
    fun `records a call whose app could not be identified`() {
        // We cannot honour an exclusion we cannot match. An unwanted recording is one tap to delete;
        // a call never recorded is gone. Failing open is the recoverable direction.
        assertTrue(VoipAppPolicy.shouldRecord(null, setOf("com.whatsapp")))
        assertTrue(VoipAppPolicy.shouldRecord("", setOf("com.whatsapp")))
        assertTrue(VoipAppPolicy.shouldRecord("   ", setOf("com.whatsapp")))
    }

    @Test
    fun `turning an app off adds it to the exclusions`() {
        assertEquals(
            setOf("com.whatsapp"),
            VoipAppPolicy.withRecording(emptySet(), "com.whatsapp", record = false)
        )
    }

    @Test
    fun `turning an app back on removes it`() {
        assertEquals(
            emptySet<String>(),
            VoipAppPolicy.withRecording(setOf("com.whatsapp"), "com.whatsapp", record = true)
        )
    }

    @Test
    fun `flipping one app leaves the others alone`() {
        assertEquals(
            setOf("a", "c"),
            VoipAppPolicy.withRecording(setOf("a", "b", "c"), "b", record = true)
        )
    }

    @Test
    fun `turning on an app that was never off changes nothing`() {
        assertEquals(setOf("a"), VoipAppPolicy.withRecording(setOf("a"), "b", record = true))
    }
}
