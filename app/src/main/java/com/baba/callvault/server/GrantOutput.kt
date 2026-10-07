/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Reads back what a grant actually did, because the commands that perform them lie.
 *
 * `appops set --user 0 --uid <uid> RECORD_AUDIO allow` exits **0 and changes nothing** on Android 14+:
 * the system logs `Ignored setUidMode call for runtime permission app op` and moves on. Measured on an
 * emulator on 2026-08-24. Reporting that as a granted permission would send a user to look for a
 * problem somewhere else entirely — so [PrivilegedGrants] asks the system what is true afterwards, and
 * this object is the reading half.
 */
object GrantOutput {

    /**
     * Whether `appops get <pkg> <op>` says the **package** mode is `allow`.
     *
     * The "Uid mode:" line is deliberately excluded. It is the line a uid-level grant leaves behind,
     * usually still reading `ignore`, and it appears *first* — so anything that scans for the op name
     * and takes the first match reads the wrong answer, confidently.
     */
    fun appOpAllowed(output: String, opName: String): Boolean =
        output.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("Uid mode:") }
            .any { it == "$opName: allow" }

    /**
     * Whether `cmd role get-role-holders <role>` lists exactly [packageName].
     *
     * Matched entry by entry rather than by `contains`, because `com.baba.callvault.instrtest` is a real
     * package this project installs during instrumented runs, and it is not us.
     */
    fun holdsRole(output: String, packageName: String): Boolean =
        output.lineSequence()
            .flatMap { it.split(';').asSequence() }
            .map { it.trim() }
            .any { it == packageName }
}
