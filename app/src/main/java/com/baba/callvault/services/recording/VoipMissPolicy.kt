/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.health.Prerequisite

/** What to say about an app call that went unrecorded. */
enum class MissReport {
    /** Nothing. Not every miss is worth a warning. */
    SILENT,

    /** The user's own setup explains it — an excused miss, blamed on the setting at fault. */
    EXCUSED,

    /** Nothing explains it. This is the one worth chasing. */
    UNEXPLAINED
}

/**
 * Whether a VoIP call nobody recorded is worth telling the user about.
 *
 * Separated from [VoipRecordingCoordinator] so the rule can be tested without a phone, a daemon, or
 * a call — the same shape as [VoipTelephonyGate] and [RecordingPolicy]. The decision is small and
 * the cost of getting it wrong is asymmetric in both directions, which is exactly when a rule
 * deserves to be written down on its own.
 */
object VoipMissPolicy {

    /**
     * @param everRecordedSuccessfully whether ANY call has ever been recorded on this setup.
     * @param missingPrerequisite the user-owned setting at fault, where there is one.
     */
    fun report(everRecordedSuccessfully: Boolean, missingPrerequisite: Prerequisite?): MissReport = when {
        // A setup still being put together records nothing *by definition*, so a warning here reports
        // the very thing the user is in the middle of fixing. The status card already says setup is
        // incomplete; a notification saying the same thing in an alarming voice only teaches people
        // that CallVault's warnings can be ignored — which is expensive later, when one is real.
        !everRecordedSuccessfully -> MissReport.SILENT

        // Blamed on the setting rather than on the app. Kept apart from UNEXPLAINED deliberately: one
        // says "fix your folder", the other says "CallVault failed and we do not know why", and a
        // user who cannot tell those apart will act on neither.
        missingPrerequisite != null -> MissReport.EXCUSED

        else -> MissReport.UNEXPLAINED
    }
}
