/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Reading and sizing logcat's main ring buffer.
 *
 * **Why this exists.** Everything the recorder daemon logs goes to logcat and nowhere else — it runs
 * as a separate shell-uid process and cannot write the app's log file. Logcat's default ring is tiny:
 * measured on a OnePlus 12 on 2026-07-31, `main` is 256 KiB and 123 KiB of it filled in 26 seconds of
 * ordinary use. A user who reproduces a bug and then walks to Settings to share it can easily outlive
 * the evidence — which is exactly what happened to the encoder-limits line the same day.
 *
 * Growing the ring while debug logging is on costs nothing when it is off, and a reboot undoes it
 * regardless.
 *
 * The parsing lives here, separate from the shell, so the awkward part is testable without a device.
 */
object LogcatRing {

    /** What the ring is grown to while debug logging runs. */
    const val TARGET_SIZE = "8M"

    /**
     * The `main` buffer's size in KiB from a `logcat -g` response, or null when it cannot be read.
     *
     * Null matters as much as the number: an unparseable answer must leave the buffer alone rather
     * than have a default guessed for it. Restoring a size we never really knew would shrink a ring
     * the user or the OEM had deliberately enlarged.
     *
     * Real response this parses (OnePlus 12, ColorOS 16):
     * ```
     * main: ring buffer is 256 KiB (123 KiB consumed, 206 KiB readable), max entry is 5120 B, …
     * ```
     * Some OEMs word it differently or emit nothing at all; both yield null.
     */
    fun parseMainSizeKib(output: String?): Int? {
        if (output.isNullOrBlank()) return null
        val line = output.lineSequence().firstOrNull { it.trimStart().startsWith("main:") } ?: return null
        // Deliberately anchored on "ring buffer is N" rather than the first number on the line: the
        // line carries several sizes, and matching loosely would pick up "consumed" or "max entry".
        val match = Regex("""ring buffer is\s+(\d+)\s*(KiB|MiB|B)""", RegexOption.IGNORE_CASE).find(line)
            ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        if (value <= 0) return null
        return when (match.groupValues[2].lowercase()) {
            "mib" -> value * KIB_PER_MIB
            "kib" -> value
            // A byte figure below 1 KiB is not a ring size anyone set; treat it as unreadable.
            else -> if (value >= BYTES_PER_KIB) value / BYTES_PER_KIB else null
        }
    }

    /** The command that grows the ring. */
    fun growCommand(): String = "logcat -b main -G $TARGET_SIZE"

    /** The command that puts a previously-read size back, in the units logcat accepts. */
    fun restoreCommand(previousKib: Int): String = "logcat -b main -G ${previousKib}K"

    private const val KIB_PER_MIB = 1024
    private const val BYTES_PER_KIB = 1024
}
