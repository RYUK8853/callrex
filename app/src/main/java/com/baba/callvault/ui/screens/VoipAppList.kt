/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import com.baba.callvault.utils.AppLogger

/** An installed app that could plausibly place calls, for the per-app recording list. */
data class CallingApp(val packageName: String, val label: String)

/**
 * Finds the installed apps worth offering a per-app recording switch for.
 *
 * Two filters, both cheap and both honest:
 *
 * - **A launcher entry.** The manifest already scopes package visibility to launchable apps rather
 *   than asking for `QUERY_ALL_PACKAGES`, which is exactly the permission a call recorder should not
 *   be requesting. So this is the set we can see at all, not a choice made here.
 * - **It asks for `RECORD_AUDIO`.** An app that cannot record audio cannot place the kind of call we
 *   capture. This is what keeps the list to a page of messengers instead of every app on the phone.
 *
 * The filter is deliberately loose rather than a hardcoded list of known messengers: a fixed list is
 * wrong the day someone installs a messenger nobody thought of, and a user who cannot find their app
 * in the list has no way to act on it.
 */
object VoipAppList {

    private const val TAG = "CV:VoipAppList"

    fun installedCallingApps(context: Context): List<CallingApp> = runCatching {
        val pm = context.packageManager
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.requestedPermissions?.contains(android.Manifest.permission.RECORD_AUDIO) == true }
            .mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                CallingApp(pkg.packageName, pm.getApplicationLabel(info).toString())
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.onFailure { AppLogger.w(TAG, "Could not list calling apps: ${it.message}") }
        .getOrDefault(emptyList())
}
