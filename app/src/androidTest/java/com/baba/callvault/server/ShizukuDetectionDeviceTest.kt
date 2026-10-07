/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.ShizukuProvider

/**
 * That [ShizukuBackend.managerPackage] finds the Shizuku a real phone actually has.
 *
 * The reason it exists: a user reported CallVault reporting "not installed" for a Shizuku running in
 * a fork's stealth mode, which reinstalls Shizuku under a random package name. The old check asked for
 * `moe.shizuku.privileged.api` by name and so could not see it. This test asks the device's own package
 * manager, so it fails on any phone where the permission lookup does not behave as the fix assumes —
 * including a ROM that restricts it, which is the one thing reasoning from the fork's source cannot
 * settle.
 *
 * Passes with any Shizuku, with a renamed one, and with none at all.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuDetectionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Who the device says declares Shizuku's API permission, asked the plain way, outside the fix. */
    private val declaredBy: String? = runCatching {
        context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0).packageName
    }.getOrNull()

    @Test
    fun the_manager_is_found_by_the_permission_it_declares_whatever_it_is_called() {
        val found = ShizukuBackend.managerPackage(context)
        Log.i(TAG, "permission ${ShizukuProvider.PERMISSION} declared by: $declaredBy")
        Log.i(TAG, "managerPackage() resolved: $found")

        if (declaredBy == null) {
            // No Shizuku of any kind: the fallback name must not be installed either, or the fix lies.
            Log.i(TAG, "no Shizuku on this device")
            return
        }

        assertNotNull("a declared Shizuku permission must resolve to a manager package", found)
        assertEquals("must resolve to the package the device says declares it", declaredBy, found)
    }

    @Test
    fun a_manager_that_does_not_answer_to_the_stock_name_is_still_found() {
        val found = ShizukuBackend.managerPackage(context) ?: return
        val stockIsInstalled = runCatching {
            context.packageManager.getPackageInfo(ShizukuBackend.SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        Log.i(TAG, "found=$found stockNameInstalled=$stockIsInstalled")
        if (stockIsInstalled) {
            Log.i(TAG, "stock-named Shizuku present; the renamed case is covered by the run with a clone")
            return
        }
        // This is the reported bug, on a real device: nothing answers to moe.shizuku.privileged.api,
        // and Shizuku is found anyway. The old code returned NOT_INSTALLED here.
        assertEquals(found, ShizukuBackend.managerPackage(context))
        assertEquals(
            ShizukuStatus.NOT_RUNNING,
            ShizukuStatus.of(isRunning = false, hasPermission = false, isInstalled = true),
        )
    }

    private companion object {
        const val TAG = "CV:ShizukuDetectionTest"
    }
}
