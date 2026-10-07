/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers how the daemon locates the extracted native library.
 *
 * This is worth pinning because the failure is invisible from outside: a library the daemon cannot
 * load makes `startHandoff` return false, the engine quietly falls back to the daemon recording path,
 * and the call still records — so "Resilient recording" is off while everything looks healthy. That
 * is exactly what happened when the lookup assumed the build ABI name (`arm64-v8a`) instead of the
 * instruction-set directory Android actually extracts to (`arm64`).
 */
class AudioHandoffNativeTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Builds an install layout: `<root>/<pkg>/base.apk` alongside `<root>/<pkg>/lib/<abiDir>/…`. */
    private fun installLayout(abiDir: String?, soName: String = "libaudiohandoff.so"): File {
        val pkgDir = temp.newFolder("pkg")
        val apk = File(pkgDir, "base.apk").apply { writeText("apk") }
        if (abiDir != null) {
            File(pkgDir, "lib/$abiDir").mkdirs()
            File(pkgDir, "lib/$abiDir/$soName").writeText("so")
        }
        return apk
    }

    @Test
    fun `finds the library in the instruction-set directory Android actually uses`() {
        // Arrange: the real on-device layout — lib/arm64, NOT lib/arm64-v8a.
        val apk = installLayout("arm64")

        // Act
        val found = AudioHandoffNative.findExtractedLib(apk.absolutePath)

        // Assert
        assertEquals(File(apk.parentFile, "lib/arm64/libaudiohandoff.so"), found)
    }

    @Test
    fun `finds the library whatever the abi directory is called`() {
        // Not assuming a name is the whole point — any of these must work.
        listOf("arm64-v8a", "arm", "armeabi-v7a", "x86_64").forEach { abiDir ->
            temp.delete()
            temp.create()
            val apk = installLayout(abiDir)
            assertEquals(
                "should have found the library under lib/$abiDir",
                File(apk.parentFile, "lib/$abiDir/libaudiohandoff.so"),
                AudioHandoffNative.findExtractedLib(apk.absolutePath),
            )
        }
    }

    @Test
    fun `returns null when the library was not extracted`() {
        // Arrange: a lib dir exists but holds a different library.
        val apk = installLayout("arm64", soName = "libsomethingelse.so")

        // Act + Assert
        assertNull(AudioHandoffNative.findExtractedLib(apk.absolutePath))
    }

    @Test
    fun `returns null when there is no lib directory at all`() {
        assertNull(AudioHandoffNative.findExtractedLib(installLayout(abiDir = null).absolutePath))
    }

    @Test
    fun `returns null for a nonexistent apk path rather than throwing`() {
        assertNull(AudioHandoffNative.findExtractedLib("/does/not/exist/base.apk"))
    }

    @Test
    fun `ignores a directory entry that is not a regular file`() {
        // Arrange: a DIRECTORY named like the library must not be mistaken for it.
        val pkgDir = temp.newFolder("pkg")
        val apk = File(pkgDir, "base.apk").apply { writeText("apk") }
        File(pkgDir, "lib/arm64/libaudiohandoff.so").mkdirs()

        // Act + Assert
        assertNull(AudioHandoffNative.findExtractedLib(apk.absolutePath))
    }
}
