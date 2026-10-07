/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grouping raw log lines into entries, so the debug report can merge two processes by timestamp.
 *
 * The report interleaves the app's log file with the recorder host's lines, which means sorting. Sorting
 * *raw lines* would scatter the continuation lines of a stack trace all over the file and turn the most
 * useful thing in a bug report into confetti — so entries have to be grouped first, and that grouping is
 * what these tests pin down.
 */
class AppLoggerEntriesTest {

    private val a = "2026-08-25 10:00:00.100 [I] CV:Тag: first"
    private val b = "2026-08-25 10:00:01.200 [W] CV:Tag: second"

    @Test
    fun `each timestamped line is its own entry`() {
        assertEquals(listOf(a, b), AppLogger.entriesOf(listOf(a, b)))
    }

    @Test
    fun `a stack trace stays attached to the line that produced it`() {
        val trace1 = "java.lang.IllegalStateException: boom"
        val trace2 = "\tat com.baba.callvault.Thing.method(Thing.kt:42)"

        val entries = AppLogger.entriesOf(listOf(a, trace1, trace2, b))

        assertEquals(2, entries.size)
        assertEquals("$a\n$trace1\n$trace2", entries[0])
        assertEquals(b, entries[1])
    }

    @Test
    fun `a file that starts mid-entry does not lose its first lines`() {
        // Trimming cuts the file at an arbitrary line, so the first line of a report is often a
        // continuation with no timestamp. Dropping it would silently eat the top of the log.
        val orphan = "\tat com.baba.callvault.Thing.method(Thing.kt:42)"

        val entries = AppLogger.entriesOf(listOf(orphan, a))

        assertEquals(listOf(orphan, a), entries)
    }

    @Test
    fun `an empty log produces no entries`() {
        assertEquals(emptyList<String>(), AppLogger.entriesOf(emptyList()))
    }

    @Test
    fun `entries sort chronologically once grouped, trace and all`() {
        // The point of the exercise: a host line landing between two app lines must sort between them,
        // and must not split an app entry's trace.
        val hostLine = "2026-08-25 10:00:00.500 [I] CV:RecorderServer: releaseHeld"
        val trace = "\tat com.baba.callvault.Thing.method(Thing.kt:42)"

        val merged = (AppLogger.entriesOf(listOf(a, trace, b)) + listOf(hostLine))
            .sortedBy { it.take(23) }

        assertEquals(listOf("$a\n$trace", hostLine, b), merged)
    }
}
