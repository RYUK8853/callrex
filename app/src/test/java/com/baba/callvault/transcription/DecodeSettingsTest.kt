/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guards the values that ship and the boundaries the native side relies on.
 *
 * These are cheap assertions about a data class, and they exist for one reason: every field here
 * reaches whisper.cpp as a raw int, where a wrong one does not crash. Beam 0 or a negative context
 * cap would be reinterpreted silently and the only symptom would be a slightly worse transcript
 * months later.
 */
class DecodeSettingsTest {

    @Test
    fun `ships with vad on, greedy decoding and whisper's own rolling context`() {
        // Arrange / Act
        val shipped = DecodeSettings.DEFAULT

        // Assert — VAD-off is a silent revert of a measured win, so that stays pinned.
        //
        // Greedy, NOT beam-5: on a real 8:46 Hebrew call beam-5 *with VAD on* locked whisper into
        // emitting "*ערבית*" for three consecutive 30-second windows and swallowed the first 94
        // seconds of the call whole — 1117 characters of real speech in the VAD-only run reduced to
        // 21. Beam-5 without VAD did no such thing, so this pin is specifically about the pair.
        assertEquals(1, shipped.beamSize)
        assertEquals(true, shipped.useVad)

        // -1, not 0 and not 64. Measured on a real call: 0 fragmented it into 79 one-to-two-second
        // segments and 64 silently dropped two utterances. See [DecodeSettings.maxTextCtx].
        assertEquals(-1, shipped.maxTextCtx)
    }

    @Test
    fun `rejects a beam width below one`() {
        assertThrows(IllegalArgumentException::class.java) { DecodeSettings(beamSize = 0) }
    }

    @Test
    fun `accepts minus one as leave whisper's own context default alone`() {
        // -1 must survive: it is how the benchmark's baseline reproduces the pre-change behaviour,
        // and it is deliberately distinct from 0, which means "no conditioning at all".
        assertEquals(-1, DecodeSettings(maxTextCtx = -1).maxTextCtx)
        assertEquals(0, DecodeSettings(maxTextCtx = 0).maxTextCtx)
    }

    @Test
    fun `rejects a context cap below minus one`() {
        assertThrows(IllegalArgumentException::class.java) { DecodeSettings(maxTextCtx = -2) }
    }

    @Test
    fun `describes itself for the log line that explains a transcript`() {
        assertEquals(
            "beam=1 ctx=0 vad=off",
            DecodeSettings(beamSize = 1, maxTextCtx = 0, useVad = false).toString()
        )
    }
}
