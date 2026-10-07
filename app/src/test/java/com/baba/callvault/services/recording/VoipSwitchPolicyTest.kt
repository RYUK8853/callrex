/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import com.baba.callvault.services.recording.VoipSwitchPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** The full switch sequence, state by state. */
class VoipSwitchPolicyTest {

    private val normal = AudioManager.MODE_NORMAL
    private val voip = AudioManager.MODE_IN_COMMUNICATION
    private val cell = AudioManager.MODE_IN_CALL

    @Test
    fun `an app call appearing starts a recording`() {
        assertEquals(
            Action.START,
            VoipSwitchPolicy.decide(voip, callActive = false, suspendedForCarrier = false)
        )
    }

    @Test
    fun `an app call ending finalises the recording`() {
        assertEquals(
            Action.END,
            VoipSwitchPolicy.decide(normal, callActive = true, suspendedForCarrier = false)
        )
    }

    @Test
    fun `while a phone call is up, a held app call waits`() {
        assertEquals(
            Action.HOLD,
            VoipSwitchPolicy.decide(cell, callActive = true, suspendedForCarrier = true)
        )
    }

    @Test
    fun `returning to a held app call resumes it, never starts a second one`() {
        // THE defect. Without the resume branch this reads as a brand new app call and opens a
        // second file, which is how one conversation ended up as two recordings.
        assertEquals(
            Action.RESUME,
            VoipSwitchPolicy.decide(voip, callActive = true, suspendedForCarrier = true)
        )
    }

    @Test
    fun `hanging up the app call while on the phone finalises it when the phone call ends`() {
        // Mode goes straight from IN_CALL to NORMAL: the app call never comes back, so the held
        // recording must be finalised rather than waiting for a resume that will never arrive.
        assertEquals(
            Action.END,
            VoipSwitchPolicy.decide(normal, callActive = true, suspendedForCarrier = true)
        )
    }

    @Test
    fun `a phone call with no app call up is not our business`() {
        assertEquals(
            Action.NOTHING,
            VoipSwitchPolicy.decide(cell, callActive = false, suspendedForCarrier = false)
        )
    }

    @Test
    fun `an idle phone with nothing running does nothing`() {
        assertEquals(
            Action.NOTHING,
            VoipSwitchPolicy.decide(normal, callActive = false, suspendedForCarrier = false)
        )
    }

    @Test
    fun `the whole switch sequence, in order`() {
        // app call starts
        assertEquals(Action.START, VoipSwitchPolicy.decide(voip, false, false))
        // phone call answered — the detector suspends off the telephony broadcast, then this mode
        // event must NOT end the recording
        assertEquals(Action.HOLD, VoipSwitchPolicy.decide(cell, true, true))
        // phone call ends, app call audible again
        assertEquals(Action.RESUME, VoipSwitchPolicy.decide(voip, true, true))
        // app call finally ends
        assertEquals(Action.END, VoipSwitchPolicy.decide(normal, true, false))
    }
}
