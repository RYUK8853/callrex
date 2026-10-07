/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager

/**
 * What to do with an app-call recording when the audio mode changes.
 *
 * Extracted from [VoipCallDetector] because it is the logic that decides whether one conversation
 * becomes one recording or two, it has four states and three modes, and none of it can be exercised
 * through a real [AudioManager]. Same shape as [VoipTelephonyGate] beside it.
 *
 * The case that drove it: an app call is running, a phone call is answered, the user takes it and
 * then goes back. Before this, the app call was ended and a second one started on return — two files
 * for one conversation.
 */
object VoipSwitchPolicy {

    enum class Action {
        /** No app call is up and one has appeared. */
        START,

        /** A held app call is audible again — continue into the same file. */
        RESUME,

        /** A phone call is still up. Wait; do not end anything. */
        HOLD,

        /** The app call is over. Finalise the recording. */
        END,

        /** Nothing to do. */
        NOTHING,
    }

    /**
     * @param mode                the current [AudioManager] mode.
     * @param callActive          whether an app call is believed to be up (a HELD one still counts).
     * @param suspendedForCarrier whether that app call is currently held open across a phone call.
     */
    fun decide(mode: Int, callActive: Boolean, suspendedForCarrier: Boolean): Action {
        val inVoipCall = mode == AudioManager.MODE_IN_COMMUNICATION

        // Before START, always. The same event means "resume" when a call was held and "a new call"
        // when it was not, and testing START first is exactly how one conversation became two files.
        if (inVoipCall && callActive && suspendedForCarrier) return Action.RESUME

        // A phone call is up. The mode reads IN_CALL whether the app call is still holding or the
        // user hung it up while on the phone, and those cannot be told apart from here — only from
        // what the mode becomes next. Waiting is the only answer that is not a guess.
        if (suspendedForCarrier && mode == AudioManager.MODE_IN_CALL) return Action.HOLD

        if (inVoipCall && !callActive) return Action.START
        if (!inVoipCall && callActive) return Action.END
        return Action.NOTHING
    }
}
