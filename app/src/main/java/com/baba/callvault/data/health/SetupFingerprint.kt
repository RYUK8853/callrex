/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences

/**
 * A stable hash of the setup a user owns. When it changes, an earlier verification no longer speaks
 * for the current configuration.
 *
 * Wireless and USB debugging state are deliberately absent: CallVault toggles those itself as normal
 * behaviour, so including them would invalidate verification through the app's own actions.
 */
object SetupFingerprint {

    fun of(prefs: AppPreferences): String = listOf(
        prefs.getRecordingFolderUri()?.toString().orEmpty(),
        prefs.getDriveFolderUri()?.toString().orEmpty(),
        prefs.getStorageTarget().key,
        prefs.isAdbPaired().toString(),
        BuildConfig.VERSION_CODE.toString()
    ).joinToString("|").hashCode().toString(RADIX_HEX)

    private const val RADIX_HEX = 16
}
