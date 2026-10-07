/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.health.Prerequisite
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When an unrecorded app call is worth mentioning.
 *
 * The failure this guards against is the one that shipped until now: a VoIP call recorded by nobody
 * *and* mentioned by nobody, discovered weeks later if at all. The opposite failure — warning
 * someone mid-onboarding about a call that was never going to record — is cheaper but not free, and
 * a warning people learn to ignore is worth nothing when a real one arrives.
 */
class VoipMissPolicyTest {

    @Test
    fun `says nothing until this setup has ever recorded a call`() {
        // Mid-onboarding: nothing records yet, by definition. The status card already says so.
        assertEquals(
            MissReport.SILENT,
            VoipMissPolicy.report(everRecordedSuccessfully = false, missingPrerequisite = null)
        )
    }

    @Test
    fun `stays silent during setup even when a prerequisite explains the miss`() {
        // The missing folder IS the thing they are being walked through. Saying it twice, once in an
        // alarming voice, does not help.
        assertEquals(
            MissReport.SILENT,
            VoipMissPolicy.report(
                everRecordedSuccessfully = false,
                missingPrerequisite = Prerequisite.RECORDING_FOLDER
            )
        )
    }

    @Test
    fun `blames the setting when the user's own setup explains it`() {
        assertEquals(
            MissReport.EXCUSED,
            VoipMissPolicy.report(
                everRecordedSuccessfully = true,
                missingPrerequisite = Prerequisite.RECORDING_FOLDER
            )
        )
    }

    @Test
    fun `reports a working setup that missed a call as unexplained`() {
        // Everything the user owns is in order and the call still went unrecorded. This is the case
        // the whole warning exists for, and the one that must never be quietly folded in with the
        // excused kind.
        assertEquals(
            MissReport.UNEXPLAINED,
            VoipMissPolicy.report(everRecordedSuccessfully = true, missingPrerequisite = null)
        )
    }

    @Test
    fun `every prerequisite is treated as an explanation, not just the folder`() {
        // A new Prerequisite must not silently fall through to "unexplained" and start blaming
        // CallVault for something the user can fix themselves.
        for (prerequisite in Prerequisite.entries) {
            assertEquals(
                prerequisite.name,
                MissReport.EXCUSED,
                VoipMissPolicy.report(everRecordedSuccessfully = true, missingPrerequisite = prerequisite)
            )
        }
    }
}
