/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Home pill shows while transcription is running.
 *
 * Decision A3: silent, but visible while working — an overnight backlog is hours of sustained CPU, and
 * a phone that is warm for no visible reason is not acceptable. The rule for *what* it says lives here
 * rather than in the composable, because "0/0" or a stuck-looking "1/1" are exactly the kind of wrong
 * that renders perfectly.
 */
class TranscribingPillStateTest {

    @Test
    fun is_absent_when_nothing_is_being_transcribed() {
        // The common case, and the whole point of A3: idle must show nothing at all.
        assertEquals(
            TranscribingPillState.Hidden,
            TranscribingPillState.from(isRunning = false, completed = 0, total = 0, current = null)
        )
    }

    @Test
    fun is_absent_when_a_finished_run_still_reports_its_last_progress() {
        // WorkManager keeps the final progress of a finished worker; without the isRunning gate the
        // pill would stay on screen for ever after an overnight sweep.
        assertEquals(
            TranscribingPillState.Hidden,
            TranscribingPillState.from(isRunning = false, completed = 12, total = 12, current = "call.ogg")
        )
    }

    @Test
    fun shows_position_in_the_backlog_while_running() {
        val state = TranscribingPillState.from(
            isRunning = true, completed = 2, total = 12, current = "call.ogg"
        )

        // Two finished means the third is in hand: people count the one being worked on.
        assertEquals(TranscribingPillState.Batch(position = 3, total = 12, current = "call.ogg"), state)
    }

    @Test
    fun shows_a_single_item_form_for_a_one_off_rather_than_a_count() {
        // A tapped single recording is not a backlog; "1/1" would read as a sweep that has stalled.
        val state = TranscribingPillState.from(
            isRunning = true, completed = 0, total = 1, current = "call.ogg"
        )

        assertEquals(TranscribingPillState.Single(current = "call.ogg"), state)
    }

    @Test
    fun shows_an_indeterminate_form_before_the_worker_has_published_anything() {
        // A worker that has just started has no progress yet. "0/0" would be a lie; showing nothing
        // would make the pill flicker in a second after the tap.
        val state = TranscribingPillState.from(
            isRunning = true, completed = 0, total = 0, current = null
        )

        assertEquals(TranscribingPillState.Starting, state)
    }

    @Test
    fun never_counts_past_the_total() {
        // The last recording is "12/12" while it is being worked on, not "13/12".
        val state = TranscribingPillState.from(
            isRunning = true, completed = 12, total = 12, current = "last.ogg"
        )

        assertEquals(TranscribingPillState.Batch(position = 12, total = 12, current = "last.ogg"), state)
    }

    @Test
    fun the_transcribing_pill_takes_the_slot_from_the_support_pill_while_it_is_working() {
        // Both want the one titleTrailing slot. Transcription wins because it is transient and
        // explains something happening right now; the support pill is always available and loses
        // nothing by waiting.
        assertTrue(TranscribingPillState.Starting.occupiesTitleSlot)
        assertTrue(TranscribingPillState.Single("call.ogg").occupiesTitleSlot)
        assertTrue(TranscribingPillState.Batch(3, 12, "call.ogg").occupiesTitleSlot)
    }

    @Test
    fun the_support_pill_keeps_the_slot_when_nothing_is_transcribing() {
        assertTrue(!TranscribingPillState.Hidden.occupiesTitleSlot)
    }
}
