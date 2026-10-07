/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import com.baba.callvault.server.speakers.SpeakerChannel

/**
 * Puts names to the channels a transcript was labelled with.
 *
 * **This is the whole reason segments store `A` and `B` rather than names.** Which captured channel
 * carries the far party is an OEM detail the app learns from ringback over several calls, so the
 * answer can arrive long after a transcript was written — and can be *lost* again if the calls that
 * taught it are deleted. Resolving here, on every read, means a mapping learned tomorrow improves
 * every transcript already on the phone, and a mapping lost falls back to neutral labels instead of
 * leaving a name that is now a guess.
 *
 * @param map     the trusted mapping, or [ChannelMap.UNKNOWN] to stay neutral.
 * @param you     what to call the phone's owner.
 * @param contact who the call was with — the same name the transcript is titled with.
 * @param sideA   the neutral label for channel A, used while the mapping is unknown.
 * @param sideB   the neutral label for channel B.
 */
data class SpeakerNames(
    val map: ChannelMap,
    val you: String,
    val contact: String,
    val sideA: String,
    val sideB: String,
    /**
     * Whether the user has yet to settle who is who.
     *
     * Drives the offer to name or correct the two sides, in both directions: while [map] is
     * [ChannelMap.UNKNOWN] the offer is the only way to get names at all, and once something has
     * been worked out it becomes the way to disagree with it. False only once the user has answered.
     */
    val isGuess: Boolean = false
) {

    /**
     * What to show for a segment labelled [speaker], or null to show no speaker column at all.
     *
     * Null for an unattributed segment and for any label this does not recognise — a diarization
     * model may one day write its own labels into the same column, and showing `speaker_3` verbatim
     * would be worse than showing nothing.
     */
    fun of(speaker: String?): String? = when (speaker) {
        SpeakerChannel.A.key -> when (map) {
            ChannelMap.A_IS_FAR -> contact
            ChannelMap.B_IS_FAR -> you
            ChannelMap.UNKNOWN -> sideA
        }

        SpeakerChannel.B.key -> when (map) {
            ChannelMap.A_IS_FAR -> you
            ChannelMap.B_IS_FAR -> contact
            ChannelMap.UNKNOWN -> sideB
        }

        else -> null
    }
}
