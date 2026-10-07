/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.Process

/**
 * What identity this process presents to the audio stack.
 *
 * **Why this is worth its own file.** `AudioRecord` is authorised against the pair (uid, package), and
 * the package is not the one you would guess — it comes from whatever `ActivityThread` the process
 * happens to have initialised, which differs between the two hosts of [RecorderServiceImpl]:
 *
 *  - our own `app_process` daemon never creates an `ActivityThread`, so it presents no package and the
 *    framework resolves uid 2000 to `com.android.shell`;
 *  - a host that initialised an `ActivityThread` for **our** package presents `com.baba.callvault`
 *    while running as uid 2000 — a pairing no app owns, which AudioFlinger refuses with
 *    `could not create record track, status: -1`.
 *
 * Reflection because everything here is hidden API, and best-effort because a diagnostic must never be
 * the thing that breaks a recording.
 */
object AudioAttribution {

    /** One line for the log: everything that decides whether this process may capture audio. */
    fun describe(): String =
        "uid=${Process.myUid()} pid=${Process.myPid()} opPackage=${opPackageName() ?: "<none>"} " +
            "groups=[${supplementaryGroups()}] context=${seLinuxContext()}"

    /**
     * The process's supplementary group ids.
     *
     * The audio stack gates capture on group membership as well as on permissions — **gid 1005 is
     * `audio`** — and a process does not necessarily inherit its parent's set. Two shell-uid processes
     * are not automatically equivalent.
     */
    fun supplementaryGroups(): String = runCatching {
        java.io.File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }
            ?.removePrefix("Groups:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            // Prefixed, not bare: AppLogger's redaction reads a run of digits as a phone number and
            // replaces it, which silently turned this diagnostic into "[PHONE_REDACTED]" the first
            // time it was used. A gid is not a phone number, and the log has to be able to say so.
            ?.joinToString(",") { "gid$it" }
            .orEmpty()
    }.getOrDefault("<unreadable>")

    /** The process's SELinux context, which decides what the audio HAL will talk to. */
    fun seLinuxContext(): String = runCatching {
        java.io.File("/proc/self/attr/current").readText().trim()
    }.getOrDefault("<unreadable>")

    /**
     * The package `AudioRecord` would attribute a capture to, or null when the process has none.
     *
     * Null is the *healthy* answer for a shell-uid recorder: it lets the framework fall back to the
     * package that actually owns uid 2000.
     */
    fun opPackageName(): String? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null) ?: return null
        activityThread.getMethod("getProcessName").invoke(current) as? String
    }.getOrNull()

    /** The package name the process was launched under, per `AppGlobals`; null when unset. */
    fun initialPackage(): String? = runCatching {
        Class.forName("android.app.AppGlobals")
            .getMethod("getInitialPackage")
            .invoke(null) as? String
    }.getOrNull()
}
