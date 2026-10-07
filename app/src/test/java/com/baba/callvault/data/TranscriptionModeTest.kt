/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which mode does what, kept in one place so the scheduler and the call-end hook cannot drift apart.
 */
class TranscriptionModeTest {

    @Test
    fun only_after_each_call_transcribes_when_a_call_ends() {
        assertTrue(TranscriptionMode.AFTER_EACH_CALL.transcribesOnCallEnd)
        assertFalse(TranscriptionMode.MANUAL.transcribesOnCallEnd)
        // The daily sweep would pick a new call up anyway, at the chosen hour; doing it twice would
        // pay for the same call's CPU twice over.
        assertFalse(TranscriptionMode.AUTOMATIC.transcribesOnCallEnd)
    }

    @Test
    fun only_automatic_schedules_the_periodic_sweep() {
        assertTrue(TranscriptionMode.AUTOMATIC.needsPeriodicSweep)
        assertFalse(TranscriptionMode.MANUAL.needsPeriodicSweep)
        // Per-call is not a backlog sweep: someone who picks it is saying "keep up with new calls",
        // not "work through years of old ones".
        assertFalse(TranscriptionMode.AFTER_EACH_CALL.needsPeriodicSweep)
    }

    @Test
    fun an_unknown_or_missing_key_falls_back_to_manual() {
        // Manual is the safe default: transcription costs roughly the call's own duration in CPU, so
        // a corrupt preference must never start doing that to someone's phone unasked.
        assertEquals(TranscriptionMode.MANUAL, TranscriptionMode.fromKey(null))
        assertEquals(TranscriptionMode.MANUAL, TranscriptionMode.fromKey("nonsense"))
    }

    @Test
    fun every_mode_survives_a_round_trip_through_its_key() {
        // The key is persisted; renaming one silently resets that user to Manual.
        TranscriptionMode.entries.forEach { mode ->
            assertEquals(mode, TranscriptionMode.fromKey(mode.key))
        }
    }
}
