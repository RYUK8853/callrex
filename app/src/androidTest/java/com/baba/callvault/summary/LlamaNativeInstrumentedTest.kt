/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the native half is really there before anything is asked of it.
 *
 * Worth its own test because the failure it catches is silent in a useful way: if libllamacv.so or
 * the ggml backend .so did not make it into the APK, every later measurement would fail for a
 * reason that looks like a model problem rather than a packaging one.
 */
@RunWith(AndroidJUnit4::class)
class LlamaNativeInstrumentedTest {

    private val libDir: String
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationInfo.nativeLibraryDir

    @Test
    fun reportsSystemInfo() {
        val info = LlamaNative.systemInfo(libDir)
        // Non-empty is the real assertion: it means the library loaded, a CPU backend .so was found
        // in the app's library directory, and JNI name resolution matched. Blank is exactly what a
        // wrong directory looks like — the string is built from the backend registry, so an empty
        // registry produces an empty string rather than an error.
        assertTrue("system info was empty — the backend probably did not load", info.isNotEmpty())
    }

    @Test
    fun refusesAModelThatIsNotThere() {
        // 0 rather than a crash. A missing or corrupt model file is an ordinary thing on a phone
        // where downloads get interrupted, and it must surface as a value the caller can handle.
        assertTrue(LlamaNative.initContext("/does/not/exist.gguf", libDir) == 0L)
    }

    @Test
    fun freeingNothingIsSafe() {
        LlamaNative.freeContext(0L)
    }
}
