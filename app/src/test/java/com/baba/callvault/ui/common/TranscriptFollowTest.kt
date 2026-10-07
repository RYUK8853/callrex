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

/**
 * Which line of the transcript is being spoken right now.
 *
 * Segment-level, because that is the only thing the stored timestamps can support: whisper reports a
 * start and end per segment, not per word. Highlighting a word would mean guessing where inside a
 * sentence the speaker had got to, and a guess that moves confidently is worse than no highlight.
 */
class TranscriptFollowTest {

    private val starts = listOf(0L, 5_000L, 12_000L, 20_000L)

    @Test
    fun the_line_being_spoken_is_the_last_one_that_has_started() {
        assertEquals(0, TranscriptFollow.activeIndex(starts, positionMs = 0L))
        assertEquals(0, TranscriptFollow.activeIndex(starts, positionMs = 4_999L))
        assertEquals(1, TranscriptFollow.activeIndex(starts, positionMs = 5_000L))
        assertEquals(2, TranscriptFollow.activeIndex(starts, positionMs = 19_999L))
        assertEquals(3, TranscriptFollow.activeIndex(starts, positionMs = 20_000L))
    }

    @Test
    fun the_last_line_stays_lit_to_the_end_of_the_recording() {
        // There is no segment after the last one, and dropping the highlight while the speaker is
        // still finishing their sentence would look like a bug.
        assertEquals(3, TranscriptFollow.activeIndex(starts, positionMs = 600_000L))
    }

    @Test
    fun nothing_is_lit_before_the_first_line() {
        // A call can open with several seconds of ringing or silence. Lighting the first line through
        // that would attach words to a moment they were not spoken in.
        assertEquals(-1, TranscriptFollow.activeIndex(listOf(3_000L, 9_000L), positionMs = 0L))
        assertEquals(-1, TranscriptFollow.activeIndex(listOf(3_000L, 9_000L), positionMs = 2_999L))
    }

    @Test
    fun a_transcript_with_no_lines_highlights_nothing() {
        assertEquals(-1, TranscriptFollow.activeIndex(emptyList(), positionMs = 1_000L))
    }

    @Test
    fun segments_out_of_order_do_not_light_the_wrong_line() {
        // The DAO sorts by start, but this must not quietly depend on it — a binary search over
        // unsorted input would return an arbitrary answer instead of an obviously wrong one.
        assertEquals(1, TranscriptFollow.activeIndex(listOf(10_000L, 0L), positionMs = 1_000L))
    }
}
