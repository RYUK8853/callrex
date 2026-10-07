/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupFingerprintTest {

    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `the same setup always hashes the same`() {
        prefs.setRecordingFolderUri("content://tree/a".toUri())
        assertEquals(SetupFingerprint.of(prefs), SetupFingerprint.of(prefs))
    }

    @Test
    fun `changing the recording folder changes the fingerprint`() {
        prefs.setRecordingFolderUri("content://tree/a".toUri())
        val before = SetupFingerprint.of(prefs)
        prefs.setRecordingFolderUri("content://tree/b".toUri())
        assertNotEquals(before, SetupFingerprint.of(prefs))
    }

    @Test
    fun `changing the storage target changes the fingerprint`() {
        prefs.setStorageTarget(StorageTarget.LOCAL)
        val before = SetupFingerprint.of(prefs)
        prefs.setStorageTarget(StorageTarget.BOTH)
        assertNotEquals(before, SetupFingerprint.of(prefs))
    }

    @Test
    fun `vibration is not setup and does not change the fingerprint`() {
        prefs.setRecordingFolderUri("content://tree/a".toUri())
        val before = SetupFingerprint.of(prefs)
        prefs.setVibrationEnabled(!prefs.isVibrationEnabled())
        assertEquals(before, SetupFingerprint.of(prefs))
    }
}
