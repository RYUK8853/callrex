/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Finds our own APK in the classpath of a process Shizuku started for us.
 *
 * Shizuku launches a user service as `app_process` with `CLASSPATH` set to the client APK, so
 * `java.class.path` names it. That is the only handle a no-Context [RecorderUserService] has on its own
 * APK, which the scrcpy fallback and the native handoff library both need.
 *
 * Its own file because it is the one piece of the Shizuku path that can be tested on a JVM.
 */
object ShizukuClasspath {

    /**
     * The `.apk` entry in [classpath] that belongs to [ownPackage], or `""` when there is none.
     *
     * **Matching the package is the point.** Measured on an emulator on 2026-08-24, a Shizuku-hosted
     * process reported *Shizuku's* APK on its classpath rather than the client's — so "the first apk"
     * was confidently wrong, and the failure surfaced far away as "APK is missing scrcpy asset entry"
     * for a package that never had one. Empty is the honest answer; the callers report it clearly.
     */
    fun apkFrom(classpath: String, ownPackage: String): String =
        classpath.split(':')
            .firstOrNull { it.isNotBlank() && it.endsWith(".apk") && it.contains(ownPackage) }
            .orEmpty()
}
