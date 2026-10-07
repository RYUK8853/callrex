/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The "your call was recorded" confirmation.
 *
 * It exists as its own function because the VoIP path could not reach it: the toast and vibration hang
 * off [RecordingServiceState] transitions, which only [RecordingForegroundService] drives, and
 * `VoipRecordingCoordinator` deliberately does not go through that service. An app call therefore
 * recorded successfully and told the user nothing — and a silent success is indistinguishable from a
 * silent failure, which is the complaint users make most bitterly about recorders.
 *
 * These tests pin the two things that matter: it says the same thing a carrier call says, and it stays
 * opt-out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingEndedFeedbackTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** [RecordingNotificationHelper.showToast] posts to the main looper; drain it before asserting. */
    private fun drainMainLooper() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun `it says the same thing a carrier call says`() {
        AppPreferences(context).setShowToastsEnabled(true)

        RecordingNotificationHelper(context).showRecordingEnded()
        drainMainLooper()

        assertEquals(
            context.getString(R.string.recording_toast_ended),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a user who turned toasts off is not shown one`() {
        // The confirmation must not become a new way to be noisy: it reuses the existing preference
        // rather than introducing a second one the user has to find.
        AppPreferences(context).setShowToastsEnabled(false)

        RecordingNotificationHelper(context).showRecordingEnded()
        drainMainLooper()

        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
