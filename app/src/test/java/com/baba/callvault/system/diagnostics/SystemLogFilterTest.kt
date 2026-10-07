/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These reports get attached to public GitHub issues, so a line that slips through the filter is
 * somebody's private data in a public thread. Every case below is either "must be kept because it
 * once answered a real question" or "must be dropped because it is not ours to share".
 *
 * Sample lines are real, taken from a OnePlus 12 on 2026-07-31.
 */
class SystemLogFilterTest {

    private val ours =
        "07-31 15:50:33.595 31037 31037 I CV:RecorderConn: RecorderBinderProvider onCreate (pid=31037 uid=10546)"
    private val daemon =
        "07-31 15:50:36.395  2518  2518 I app_process: Using generational CollectorTypeCMC GC."
    private val audio =
        "07-30 17:46:12.001  1123  1200 I AudioFlinger: rec update uid:2000 src:MIC silenced pack:com.android.shell"
    private val someoneElse =
        "07-31 15:50:33.100  4444  4444 I WhatsApp: message from +972 50-123-4567 delivered"

    // ---- What gets kept ----

    @Test
    fun `keeps our own lines, app and daemon alike`() {
        assertTrue(SystemLogFilter.keep(ours))
        assertTrue(SystemLogFilter.keep(daemon))
    }

    @Test
    fun `keeps the audio stack, where recorded-but-silent is decided`() {
        // This exact line is what explained the Samsung VoIP bug: the platform saying our capture was
        // silenced. Nothing of ours could have reported it.
        assertTrue(SystemLogFilter.keep(audio))
    }

    @Test
    fun `keeps process-lifecycle lines that are about us`() {
        for (tag in listOf("ActivityManager", "lowmemorykiller", "libc", "DEBUG", "avc", "SELinux")) {
            val line = "07-31 15:50:33.100  1  1 W $tag: Force stopping com.baba.callvault appid=10546"
            assertTrue("expected $tag to be kept when it names us", SystemLogFilter.keep(line))
        }
    }

    @Test
    fun `drops process-lifecycle lines about somebody else`() {
        // FOUND BY READING A REAL REPORT, not by reasoning about it. ActivityManager is whitelisted
        // because it says how our process died — but it narrates every process on the device, so the
        // file filled with an inventory of the user's installed apps, bound for a public issue.
        val others = listOf(
            "07-31 15:50:33.100  1  1 I ActivityManager: Process com.paypal.android.p2pmobile (pid 9424) has died",
            "07-31 15:50:33.100  1  1 W ActivityManager: Unable to start service Intent { pkg=com.alibaba.aliexpresshd } U=0",
            "07-31 15:50:33.100  1  1 I lowmemorykiller: Killing 'com.whatsapp' (1234)",
        )
        for (line in others) assertFalse("leaked: $line", SystemLogFilter.keep(line))
    }

    @Test
    fun `keeps audio lines even when they never mention CallVault`() {
        // THE LINE THAT MUST NOT BE FILTERED. This is what explained the Samsung VoIP bug, and it
        // names com.android.shell and a uid — no CallVault identifier at all. An "about us" rule
        // applied to the audio tags would have thrown away the most valuable line ever collected.
        assertTrue(SystemLogFilter.keep(audio))
        assertTrue(
            SystemLogFilter.keep(
                "07-30 17:46:12.001 1123 1200 I AudioPolicyManager: startInput() failed, device unavailable"
            )
        )
    }

    @Test
    fun `keeps adbd lines, which carry transport churn rather than other apps`() {
        assertTrue(SystemLogFilter.keep("07-31 15:50:33.100  1  1 I adbd    : adbd_auth: got auth key"))
    }

    // ---- What gets dropped ----

    @Test
    fun `drops another application's lines`() {
        // THE CASE THAT MATTERS: this line carries a phone number and belongs to someone else's app.
        assertFalse(SystemLogFilter.keep(someoneElse))
    }

    @Test
    fun `drops an unknown tag rather than passing it through`() {
        // Unfamiliar means excluded. The cost of guessing wrong is private data in a public issue.
        assertFalse(SystemLogFilter.keep("07-31 15:50:33.100  1  1 I SomeVendorService: boot stage 3"))
    }

    @Test
    fun `drops lines that are not threadtime at all`() {
        assertFalse(SystemLogFilter.keep("--------- beginning of main"))
        assertFalse(SystemLogFilter.keep(""))
        assertFalse(SystemLogFilter.keep("not a log line"))
        assertFalse(SystemLogFilter.keep("07-31 15:50:33.100 no pid or level here"))
    }

    @Test
    fun `a tag that merely contains an allowed name is not allowed`() {
        // "NotAudioFlingerAtAll" must not inherit AudioFlinger's place on the whitelist.
        assertFalse(SystemLogFilter.keep("07-31 15:50:33.100  1  1 I NotAudioFlingerAtAll: hello"))
    }

    // ---- Tag extraction ----

    @Test
    fun `reads the tag out of a threadtime line`() {
        assertEquals("CV:RecorderConn", SystemLogFilter.tagOf(ours))
        assertEquals("AudioFlinger", SystemLogFilter.tagOf(audio))
    }

    @Test
    fun `returns no tag for a line that has none`() {
        assertNull(SystemLogFilter.tagOf("--------- beginning of main"))
    }

    // ---- Capping ----

    @Test
    fun `keeps the newest lines when there are too many`() {
        val lines = (1..100).map { "line $it" }
        val capped = SystemLogFilter.capToNewest(lines, maxLines = 10, maxBytes = 1_000_000)
        assertEquals(10, capped.size)
        assertEquals("line 100", capped.last())
        assertEquals("line 91", capped.first())
    }

    @Test
    fun `trims from the oldest end to fit the byte ceiling`() {
        // A report is read to find out what happened just before the failure, so the end of the log
        // is the part worth its bytes.
        val lines = (1..10).map { "0123456789" }   // 11 bytes each with the newline
        val capped = SystemLogFilter.capToNewest(lines, maxLines = 10, maxBytes = 33)
        assertEquals(3, capped.size)
    }

    @Test
    fun `leaves a slice that already fits alone`() {
        val lines = listOf("a", "b", "c")
        assertEquals(lines, SystemLogFilter.capToNewest(lines, maxLines = 10, maxBytes = 1000))
    }

    @Test
    fun `an empty slice caps to empty rather than failing`() {
        assertEquals(emptyList<String>(), SystemLogFilter.capToNewest(emptyList(), 10, 100))
    }

    @Test
    fun `keeps the warning that names a released-while-active recording client`() {
        // AudioPolicyService::releaseInput's ALOGW. It is the only field-visible fingerprint of an
        // input released without being stopped, which is the path that leaves the microphone app-op
        // started and the green dot lit. Its presence (or absence) tells the two stuck-mic mechanisms
        // apart, so it must survive the filter even though it never mentions our package.
        val line = "09-03 12:07:33.500  2701  2848 W AudioPolicyInterfaceImpl: releaseInput " +
            "releasing active client portId 42"

        assertTrue(SystemLogFilter.keep(line))
    }
}
