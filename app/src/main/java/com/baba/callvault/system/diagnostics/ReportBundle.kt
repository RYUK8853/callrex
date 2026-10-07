/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * The one file "Save" writes: the app's debug report followed by the system report.
 *
 * Saving exists because Share was the only way out, and the reports live in the app's private cache — no file manager
 * can see them and `adb pull` cannot reach them on a release build. A user who will not send logs through email or a
 * messenger had no way to get them at all (mirror176, issues #28 and #29). Android's save dialog creates exactly one
 * file, so both reports go into it, each introduced by the name it has when shared, rather than asking twice in a row.
 */
object ReportBundle {

    const val DEBUG_REPORT_NAME = "callvault_debug_report.txt"
    const val SYSTEM_REPORT_NAME = "callvault_system_report.txt"

    /** [systemReport] is null when it could not be collected; the debug report is saved on its own then. */
    fun combine(debugReport: String, systemReport: String?): String = buildString {
        append(header(DEBUG_REPORT_NAME))
        append(debugReport.trimEnd())
        append('\n')
        if (systemReport != null) {
            append('\n')
            append(header(SYSTEM_REPORT_NAME))
            append(systemReport.trimEnd())
            append('\n')
        }
    }

    /** What the save dialog proposes, sortable by name: `callvault_report_2026-09-11_14-30.txt`. */
    fun suggestedFileName(year: Int, month: Int, day: Int, hour: Int, minute: Int): String =
        "callvault_report_%04d-%02d-%02d_%02d-%02d.txt".format(year, month, day, hour, minute)

    private fun header(name: String) = "===== $name =====\n"
}
