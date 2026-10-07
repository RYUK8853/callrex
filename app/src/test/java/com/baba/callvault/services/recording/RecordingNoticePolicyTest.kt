/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.RecordingNoticePolicy.Opening
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the recording service's notification says the instant a command arrives (mirror176, issue #31).
 *
 * The service used to post whatever state it was already in before acting on the command, and a
 * fresh service is in Standby — so every automatically recorded call flashed "Call in progress —
 * Press to start recording" before switching to "Preparing to record…".
 */
class RecordingNoticePolicyTest {

    @Test
    fun a_start_that_will_go_ahead_opens_on_preparing() {
        assertEquals(
            Opening.PREPARING,
            RecordingNoticePolicy.opening(isStartRequest = true, hasSession = false, hasMetadata = true, isVoipCall = false),
        )
    }

    /** The VoIP recorder already has this call; the carrier start is about to be ignored. */
    @Test
    fun a_start_during_a_voip_call_does_not_claim_to_be_preparing() {
        assertEquals(
            Opening.AS_IS,
            RecordingNoticePolicy.opening(isStartRequest = true, hasSession = false, hasMetadata = true, isVoipCall = true),
        )
    }

    @Test
    fun a_start_while_already_recording_leaves_the_notification_as_it_is() {
        assertEquals(
            Opening.AS_IS,
            RecordingNoticePolicy.opening(isStartRequest = true, hasSession = true, hasMetadata = true, isVoipCall = false),
        )
    }

    @Test
    fun a_start_without_call_details_leaves_the_notification_as_it_is() {
        assertEquals(
            Opening.AS_IS,
            RecordingNoticePolicy.opening(isStartRequest = true, hasSession = false, hasMetadata = false, isVoipCall = false),
        )
    }

    @Test
    fun any_other_command_leaves_the_notification_as_it_is() {
        assertEquals(
            Opening.AS_IS,
            RecordingNoticePolicy.opening(isStartRequest = false, hasSession = false, hasMetadata = true, isVoipCall = false),
        )
    }
}
