/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import androidx.annotation.StringRes
import com.baba.callvault.R
import com.baba.callvault.data.PrivilegedMode

/**
 * How a mode switch ended, in terms the dialog can show.
 *
 * **"Ready" means a live binder, nothing less.** Switching used to be silent — the toggle flipped, work
 * happened on a background thread, and nothing said whether a recorder was actually running afterwards.
 * On a phone that had both backends alive at once, that silence hid a real bug for hours.
 */
enum class ModeSwitchResult(@param:StringRes val messageRes: Int, val isReady: Boolean = false) {

    /** A recorder is running and its binder is in [RecorderConnection]. */
    Ready(R.string.mode_switch_ready, isReady = true),

    /** Shizuku mode was chosen on a phone with no Shizuku app. */
    ShizukuNotInstalled(R.string.mode_switch_shizuku_not_installed),

    /** Shizuku is installed but its server is not running — it stops on every reboot. */
    ShizukuNotRunning(R.string.mode_switch_shizuku_not_running),

    /** Shizuku is running but has not allowed CallVault to use it. */
    ShizukuNotPermitted(R.string.mode_switch_shizuku_not_permitted),

    /**
     * Everything the user controls is in place and the recorder still did not come up.
     *
     * Deliberately distinct from the three above: telling someone to start Shizuku when Shizuku is
     * already running sends them to fix something that is not broken.
     */
    RecorderDidNotStart(R.string.mode_switch_recorder_failed),

    /**
     * A recorder is running, but VoIP capture could not be armed and the user has VoIP recording on.
     *
     * **Not "ready", because a VoIP call arriving now is lost for good.** Capture depends on a dynamic
     * audio policy the daemon registers, and Android fixes a track's routing when the track is
     * *created* — so there is no arming it once a call is under way, and no retry. Arming is a blocking
     * IPC the app runs on its own thread, which means `ensureRunning` returns (and this dialog used to
     * say "Ready") while it is still in flight.
     */
    VoipNotArmed(R.string.mode_switch_voip_not_armed),

    ;

    companion object {
        /**
         * Judges the switch from what is true afterwards.
         *
         * @param connected whether a recorder binder is live now.
         * @param voipArmed whether VoIP capture is armed, or true when the user has VoIP recording off
         *   and there is nothing to arm. A live binder alone is **not** success: everything the switch
         *   tore down has to be standing again before the dialog may say so, and arming is the piece
         *   that runs on its own thread and therefore finishes last.
         * @param shizuku Shizuku's state, consulted **only** in Shizuku mode; in standalone it is a red
         *   herring and naming it would send the user somewhere irrelevant.
         */
        fun of(
            mode: PrivilegedMode,
            connected: Boolean,
            voipArmed: Boolean,
            shizuku: ShizukuStatus,
        ): ModeSwitchResult {
            // Order matters: a recorder that never started is the bigger problem, and reporting "VoIP is
            // not armed" to someone in that state sends them to fix the wrong thing.
            if (connected) return if (voipArmed) Ready else VoipNotArmed
            if (!mode.needsShizuku) return RecorderDidNotStart
            return when (shizuku) {
                ShizukuStatus.NOT_INSTALLED -> ShizukuNotInstalled
                ShizukuStatus.NOT_RUNNING -> ShizukuNotRunning
                ShizukuStatus.NO_PERMISSION -> ShizukuNotPermitted
                ShizukuStatus.READY -> RecorderDidNotStart
            }
        }
    }
}
