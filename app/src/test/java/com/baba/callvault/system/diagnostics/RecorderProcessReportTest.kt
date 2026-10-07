/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Counting privileged recorder processes.
 *
 * The healthy fixture is real `ps` output from a device with no symptom, captured 2026-09-01. It
 * contains the app's own process as well as the daemon, which is what makes it worth keeping: the
 * first version of this counted both and would have reported a problem on a healthy phone.
 */
class RecorderProcessReportTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `the app's own process is not a recorder`() {
        // Act
        val procs = RecorderProcessReport.parse(fixture("ps_recorder_healthy.txt"))

        // Assert — exactly the daemon, not the app beside it.
        assertEquals(1, procs.size)
        assertEquals("shell", procs.single().user)
        assertTrue(procs.single().args, "RecorderServer" in procs.single().args)
    }

    @Test
    fun `one recorder reads as correct`() {
        assertTrue(RecorderProcessReport.render(fixture("ps_recorder_healthy.txt")).contains("which is correct"))
    }

    @Test
    fun `more than one recorder is called out as the orphan it is`() {
        // Arrange — a daemon that outlived its replacement, which is what the reporting device showed.
        val ps = """
            u0_a546 8483 com.baba.callvault
            shell 3484 app_process / com.baba.callvault.server.RecorderServer /data/app/base.apk
            shell 3492 app_process / com.baba.callvault.server.RecorderServer /data/app/base.apk
        """.trimIndent()

        // Act
        val rendered = RecorderProcessReport.render(ps)

        // Assert
        assertEquals(2, RecorderProcessReport.parse(ps).size)
        assertTrue(rendered, "only ONE should be" in rendered)
    }

    @Test
    fun `a leftover scrcpy server counts too`() {
        val ps = "shell 999 app_process / com.genymobile.scrcpy.Server"
        assertEquals(1, RecorderProcessReport.parse(ps).size)
    }

    @Test
    fun `no daemon at all is reported rather than passed over`() {
        assertTrue(RecorderProcessReport.render("u0_a546 8483 com.baba.callvault").contains("not up"))
    }

    @Test
    fun `an unreadable ps says so instead of claiming everything is fine`() {
        assertTrue(RecorderProcessReport.render(null).contains("could not read"))
    }
}
