/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import com.baba.callvault.server.speakers.SpeakerChannel
import com.baba.callvault.server.speakers.SpeakerTurnCodec

/**
 * Guesses which captured channel is the far party from who spoke first on an outgoing call.
 *
 * **Why a guess at all.** Which channel carries the far party is an OEM detail Android never states,
 * and both ways of measuring it are closed on the reference device: the ringback never enters the
 * capture, and comparing against a `VOICE_DOWNLINK` probe costs the recording its near side. What is
 * left is a convention — you dial, you listen to it ring, and whoever answers speaks first — so on an
 * outgoing call the first voice is the far party's.
 *
 * **It is never trusted alone.** Two calls must agree before a name is shown
 * ([ChannelMapCorroboration]), and until the user has confirmed or corrected it once, the transcript
 * offers a one-tap swap. A convention that holds nearly always still fails sometimes, and a wrong
 * attribution puts one person's words under the other's name.
 *
 * **Incoming calls are not read.** The convention there points the other way and holds less firmly:
 * the answerer usually speaks first, but a caller opening with "it's me" is common enough to poison
 * the evidence, and half-right evidence is worse than none when two observations are enough to
 * settle it.
 */
object FirstSpeakerHeuristic {

    /**
     * The far-party channel suggested by [encodedTurns], or [ChannelMap.UNKNOWN].
     *
     * @param encodedTurns `SpeakerTurnCodec` output for an **outgoing** call. The caller checks the
     *   direction; this function has no way to know it.
     */
    fun observe(encodedTurns: String): ChannelMap {
        val turns = SpeakerTurnCodec.decode(encodedTurns)
        if (turns.isEmpty()) return ChannelMap.UNKNOWN

        val firstVoiced = turns.indexOfFirst { it.channel == SpeakerChannel.A || it.channel == SpeakerChannel.B }
        if (firstVoiced < 0) return ChannelMap.UNKNOWN

        // A greeting, not a click. The turn runs until the next one starts; a final turn runs to the
        // end of the call, which is certainly long enough.
        val start = turns[firstVoiced].startMs
        val end = turns.getOrNull(firstVoiced + 1)?.startMs
        if (end != null && end - start < MIN_GREETING_MS) return ChannelMap.UNKNOWN

        // Both sides must be heard. A recording carrying only one of them says nothing about which
        // channel is whose — and is exactly what a broken capture produces, so reading it would turn
        // a fault into a confident wrong name.
        val voices = turns.map { it.channel }.filter { it == SpeakerChannel.A || it == SpeakerChannel.B }.toSet()
        if (voices.size < 2) return ChannelMap.UNKNOWN

        return when (turns[firstVoiced].channel) {
            SpeakerChannel.A -> ChannelMap.A_IS_FAR
            else -> ChannelMap.B_IS_FAR
        }
    }

    /** Shorter than this, the first "voice" is a click on the line rather than someone answering. */
    private const val MIN_GREETING_MS = 400L
}
