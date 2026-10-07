/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/** Why Shizuku mode can or cannot serve a recorder, in the order a user would fix them. */
enum class ShizukuStatus {
    /** No Shizuku on the phone at all — nothing installed and nothing answering. */
    NOT_INSTALLED,

    /** Installed, but its server is not running — it must be started after every reboot. */
    NOT_RUNNING,

    /** Running, but CallVault has not been allowed to use it. */
    NO_PERMISSION,

    /** Running and permitted. */
    READY;

    companion object {

        /**
         * The one rule, kept away from the Android calls that answer it so it can be tested.
         *
         * **A running Shizuku outranks an absent package.** The reverse order was the bug: asking the
         * package manager first made every Shizuku that does not answer to `moe.shizuku.privileged.api`
         * report [NOT_INSTALLED], which greys the mode out entirely. Two real setups do that —
         * a fork's stealth mode, which reinstalls Shizuku under a random package name, and Sui, which
         * installs no manager app at all — and both left users unable to turn the mode on while their
         * Shizuku was running perfectly.
         *
         * [isInstalled] therefore only ever picks the wording for a Shizuku that is *not* answering.
         */
        fun of(isRunning: Boolean, hasPermission: Boolean, isInstalled: Boolean): ShizukuStatus = when {
            isRunning && hasPermission -> READY
            isRunning -> NO_PERMISSION
            isInstalled -> NOT_RUNNING
            else -> NOT_INSTALLED
        }
    }
}
