/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is being transcribed right now — the question a delete has to ask before it can know whether
 * there is work to abandon (mirror176, issue #35).
 */
class TranscriptionInFlightTest {

    @After
    fun clear() = TranscriptionInFlight.releaseAll()

    @Test
    fun nothing_is_in_flight_before_a_run_starts() {
        assertNull(TranscriptionInFlight.displayName)
        assertFalse(TranscriptionInFlight.isAmong(listOf("anything.ogg")))
    }

    @Test
    fun the_claimed_recording_is_the_one_in_flight() {
        TranscriptionInFlight.claim("call.ogg")

        assertEquals("call.ogg", TranscriptionInFlight.displayName)
        assertTrue(TranscriptionInFlight.isAmong(listOf("other.ogg", "call.ogg")))
    }

    @Test
    fun deleting_a_different_recording_does_not_touch_the_run() {
        TranscriptionInFlight.claim("call.ogg")

        assertFalse(TranscriptionInFlight.isAmong(listOf("other.ogg")))
    }

    @Test
    fun releasing_clears_the_claim() {
        TranscriptionInFlight.claim("call.ogg")
        TranscriptionInFlight.release("call.ogg")

        assertNull(TranscriptionInFlight.displayName)
        assertFalse(TranscriptionInFlight.isAmong(listOf("call.ogg")))
    }

    /**
     * A batch hands the claim from one recording to the next. Releasing the one that has finished
     * must not clear the claim the next one has already taken, or a delete arriving in that window
     * would find nothing to stop.
     */
    @Test
    fun releasing_a_recording_that_is_no_longer_current_leaves_the_claim_alone() {
        TranscriptionInFlight.claim("first.ogg")
        TranscriptionInFlight.claim("second.ogg")

        TranscriptionInFlight.release("first.ogg")

        assertEquals("second.ogg", TranscriptionInFlight.displayName)
    }

    @Test
    fun an_empty_delete_never_matches() {
        TranscriptionInFlight.claim("call.ogg")

        assertFalse(TranscriptionInFlight.isAmong(emptyList()))
    }
}
