/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stopgap that stops a long recording taking the heap with it.
 *
 * Transcription decodes the whole file into memory before the model sees any of it, so a long call ends
 * in a crash or a silently dead job after a long wait. Refusing up front is strictly better — and the
 * edges matter, because the two ways to get this wrong are refusing recordings that would have worked
 * and letting through the ones that will not.
 */
class TranscriptionLengthLimitTest {

    /**
     * Derived from the constant, never a literal.
     *
     * These tests hardcoded 15 and broke the day the limit moved to 20 — which is backwards: the
     * limit is explicitly a stopgap meant to change as the decode path improves, so a test that
     * pins the *number* fails for the one reason that is not a bug. What is under test is the
     * boundary behaviour, not the value.
     */
    private val limitMs = TranscriptionLengthLimit.MAX_MINUTES * 60L * 1000

    @Test
    fun `a short recording is fine`() {
        assertFalse(TranscriptionLengthLimit.isTooLong(30_000L))
    }

    @Test
    fun `exactly the limit is still allowed`() {
        // The limit is the longest length that works, not the shortest that fails.
        assertFalse(TranscriptionLengthLimit.isTooLong(limitMs))
    }

    @Test
    fun `a millisecond over the limit is refused`() {
        assertTrue(TranscriptionLengthLimit.isTooLong(limitMs + 1))
    }

    @Test
    fun `an unknown length is allowed through`() {
        // The container returns 0 when it declares no duration. Refusing on "unknown" would block
        // ordinary short recordings with missing metadata — a worse failure than letting a rare long
        // one reach the limit that already exists, which is the heap.
        assertFalse(TranscriptionLengthLimit.isTooLong(0L))
    }

    @Test
    fun `the call-log duration in seconds is judged the same way`() {
        assertFalse(TranscriptionLengthLimit.isTooLong(durationSeconds = TranscriptionLengthLimit.MAX_MINUTES * 60L))
        assertTrue(TranscriptionLengthLimit.isTooLong(durationSeconds = TranscriptionLengthLimit.MAX_MINUTES * 60L + 1))
    }

    @Test
    fun `an unknown call-log duration is allowed through`() {
        // Null is routine: VoIP calls are often not in the call log at all.
        assertFalse(TranscriptionLengthLimit.isTooLong(durationSeconds = null))
    }
}
