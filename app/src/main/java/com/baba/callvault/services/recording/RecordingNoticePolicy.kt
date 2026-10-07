/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * What the recording service's notification should say the instant a command reaches it.
 *
 * Android requires the notification to be posted straight away, before the command has been acted on.
 * The service used to post whatever state it was *already* in — and a service that has just been
 * started is in Standby — so every automatically recorded call flashed "Call in progress — Press to
 * start recording", with a Record button, for the moment before it switched to "Preparing to record…"
 * (mirror176, issue #31). The maintainer had seen it for a long time; recording was never affected.
 *
 * Pure, so the cases below are testable without a service.
 */
object RecordingNoticePolicy {

    enum class Opening {
        /** Announce the recording that is about to start. */
        PREPARING,

        /** Keep describing the state the service is already in. */
        AS_IS,
    }

    /**
     * @param isStartRequest the command is an automatic or manual start.
     * @param hasSession a recording is already under way, so the start will be ignored.
     * @param hasMetadata the call's details arrived with the command; without them the start fails.
     * @param isVoipCall the VoIP recorder already has this call, so the carrier start will be ignored.
     */
    fun opening(
        isStartRequest: Boolean,
        hasSession: Boolean,
        hasMetadata: Boolean,
        isVoipCall: Boolean,
    ): Opening = when {
        !isStartRequest -> Opening.AS_IS
        hasSession -> Opening.AS_IS
        !hasMetadata -> Opening.AS_IS
        isVoipCall -> Opening.AS_IS
        else -> Opening.PREPARING
    }
}
