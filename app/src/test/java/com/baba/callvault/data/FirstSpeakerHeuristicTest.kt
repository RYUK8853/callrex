/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which channel is the far party, guessed from who spoke first on an outgoing call.
 *
 * The convention is strong and nearly universal: you dial, you listen to it ring, and the person who
 * answers says the first word. So on an **outgoing** call the first voice belongs to the far party.
 *
 * It is a convention rather than a law, which is why nothing here is trusted on its own — the caller
 * needs two calls to agree before any name is shown, and the transcript offers a one-tap swap for as
 * long as the answer has never been confirmed.
 *
 * Incoming calls are deliberately not read. There the same convention points the other way and less
 * reliably: the answerer usually speaks first, but a caller who opens with "it's me" is common
 * enough to poison the evidence, and a wrong name is the one outcome worth avoiding.
 */
class FirstSpeakerHeuristicTest {

    @Test
    fun `the first voice on an outgoing call is the far party`() {
        // Arrange: B speaks first, then A — the shape of every answered call.
        val turns = "0:-;1200:B;4000:A"

        // Act
        val observed = FirstSpeakerHeuristic.observe(turns)

        // Assert
        assertEquals(ChannelMap.B_IS_FAR, observed)
    }

    @Test
    fun `reads the other channel just as readily`() {
        assertEquals(ChannelMap.A_IS_FAR, FirstSpeakerHeuristic.observe("0:-;900:A;3000:B"))
    }

    @Test
    fun `ignores silence and double-talk before the first real voice`() {
        // The ringing phase is silence, and the moment of connection often reads as both at once.
        assertEquals(ChannelMap.B_IS_FAR, FirstSpeakerHeuristic.observe("0:-;500:+;800:B;2500:A"))
    }

    @Test
    fun `refuses when only one side ever speaks`() {
        // A one-sided recording says nothing about which channel is whose — and it is exactly what a
        // broken capture produces, so reading it as evidence would turn a fault into a wrong name.
        assertEquals(ChannelMap.UNKNOWN, FirstSpeakerHeuristic.observe("0:-;1000:A;5000:-"))
    }

    @Test
    fun `refuses when the first voice is too brief to be speech`() {
        // A single 100 ms blip is a click on the line, not a greeting.
        assertEquals(ChannelMap.UNKNOWN, FirstSpeakerHeuristic.observe("0:-;1000:B;1100:A;4000:B"))
    }

    @Test
    fun `refuses an empty or unreadable turn list`() {
        assertEquals(ChannelMap.UNKNOWN, FirstSpeakerHeuristic.observe(""))
        assertEquals(ChannelMap.UNKNOWN, FirstSpeakerHeuristic.observe("not a turn list"))
    }

    @Test
    fun `refuses a call that was silent throughout`() {
        assertEquals(ChannelMap.UNKNOWN, FirstSpeakerHeuristic.observe("0:-"))
    }
}
