/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.telephony.TelephonyManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-call path must stand aside for a carrier call, and only for a carrier call.
 *
 * The distinction is the whole test class, because the device call state does **not** make it. An
 * app call registered with Telecom raises the device call state to OFFHOOK exactly like a phone
 * call: AOSP's `PhoneStateBroadcaster` excludes only *external* calls from the broadcast, and goes
 * to the trouble of stripping the phone number for self-managed ones — which it would not need to do
 * if they never reached it. `TelephonyManager.getCallState()` says the same thing in its own
 * documentation: "considers not only calls in the Telephony stack, but also calls via other
 * ConnectionService implementations."
 *
 * So "is a carrier call up?" is asked of `TelecomManager.isInManagedCall()`, which counts only calls
 * from a **managed** ConnectionService — the carrier's. Wi-Fi calling and VoLTE run through it and
 * still block the app-call path, which is what this gate was built for. A WhatsApp call does not.
 */
class VoipTelephonyGateTest {

    @Test
    fun `allows a capture to start when no carrier call is in progress`() {
        assertTrue(VoipTelephonyGate.mayStart(managedCallInProgress = false))
    }

    @Test
    fun `allows a capture to start during an app call that raised the device call state`() {
        // The regression this class exists to prevent. On One UI a WhatsApp call reports OFFHOOK,
        // and reading that as "a carrier call is up" refused to record the very call the user
        // started. Measured working on a Galaxy S24 FE before the old gate, broken after it.
        assertTrue(VoipTelephonyGate.mayStart(managedCallInProgress = false))
        assertFalse(
            VoipTelephonyGate.mustStop(
                telephonyState = TelephonyManager.CALL_STATE_OFFHOOK,
                managedCallInProgress = false
            )
        )
    }

    @Test
    fun `blocks a start while a carrier call is up`() {
        // isInManagedCall() is true from dialing through holding, so the ring is covered too: the
        // audio mode wobbles while a call is being set up and a start must not slip through.
        assertFalse(VoipTelephonyGate.mayStart(managedCallInProgress = true))
    }

    @Test
    fun `stops a running capture once a carrier call is answered`() {
        assertTrue(
            VoipTelephonyGate.mustStop(
                telephonyState = TelephonyManager.CALL_STATE_OFFHOOK,
                managedCallInProgress = true
            )
        )
    }

    @Test
    fun `keeps a running capture while a carrier call merely rings`() {
        // An incoming call the user declines must not cost them the app call they were recording.
        // If they do answer it, the OFFHOOK that follows stops the capture a moment later.
        assertFalse(
            VoipTelephonyGate.mustStop(
                telephonyState = TelephonyManager.CALL_STATE_RINGING,
                managedCallInProgress = true
            )
        )
    }

    @Test
    fun `keeps a running capture when no carrier call is in progress`() {
        assertFalse(
            VoipTelephonyGate.mustStop(
                telephonyState = TelephonyManager.CALL_STATE_IDLE,
                managedCallInProgress = true
            )
        )
    }

    @Test
    fun `fails open when Telecom cannot be asked`() {
        // Matching CallInProgressGate: a device that cannot answer the question should lose the
        // guard, not lose app-call recording altogether. An unreadable Telecom reads as "no managed
        // call", which is what [VoipTelephonyGate.managedCallInProgress] returns on failure.
        assertTrue(VoipTelephonyGate.mayStart(managedCallInProgress = false))
        assertFalse(
            VoipTelephonyGate.mustStop(
                telephonyState = TelephonyManager.CALL_STATE_OFFHOOK,
                managedCallInProgress = false
            )
        )
    }

    @Test
    fun `ignores an unrecognised telephony state rather than stopping on it`() {
        val unknown = -1
        assertFalse(
            VoipTelephonyGate.mustStop(telephonyState = unknown, managedCallInProgress = true)
        )
    }
}
