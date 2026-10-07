/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.data.transcripts.TranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One slot in a recording row whose meaning changes under the user: it starts a transcription, then
 * becomes the way to read one.
 *
 * The decision lives in a pure function rather than inside the composable, matching how the rest of
 * this codebase is tested — and because "which state shows which affordance" is the part that can
 * actually be wrong.
 */
class TranscriptRowActionTest {

    @Test
    fun offers_to_transcribe_a_recording_that_has_no_transcript() {
        assertEquals(TranscriptRowAction.TRANSCRIBE, TranscriptRowAction.forStatus(TranscriptStatus.NONE))
    }

    @Test
    fun shows_busy_while_queued_or_running() {
        // Queued and running look the same to the user: something is happening, do not tap again.
        assertEquals(TranscriptRowAction.BUSY, TranscriptRowAction.forStatus(TranscriptStatus.QUEUED))
        assertEquals(TranscriptRowAction.BUSY, TranscriptRowAction.forStatus(TranscriptStatus.RUNNING))
    }

    @Test
    fun opens_the_transcript_once_it_is_done() {
        assertEquals(TranscriptRowAction.OPEN, TranscriptRowAction.forStatus(TranscriptStatus.DONE))
    }

    @Test
    fun offers_a_retry_after_a_failure() {
        // The queue will never pick a FAILED recording up again on its own, so this is the only route
        // back for it.
        assertEquals(TranscriptRowAction.RETRY, TranscriptRowAction.forStatus(TranscriptStatus.FAILED))
    }

    @Test
    fun every_status_maps_to_an_action() {
        // Guards the `when` against a status added later that silently renders nothing.
        TranscriptStatus.entries.forEach { status ->
            TranscriptRowAction.forStatus(status) // must not throw
        }
    }

    @Test
    fun only_the_busy_action_ignores_taps() {
        assertFalse(TranscriptRowAction.BUSY.isTappable)
        assertTrue(TranscriptRowAction.TRANSCRIBE.isTappable)
        assertTrue(TranscriptRowAction.OPEN.isTappable)
        assertTrue(TranscriptRowAction.RETRY.isTappable)
    }

    @Test
    fun each_action_has_its_own_content_description() {
        // This is the one affordance whose meaning changes under the user, so a screen reader must not
        // read all four as the same thing.
        val descriptions = TranscriptRowAction.entries.map { it.contentDescriptionRes }

        assertEquals(descriptions.size, descriptions.toSet().size)
    }
}
