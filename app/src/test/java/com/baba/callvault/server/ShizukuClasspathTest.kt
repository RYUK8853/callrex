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

class ShizukuClasspathTest {

    private val ours = "com.baba.callvault"

    @Test
    fun our_own_apk_is_the_answer() {
        val apk = "/data/app/~~abc==/com.baba.callvault-xyz==/base.apk"

        assertEquals(apk, ShizukuClasspath.apkFrom(apk, ours))
    }

    @Test
    fun our_apk_is_picked_out_of_a_longer_classpath() {
        val classpath = "/system/framework/foo.jar:/data/app/~~a==/com.baba.callvault-b==/base.apk"

        assertEquals("/data/app/~~a==/com.baba.callvault-b==/base.apk", ShizukuClasspath.apkFrom(classpath, ours))
    }

    @Test
    fun shizukus_own_apk_is_not_mistaken_for_ours() {
        // The case that actually happened on an emulator: a Shizuku-hosted process reported Shizuku's
        // APK on its classpath. Returning it produced "APK is missing scrcpy asset entry" against a
        // package that never had one — a failure that reads as ours and is not.
        val classpath = "/data/app/~~z==/moe.shizuku.privileged.api-q==/base.apk"

        assertEquals("", ShizukuClasspath.apkFrom(classpath, ours))
    }

    @Test
    fun ours_is_found_even_when_another_apk_comes_first() {
        val classpath = "/data/app/~~z==/moe.shizuku.privileged.api-q==/base.apk:" +
            "/data/app/~~a==/com.baba.callvault-b==/base.apk"

        assertEquals("/data/app/~~a==/com.baba.callvault-b==/base.apk", ShizukuClasspath.apkFrom(classpath, ours))
    }

    @Test
    fun an_empty_classpath_gives_an_empty_answer_rather_than_a_wrong_one() {
        assertEquals("", ShizukuClasspath.apkFrom("", ours))
    }

    @Test
    fun a_classpath_with_no_apk_gives_an_empty_answer() {
        assertEquals("", ShizukuClasspath.apkFrom("/system/framework/foo.jar", ours))
    }

    @Test
    fun blank_entries_are_ignored() {
        assertEquals(
            "/a/com.baba.callvault/base.apk",
            ShizukuClasspath.apkFrom(":/a/com.baba.callvault/base.apk:", ours)
        )
    }
}
