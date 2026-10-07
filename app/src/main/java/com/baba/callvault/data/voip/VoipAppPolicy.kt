/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.voip

/**
 * Whether an app call should be recorded, given the user's per-app choices.
 *
 * Some calls should not be recorded at all: a therapist on a messenger, a work call in a
 * two-party-consent country, a personal app on a shared phone. Until this, VoIP recording was one
 * global switch — on for everything or off for everything.
 *
 * **Stored as exclusions, presented as a whitelist.** The UI shows every app with a switch that is
 * on by default, which reads as "choose what gets recorded". Underneath, only the apps turned *off*
 * are stored. That asymmetry is deliberate and load-bearing: an allow-list stored literally would
 * start out empty, and an empty allow-list means "record nothing" — every existing user would
 * silently stop recording app calls the moment they upgraded, with a screen that looked fine.
 */
object VoipAppPolicy {

    /**
     * Whether a call owned by [packageName] should be recorded.
     *
     * **An unidentified app is recorded.** When the audio owner cannot be resolved to a package we
     * cannot honour an exclusion, and the two ways of being wrong are not equal: an unwanted
     * recording can be deleted in one tap, while a call that was never recorded is gone. Failing
     * open is the recoverable direction, and it is also what the app did before this existed.
     */
    fun shouldRecord(packageName: String?, excluded: Set<String>): Boolean {
        if (packageName.isNullOrBlank()) return true
        return packageName !in excluded
    }

    /** The exclusion set with [packageName] recorded or not, for a switch being flipped. */
    fun withRecording(excluded: Set<String>, packageName: String, record: Boolean): Set<String> =
        if (record) excluded - packageName else excluded + packageName
}
