/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import java.util.Locale

/**
 * Formats a byte count as a compact human-readable size (e.g. "1.2 MB").
 *
 * Lives here rather than in one screen because the recordings list and the debug-log controls both
 * show sizes, and two formatters would drift into disagreeing about the same number.
 *
 * Deliberately [Locale.US] for the decimal separator: these strings sit next to filenames and byte
 * counts, and a locale-specific separator in that company reads as a typo more often than as a
 * courtesy.
 */
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
