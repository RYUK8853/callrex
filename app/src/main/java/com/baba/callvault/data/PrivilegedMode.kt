/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

/**
 * Where CallVault's shell-uid privileges come from.
 *
 *  - [STANDALONE] — CallVault's own embedded ADB. One app, nothing else to install, and the reason the
 *    project exists. The cost is the pairing wizard.
 *  - [SHIZUKU] — a Shizuku server already running on the phone starts our recorder for us as a user
 *    service. No pairing at all, at the price of a second app the user must keep running.
 *
 * **[STANDALONE] is the default and stays the default.** An install that has never heard of this
 * setting must behave exactly as it does today, which is why [fromKey] answers [STANDALONE] to a null.
 *
 * The two modes differ only in *who starts the recorder*: both end at a shell-uid process serving the
 * same [com.baba.callvault.server.RecorderServiceImpl] over the same binder. See
 * `docs/dev-notes/2026-08-24-shizuku-support-plan.md`.
 */
enum class PrivilegedMode(val key: String) {
    STANDALONE("standalone"),
    SHIZUKU("shizuku");

    /** Whether this mode needs a second app installed and running. */
    val needsShizuku: Boolean get() = this == SHIZUKU

    /**
     * Whether this mode needs CallVault's own ADB setup — pairing, Wireless debugging, the
     * WRITE_SECURE_SETTINGS grant.
     *
     * What onboarding branches on, and what tells the wireless-debugging plumbing to stay quiet: in
     * Shizuku mode none of it applies, and machinery that fires anyway would report failures for setup
     * the user was never asked to do.
     */
    val needsAdbSetup: Boolean get() = this == STANDALONE

    companion object {
        fun fromKey(k: String?): PrivilegedMode = entries.firstOrNull { it.key == k } ?: STANDALONE
    }
}
