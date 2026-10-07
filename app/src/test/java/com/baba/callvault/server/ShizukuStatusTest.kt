/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the three Shizuku facts are consulted in.
 *
 * Extracted from [RecorderBackend] because the order is the whole bug: asking "is the package
 * installed?" first made every Shizuku that does not answer to `moe.shizuku.privileged.api` invisible.
 * Two real setups do exactly that — a fork's stealth mode, which reinstalls Shizuku under a random
 * package name, and Sui, which installs no manager app at all — and both left the user unable to even
 * turn Shizuku mode on, while their Shizuku was running fine.
 */
class ShizukuStatusTest {

    @Test
    fun a_running_permitted_shizuku_is_ready_even_when_no_package_can_be_found() {
        // Stealth mode and Sui both land here. The binder is the ground truth; the package name is not.
        assertEquals(
            ShizukuStatus.READY,
            ShizukuStatus.of(isRunning = true, hasPermission = true, isInstalled = false),
        )
    }

    @Test
    fun a_running_unpermitted_shizuku_asks_for_permission_even_when_no_package_can_be_found() {
        assertEquals(
            ShizukuStatus.NO_PERMISSION,
            ShizukuStatus.of(isRunning = true, hasPermission = false, isInstalled = false),
        )
    }

    @Test
    fun an_installed_but_stopped_shizuku_reports_stopped() {
        assertEquals(
            ShizukuStatus.NOT_RUNNING,
            ShizukuStatus.of(isRunning = false, hasPermission = false, isInstalled = true),
        )
    }

    @Test
    fun nothing_running_and_nothing_installed_is_the_only_not_installed_case() {
        assertEquals(
            ShizukuStatus.NOT_INSTALLED,
            ShizukuStatus.of(isRunning = false, hasPermission = false, isInstalled = false),
        )
    }

    @Test
    fun the_ordinary_stock_setup_is_unchanged() {
        // Nothing hidden: installed, running, permitted. Same answers as before the reorder.
        assertEquals(
            ShizukuStatus.READY,
            ShizukuStatus.of(isRunning = true, hasPermission = true, isInstalled = true),
        )
        assertEquals(
            ShizukuStatus.NO_PERMISSION,
            ShizukuStatus.of(isRunning = true, hasPermission = false, isInstalled = true),
        )
    }

    @Test
    fun a_stale_permission_answer_never_outranks_a_dead_binder() {
        // hasPermission() reads a cached grant and can say yes after the server is gone; a status of
        // READY there would send the recorder off to bind to nothing at all.
        assertEquals(
            ShizukuStatus.NOT_RUNNING,
            ShizukuStatus.of(isRunning = false, hasPermission = true, isInstalled = true),
        )
    }
}
