/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.Context
import androidx.annotation.Keep
import com.baba.callvault.utils.AppLogger

/**
 * The recorder, as Shizuku starts it.
 *
 * Shizuku spawns a shell-uid process with our APK on the classpath and instantiates **this class**,
 * handing the resulting binder back to the app. It adds nothing to [RecorderServiceImpl] but the two
 * constructors Shizuku is willing to call — the recorder itself is shared with our own `app_process`
 * daemon, deliberately, so the two hosts cannot drift.
 *
 * Both constructors must survive minification, hence [Keep]: nothing in the app references this class
 * by name, so to a shrinker it looks dead. (Release does not minify today, but a `@Keep` costs nothing
 * and the day it does minify, this failure would look like "Shizuku mode just doesn't work".)
 */
@Keep
class RecorderUserService : RecorderServiceImpl {

    /**
     * What Shizuku calls when it cannot supply a Context (its pre-v13 path, and any variant that
     * chooses not to). The APK is recovered from the classpath Shizuku itself set to launch us.
     */
    @Keep
    constructor() : super(apkPathFromClasspath()) {
        AppLogger.i(TAG, "Started by Shizuku (no Context) ${identity()}")
    }

    /**
     * Shizuku API v13+ hands over a Context built with `createPackageContextAsUser` for our package.
     *
     * **Strongly preferred over the no-arg constructor**, and not for tidiness: measured on an emulator
     * 2026-08-24, `java.class.path` in a Shizuku-hosted process names **Shizuku's own APK**, not ours,
     * so the classpath fallback silently pointed the scrcpy extractor at the wrong package and recording
     * failed outright. `applicationInfo.sourceDir` cannot be wrong that way.
     */
    @Keep
    constructor(context: Context) : super(context.applicationInfo.sourceDir) {
        AppLogger.i(TAG, "Started by Shizuku (with Context) ${identity()}")
    }

    private companion object {
        const val TAG = "CV:RecorderUserService"

        /**
         * pid/uid, logged because the whole premise of Shizuku mode is that it hands us the same
         * shell uid our own daemon runs as. When capture behaves differently between the two hosts,
         * this line is the first thing worth reading — `RecorderServer.main` logs the same for the
         * ADB path, so the two are directly comparable.
         */
        fun identity(): String = "pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}"

        /**
         * Our APK, read off the classpath — the last resort, used only when Shizuku gives no Context.
         *
         * Verified to be *ours* before it is returned. On the emulator this classpath turned out to
         * name Shizuku's APK instead, which produced a baffling "APK is missing scrcpy asset entry"
         * against a package that indeed has no such asset. An empty answer fails clearly; a confident
         * wrong one sends the next reader hunting through the wrong app.
         */
        fun apkPathFromClasspath(): String {
            val classpath = runCatching { System.getProperty("java.class.path") }.getOrNull().orEmpty()
            val apk = ShizukuClasspath.apkFrom(classpath, ownPackage = com.baba.callvault.BuildConfig.APPLICATION_ID)
            if (apk.isEmpty()) {
                AppLogger.e(TAG, "No CallVault APK on the classpath ('$classpath') — scrcpy and the " +
                    "native handoff library will be unavailable in this process")
            }
            return apk
        }
    }
}
