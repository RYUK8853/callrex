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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single file "Save as" writes (mirror176, issues #28 and #29): the app's debug report and the system report,
 * because Android's save dialog creates exactly one file and asking twice in a row would be clumsy.
 */
class ReportBundleTest {

    @Test
    fun both_reports_go_into_one_file_debug_report_first() {
        val bundle = ReportBundle.combine(debugReport = "APP LINES", systemReport = "SYSTEM LINES")

        assertTrue(bundle.indexOf("APP LINES") < bundle.indexOf("SYSTEM LINES"))
    }

    /** Each half is labelled, so whoever reads the file — or splits it — knows where one report ends. */
    @Test
    fun each_report_is_introduced_by_its_own_file_name() {
        val bundle = ReportBundle.combine(debugReport = "APP LINES", systemReport = "SYSTEM LINES")

        assertTrue(bundle.contains(ReportBundle.DEBUG_REPORT_NAME))
        assertTrue(bundle.contains(ReportBundle.SYSTEM_REPORT_NAME))
        assertTrue(bundle.indexOf(ReportBundle.SYSTEM_REPORT_NAME) < bundle.indexOf("SYSTEM LINES"))
    }

    /** The system report is optional — collecting it can fail or time out — and the debug report still saves. */
    @Test
    fun a_missing_system_report_saves_the_debug_report_alone() {
        val bundle = ReportBundle.combine(debugReport = "APP LINES", systemReport = null)

        assertTrue(bundle.contains("APP LINES"))
        assertFalse(bundle.contains(ReportBundle.SYSTEM_REPORT_NAME))
    }

    @Test
    fun the_suggested_file_name_says_what_it_is_and_when() {
        // 2026-09-11 14:30:05 local time, expressed as fields rather than a clock the test would have to fake.
        assertEquals(
            "callvault_report_2026-09-11_14-30.txt",
            ReportBundle.suggestedFileName(year = 2026, month = 9, day = 11, hour = 14, minute = 30),
        )
    }
}
