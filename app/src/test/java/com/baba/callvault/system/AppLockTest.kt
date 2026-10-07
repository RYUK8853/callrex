/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app lock's one unforgivable failure: locking someone out of their own recordings.
 *
 * The lock is a door in front of the UI, so it cannot destroy anything — but a phone that cannot
 * authenticate and an app that insists on authentication would leave a person unable to reach their
 * own call history through the app that recorded it. Every case below is about refusing to shut a
 * door there is no key for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockTest {

    @Test
    fun `a phone with no biometric hardware and no credential cannot be locked`() {
        assertFalse(AppLock.canLockWith(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
    }

    @Test
    fun `an unsupported configuration cannot be locked`() {
        assertFalse(AppLock.canLockWith(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
    }

    @Test
    fun `hardware needing a security update we cannot install cannot be locked`() {
        assertFalse(AppLock.canLockWith(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
    }

    @Test
    fun `a phone that can authenticate right now can be locked`() {
        assertTrue(AppLock.canLockWith(BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `nothing enrolled yet still counts as lockable`() {
        // The device credential answers even with no fingerprint enrolled, and someone who enrols one
        // later must not find the setting has quietly turned itself off in the meantime.
        assertTrue(AppLock.canLockWith(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun `hardware that is merely busy still counts as lockable`() {
        // Transient. Treating it as "cannot lock" would disable the setting because a sensor happened
        // to be in use at the moment the screen was drawn.
        assertTrue(AppLock.canLockWith(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun `an unknown status counts as lockable`() {
        assertTrue(AppLock.canLockWith(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
    }

    @Test
    fun `the lock is off until it is asked for`() {
        // The default matters: a lock nobody asked for, appearing on an app they already rely on,
        // reads as a malfunction rather than as a feature.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertFalse(AppPreferences(context).isAppLockEnabled())
        assertFalse(AppLock.isEnabled(context))
    }

    @Test
    fun `asking for the lock is remembered`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = AppPreferences(context)

        preferences.setAppLockEnabled(true)

        assertTrue(preferences.isAppLockEnabled())
    }
}
