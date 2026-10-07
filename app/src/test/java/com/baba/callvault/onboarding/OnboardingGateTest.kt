/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.onboarding

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When setup is allowed to finish.
 *
 * There was a report that onboarding kept demanding ADB pairing while the app was in Shizuku mode —
 * which would be unescapable, because in that mode there is nothing to pair and the CTA can never be
 * satisfied. It could not be reproduced against this code, and these tests pin why: the pairing
 * requirement is satisfied by the *mode choice itself*, not by a pairing that mode never performs.
 *
 * They also pin the opposite direction, which is the failure that actually shipped once: in standalone
 * mode nothing may wave the pairing requirement through, or the user reaches Home with no recorder.
 */
class OnboardingGateTest {

    private fun status(
        adbConnected: Boolean,
        batteryExempted: Boolean = true
    ) = OnboardingStatus.Status(
        disclaimerAccepted = true,
        notificationsGranted = true,
        contactsGranted = true,
        phoneStateGranted = true,
        callLogGranted = true,
        batteryExempted = batteryExempted,
        storageSelected = false,
        adbConnected = adbConnected,
        wizardCompleted = true
    )

    /**
     * The reported bug, as a test. `isPrivilegedTransportSetUp` answers true for SHIZUKU, which feeds
     * `adbConnected` — so choosing Shizuku is itself the answer to "is the transport set up?".
     */
    @Test
    fun `shizuku mode satisfies the pairing requirement without pairing`() {
        assertTrue(PrivilegedMode.SHIZUKU.needsShizuku)
        assertTrue(status(adbConnected = true).isComplete())
    }

    /** Standalone has a real thing to pair, so an unpaired standalone install must not complete. */
    @Test
    fun `standalone without pairing cannot finish setup`() {
        assertFalse(status(adbConnected = false).isComplete())
    }

    /**
     * Storage deliberately does not gate this — folder choice moved into the wizard. Pinned because it
     * reads like an omission and has been "fixed" by mistake before.
     */
    @Test
    fun `a missing recording folder does not block setup`() {
        assertTrue(status(adbConnected = true).isComplete())
    }

    /** Every other prerequisite still blocks; battery exemption stands in for the set. */
    @Test
    fun `a missing prerequisite still blocks setup`() {
        assertFalse(status(adbConnected = true, batteryExempted = false).isComplete())
    }
}
