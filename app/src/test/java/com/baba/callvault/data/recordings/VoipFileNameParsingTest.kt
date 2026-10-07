/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers how a VoIP recording's filename is read back for the Home list.
 *
 * This grammar has caused two user-visible bugs, hence the coverage. Recordings are written as
 * `{date}_voip-{App}[_{caller}]`: the app rides on the marker so that a call with an app but no caller
 * cannot be confused with a call with a caller but no app. The earlier `_voip_{App}_{caller}` form put
 * both in underscore slots, and a Signal call with no contact name — `_voip_Signal` — was read as a
 * call with someone named "Signal" and no app, which silently dropped its app badge.
 *
 * Files in the old form still exist on users' devices, so both are parsed.
 */
class VoipFileNameParsingTest {

    private fun parse(name: String) = RecordingsRepository.parseName(name)

    @Test
    fun `reads the app and the caller`() {
        val parsed = parse("20260726_165913.639+0300_voip-Telegram_Feroza.ogg")
        assertEquals("Telegram", parsed.voipApp)
        assertEquals("Feroza", parsed.contactName)
        assertNull("VoIP calls have no direction", parsed.direction)
    }

    @Test
    fun `reads an app with no caller as the app, not as the caller`() {
        // The Signal regression: this used to yield voipApp=null and contactName="Signal".
        val parsed = parse("20260726_165943.610+0300_voip-Signal.ogg")
        assertEquals("Signal", parsed.voipApp)
        assertNull(parsed.contactName)
    }

    @Test
    fun `reads a bare recording with neither app nor caller`() {
        val parsed = parse("20260726_161919.119+0300_voip.ogg")
        assertNull(parsed.voipApp)
        assertNull(parsed.contactName)
    }

    @Test
    fun `keeps a caller name that itself contains underscores`() {
        val parsed = parse("20260726_165913.639+0300_voip-WhatsApp_Dana_Liza.ogg")
        assertEquals("WhatsApp", parsed.voipApp)
        assertEquals("Dana_Liza", parsed.contactName)
    }

    @Test
    fun `keeps non-latin caller names intact`() {
        val parsed = parse("20260726_165913.639+0300_voip-Telegram_גבריאל.ogg")
        assertEquals("Telegram", parsed.voipApp)
        assertEquals("גבריאל", parsed.contactName)
    }

    @Test
    fun `handles the m4a container as well as ogg`() {
        assertEquals("Signal", parse("20260726_165943.610+0300_voip-Signal.m4a").voipApp)
    }

    @Test
    fun `still reads legacy names that put the app in its own slot`() {
        val parsed = parse("20260726_155545.223+0300_voip_WhatsApp_Feroza.ogg")
        assertEquals("WhatsApp", parsed.voipApp)
        assertEquals("Feroza", parsed.contactName)
    }

    @Test
    fun `legacy single-token names stay read as the caller`() {
        // Unknowable in the old grammar; documented here so the fallback is not "fixed" into guessing.
        val parsed = parse("20260726_155545.223+0300_voip_Feroza.ogg")
        assertNull(parsed.voipApp)
        assertEquals("Feroza", parsed.contactName)
    }

    @Test
    fun `does not treat a carrier recording as VoIP`() {
        val parsed = parse("20260726_104021.900+0300_out_Feroza.ogg")
        assertNull(parsed.voipApp)
        assertEquals(RecordingDirection.OUTGOING, parsed.direction)
    }
}
