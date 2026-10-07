/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.MediaRecorder.AudioSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #29: telling a real app call apart from something merely playing through the earpiece.
 *
 * The numbers in these tests are the ones measured on the OP12 on 2026-09-06 — see
 * `docs/dev-notes/2026-09-06-issue-29-voicemail-false-call.md`.
 */
class CallEvidenceTest {

    @Test
    fun `a communication capture is evidence of a call`() {
        // Measured: WhatsApp captures with VOICE_COMMUNICATION (7) for the whole call.
        assertTrue(CallEvidence.corroboratesCall(listOf(AudioSource.VOICE_COMMUNICATION)))
    }

    @Test
    fun `the carrier call sources count too`() {
        assertTrue(CallEvidence.corroboratesCall(listOf(AudioSource.VOICE_CALL)))
        assertTrue(CallEvidence.corroboratesCall(listOf(AudioSource.VOICE_UPLINK)))
        assertTrue(CallEvidence.corroboratesCall(listOf(AudioSource.VOICE_DOWNLINK)))
    }

    @Test
    fun `nothing capturing is not a call`() {
        // Measured: audio merely playing shows zero recording configurations.
        assertFalse(CallEvidence.corroboratesCall(emptyList()))
    }

    @Test
    fun `our own capture must never corroborate a call`() {
        // Measured: our daemon's VoipCaptureSession opens AudioSource.MIC, and it shows up in the
        // very same list. If plain MIC counted, our recording would confirm itself.
        assertFalse(CallEvidence.corroboratesCall(listOf(AudioSource.MIC)))
    }

    @Test
    fun `a stray background microphone session is not a call`() {
        // Measured twice on an idle phone: a lone MIC session with the audio mode at NORMAL.
        assertFalse(CallEvidence.corroboratesCall(listOf(AudioSource.MIC, AudioSource.MIC)))
    }

    @Test
    fun `a real call alongside our own capture is still a call`() {
        // The exact pair measured during the WhatsApp call: theirs (7) and ours (1).
        assertTrue(CallEvidence.corroboratesCall(listOf(AudioSource.VOICE_COMMUNICATION, AudioSource.MIC)))
    }

    @Test
    fun `an unreadable list is treated as no evidence either way`() {
        // A device that will not answer must not be read as "definitely not a call": the caller
        // decides what to do with UNKNOWN, and what it does is stay quiet rather than accuse.
        assertFalse(CallEvidence.corroboratesCall(null))
    }
}
