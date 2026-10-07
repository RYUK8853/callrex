/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the ring size wrong has one specific cost: restoring a size we never really knew, shrinking
 * a buffer the user or the OEM had deliberately enlarged. Every unreadable case must therefore yield
 * null, not a plausible-looking default.
 */
class LogcatRingTest {

    /** Verbatim from a OnePlus 12 (ColorOS 16), 2026-07-31. */
    private val real = """
        main: ring buffer is 256 KiB (123 KiB consumed, 206 KiB readable), max entry is 5120 B, max payload is 4068 B
        system: ring buffer is 256 KiB (79 KiB consumed, 100 KiB readable), max entry is 5120 B, max payload is 4068 B
        crash: ring buffer is 256 KiB (64 KiB consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B
    """.trimIndent()

    @Test
    fun `reads the main buffer size from a real response`() {
        assertEquals(256, LogcatRing.parseMainSizeKib(real))
    }

    @Test
    fun `reads main and not the buffers listed after it`() {
        // system and crash report the same number here, so make the distinction visible.
        val differing = """
            main: ring buffer is 1024 KiB (10 KiB consumed, 10 KiB readable)
            system: ring buffer is 256 KiB (10 KiB consumed, 10 KiB readable)
        """.trimIndent()
        assertEquals(1024, LogcatRing.parseMainSizeKib(differing))
    }

    @Test
    fun `does not mistake a later number on the line for the size`() {
        // The line carries several figures; matching loosely would pick up "consumed" or "max entry".
        assertEquals(256, LogcatRing.parseMainSizeKib("main: ring buffer is 256 KiB (123 KiB consumed), max entry is 5120 B"))
    }

    @Test
    fun `understands a size already reported in MiB`() {
        assertEquals(8192, LogcatRing.parseMainSizeKib("main: ring buffer is 8 MiB (1 KiB consumed)"))
    }

    @Test
    fun `an OEM-mangled response reads as unknown`() {
        assertNull(LogcatRing.parseMainSizeKib("main buffer size: 256K"))
        assertNull(LogcatRing.parseMainSizeKib("main: ring buffer is enormous"))
    }

    @Test
    fun `no response at all reads as unknown`() {
        assertNull(LogcatRing.parseMainSizeKib(null))
        assertNull(LogcatRing.parseMainSizeKib(""))
        assertNull(LogcatRing.parseMainSizeKib("   "))
    }

    @Test
    fun `a response without a main line reads as unknown`() {
        assertNull(LogcatRing.parseMainSizeKib("system: ring buffer is 256 KiB (1 KiB consumed)"))
    }

    @Test
    fun `a nonsensical size reads as unknown rather than zero`() {
        assertNull(LogcatRing.parseMainSizeKib("main: ring buffer is 0 KiB"))
        assertNull(LogcatRing.parseMainSizeKib("main: ring buffer is 12 B"))
    }

    @Test
    fun `the commands are the ones logcat actually accepts`() {
        assertEquals("logcat -b main -G 8M", LogcatRing.growCommand())
        assertEquals("logcat -b main -G 256K", LogcatRing.restoreCommand(256))
    }
}
