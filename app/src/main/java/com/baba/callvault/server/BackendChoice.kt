/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.data.PrivilegedMode

/**
 * Which machinery starts the recorder — and, when the user changes their mind, which must be stopped.
 *
 * A tiny enum with its own file because the *tearing down* half is easy to forget and expensive to get
 * wrong: leaving the old backend running means two shell-uid recorders competing for the same audio
 * input, and the loser is not predictable. On a phone that shows up as a call recorded by neither.
 */
enum class BackendChoice {
    /** CallVault's own detached `app_process` daemon over embedded ADB. */
    ADB,

    /** A user service hosted by whatever Shizuku server is on the phone. */
    SHIZUKU;

    companion object {

        fun of(mode: PrivilegedMode): BackendChoice = when (mode) {
            PrivilegedMode.STANDALONE -> ADB
            PrivilegedMode.SHIZUKU -> SHIZUKU
        }

        /**
         * What must be stopped when the mode changes from [from] to [to], or null when nothing must.
         *
         * Null for an unchanged mode is not a nicety: Settings can re-save the same value, and killing
         * a warm recorder for that would drop the next call for no reason at all.
         */
        fun toTearDown(from: PrivilegedMode, to: PrivilegedMode): BackendChoice? =
            if (from == to) null else of(from)
    }
}
