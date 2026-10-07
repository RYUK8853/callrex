/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a transcription posts the "your phone may get warm" notification (mirror176, issue #31).
 */
class TranscriptionNoticePolicyTest {

    private val minute = 60_000L

    @Test
    fun an_automatic_run_keeps_its_notification() {
        // Nobody asked for it just now, so the notification is the only sign it is happening.
        assertTrue(TranscriptionNoticePolicy.showsNotification(userRequested = false, estimatedMs = 2 * minute))
    }

    @Test
    fun a_short_run_the_user_just_confirmed_shows_none() {
        // The confirmation dialog already said the phone would be busy; saying it again is noise.
        assertFalse(TranscriptionNoticePolicy.showsNotification(userRequested = true, estimatedMs = 2 * minute))
    }

    /**
     * Android may stop a job that is not a foreground service once it has run ten minutes, and on
     * Android 11 always does. A stopped transcription restarts from nothing, so a long one keeps the
     * notification even when the user asked for it.
     */
    @Test
    fun a_run_long_enough_to_be_stopped_keeps_its_notification() {
        assertTrue(TranscriptionNoticePolicy.showsNotification(userRequested = true, estimatedMs = 9 * minute))
    }

    /** Without an estimate there is no way to know the run is short, so it is treated as long. */
    @Test
    fun an_unknown_length_keeps_its_notification() {
        assertTrue(TranscriptionNoticePolicy.showsNotification(userRequested = true, estimatedMs = 0L))
    }
}
