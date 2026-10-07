/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Issue #29's evidence has to be readable by the APP, not only by the privileged daemon — that is the
 * whole reason the fix works when the daemon is missing, which is the reporter's case.
 *
 * A unit test cannot prove that: it is a property of the device and of our uid, not of our arithmetic.
 * So this asserts only the part that would silently invalidate the fix — that the platform answers us
 * at all — and logs what it saw, which is what a future OEM regression would show up in.
 */
class CallEvidenceDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun the_platform_tells_an_ordinary_app_who_is_capturing() {
        val sources = CallEvidence.activeCaptureSources(context)
        Log.i(TAG, "active capture sources: $sources; looksLikeACall=${CallEvidence.looksLikeACall(context)}")

        // Null means getActiveRecordingConfigurations threw or was withheld. Were that to happen, the
        // gate would fall back to "no evidence" and every genuine missed call would go unreported —
        // so it is the one outcome that must fail loudly rather than pass quietly.
        assertNotNull(
            "This device did not answer getActiveRecordingConfigurations(); issue #29's gate cannot work here",
            sources,
        )
    }

    private companion object {
        const val TAG = "CV:CallEvidenceDevice"
    }
}
