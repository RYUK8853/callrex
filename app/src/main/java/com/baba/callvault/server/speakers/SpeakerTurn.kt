/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import com.baba.callvault.utils.AppLogger

/**
 * Which side of the call was audible.
 *
 * **A and B, not You and Them, deliberately.** The capture reliably separates the two *directions*
 * onto two channels, but which index carries the near party is an OEM detail Android never specifies
 * — it documents `VOICE_CALL` as "uplink + downlink" and stops there. The mapping is learned
 * separately and applied at the display layer, so nothing stored here can be wrong about it later.
 */
enum class SpeakerChannel(val key: String) {
    A("A"),
    B("B"),

    /** Both sides at once — double-talk, common enough on real calls to deserve its own state. */
    BOTH("+"),

    /** Neither side above the noise floor. */
    SILENCE("-");

    companion object {
        fun fromKey(key: String): SpeakerChannel? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One stretch of a call attributed to a side.
 *
 * Turns are contiguous: a turn runs until the next one starts, or until the recording ends, so only
 * the start is stored. That keeps a 30-minute call to a few hundred entries instead of 18,000.
 */
data class SpeakerTurn(val startMs: Long, val channel: SpeakerChannel)

/**
 * Serialises turns for the trip from the privileged daemon to the app.
 *
 * A compact string rather than a Parcelable because it crosses an AIDL boundary that must stay
 * trivially backward compatible: a daemon from an older build simply returns nothing, and an app
 * reading an unfamiliar payload must degrade to "no speaker data" rather than fail a recording.
 *
 * Format: `startMs:channel` pairs joined by `;` — e.g. `0:A;1200:+;3400:-`.
 */
object SpeakerTurnCodec {

    private const val TAG = "CV:SpeakerTurnCodec"
    private const val TURN_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ":"

    fun encode(turns: List<SpeakerTurn>): String =
        turns.joinToString(TURN_SEPARATOR) { "${it.startMs}$FIELD_SEPARATOR${it.channel.key}" }

    /**
     * Parses [encoded], returning an empty list for anything it cannot read.
     *
     * Never throws. This is the receiving end of a cross-process call, and the caller's next action is
     * finishing a recording — the one thing that must not be put at risk by a decoding fault.
     */
    fun decode(encoded: String): List<SpeakerTurn> {
        if (encoded.isBlank()) return emptyList()

        return runCatching {
            encoded.split(TURN_SEPARATOR).map { entry ->
                val (start, channel) = entry.split(FIELD_SEPARATOR).let { it[0] to it[1] }
                SpeakerTurn(
                    startMs = start.toLong(),
                    channel = SpeakerChannel.fromKey(channel) ?: error("unknown channel '$channel'")
                )
            }
        }.getOrElse {
            AppLogger.w(TAG, "Ignoring malformed speaker turns: ${it.message}")
            emptyList()
        }
    }
}
