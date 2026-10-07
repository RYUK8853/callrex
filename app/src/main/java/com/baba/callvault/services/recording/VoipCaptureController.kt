/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.utils.AppLogger

/**
 * Keeps the daemon's VoIP capture policy armed whenever the feature is on.
 *
 * The policy has to be registered BEFORE a VoIP call's audio track is created — Android fixes a
 * track's routing at creation, so arming once a call is under way leaves it permanently unattached and
 * the recording comes out silent rather than merely clipped. There is therefore no "arm on call start"
 * option: it is armed when the user enables the feature, re-armed whenever the daemon is (re)launched,
 * and left armed. That costs nothing while idle — measured on-device, an armed policy holds no wakelock
 * and no active record track, so the phone still sleeps normally.
 */
object VoipCaptureController {
    private const val TAG = "CV:VoipCtl"

    /**
     * Brings the policy into line with the preference. Blocking (binder + possibly a daemon launch), so
     * call it off the main thread.
     *
     * @return true when the feature is on AND the policy is armed — i.e. VoIP calls can be recorded.
     */
    fun sync(context: Context): Boolean {
        val wanted = AppPreferences(context).isVoipRecordingEnabled()
        if (!wanted) {
            runCatching { RecorderConnection.service?.disarmVoipCapture() }
                .onFailure { AppLogger.d(TAG, "disarm skipped: ${it.message}") }
            return false
        }
        // The daemon owns the policy, so it has to be up before we can arm anything.
        if (!RecorderBackend.ensureRunning(context)) {
            AppLogger.w(TAG, "VoIP arm deferred: daemon unavailable")
            return false
        }
        val armed = runCatching { RecorderConnection.service?.armVoipCapture() == true }
            .onFailure { AppLogger.w(TAG, "armVoipCapture failed: ${it.message}") }
            .getOrDefault(false)
        if (!armed) {
            AppLogger.w(TAG, "VoIP capture unavailable on this device (policy refused)")
        }
        return armed
    }

    /** Whether a VoIP recording could start right now — the feature is on and the daemon is reachable. */
    fun isReady(context: Context): Boolean =
        AppPreferences(context).isVoipRecordingEnabled() && RecorderConnection.isConnected
}
