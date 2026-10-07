/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

/**
 * Speaker turns detected in the **app** process, waiting to be stored when the call is catalogued.
 *
 * There are two capture paths and only one of them belongs to the daemon. With resilient recording
 * on, the daemon hands its `AudioRecord` to the app and the stereo frames pass through
 * `HandoffEncoder` instead — so the daemon has no session to ask, and
 * [SpeakerTurnsRepository.collectAfterCall] would find nothing however long it waited. That is
 * exactly what happened on the OP12: turns were never collected on any call, because the phone
 * always took the handoff path.
 *
 * A holder rather than a parameter because the two ends are far apart: the encoder finishes on its
 * own thread inside the handoff machinery, while the collection happens later, after the recording
 * has been catalogued, in a coroutine that knows the display name. Nothing else connects them.
 *
 * Single-call scope by construction: [clear] at capture start, [publish] at encode end, and
 * [takeIfPresent] consumes what it returns so a stale value can never be attached to a later
 * recording — the failure that a plain cache would make silent and permanent.
 */
object CapturedSpeakerTurns {

    @Volatile
    private var pending: String = ""

    /** Forgets anything left over. Called when a capture starts, before it can produce turns. */
    fun clear() {
        pending = ""
    }

    /** Records what this capture heard, encoded by `SpeakerTurnCodec`. */
    fun publish(encoded: String) {
        pending = encoded
    }

    /** Returns the pending turns and forgets them, or an empty string when there are none. */
    fun takeIfPresent(): String {
        val turns = pending
        pending = ""
        return turns
    }
}
