/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Segment timestamps, as they appear beside every line of a transcript. */
class TranscriptTimestampTest {

    @Test
    fun formats_the_start_of_a_call_as_zero() {
        assertEquals("0:00", TranscriptTimestamp.format(0L))
    }

    @Test
    fun pads_seconds_but_not_minutes() {
        assertEquals("0:05", TranscriptTimestamp.format(5_000L))
        assertEquals("1:05", TranscriptTimestamp.format(65_000L))
    }

    @Test
    fun keeps_counting_minutes_past_an_hour_for_a_long_call() {
        // A one-hour call would read "0:30" at the ninety-minute mark if hours were dropped, which is
        // indistinguishable from half a minute in.
        assertEquals("1:01:01", TranscriptTimestamp.format(3_661_000L))
        assertEquals("1:30:00", TranscriptTimestamp.format(5_400_000L))
    }

    @Test
    fun truncates_rather_than_rounds_so_a_line_never_points_past_itself() {
        // Rounding 1999 ms up to 0:02 would seek past the start of the line it labels.
        assertEquals("0:01", TranscriptTimestamp.format(1_999L))
    }

    @Test
    fun treats_a_negative_time_as_zero_instead_of_rendering_a_minus() {
        assertEquals("0:00", TranscriptTimestamp.format(-1L))
    }
}
