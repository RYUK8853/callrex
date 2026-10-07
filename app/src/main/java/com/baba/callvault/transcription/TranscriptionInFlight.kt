/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Which recording is being transcribed right now.
 *
 * Exists so a delete can tell whether it has just destroyed the subject of a run that is still
 * going. Deleting a recording mid-transcription used to leave whisper grinding through audio nobody
 * would ever read, holding the single transcription thread, so everything queued behind it waited
 * for a result that would be thrown away (mirror176, issue #35).
 *
 * Deliberately not part of [TranscriptionEngine]: the engine answers "is something running", which
 * is a question about the CPU, and this answers "running *what*", which is a question about the
 * library. Keeping it separate also keeps it free of the native library, so it can be tested.
 */
object TranscriptionInFlight {

    @Volatile
    private var current: String? = null

    /** The recording being transcribed, or null when nothing is. */
    val displayName: String? get() = current

    /** Claimed by the runner as a recording starts. */
    fun claim(displayName: String) {
        current = displayName
    }

    /**
     * Released by the runner as a recording ends.
     *
     * Guarded on the name rather than clearing outright: in a batch the next recording may already
     * have claimed it, and clearing that claim would leave a delete in the window with nothing to
     * stop.
     */
    fun release(displayName: String) {
        if (current == displayName) current = null
    }

    /** Only for tests, which must not leak a claim into the next one. */
    fun releaseAll() {
        current = null
    }

    /** True when [displayNames] includes the recording being transcribed right now. */
    fun isAmong(displayNames: Collection<String>): Boolean {
        val running = current ?: return false
        return running in displayNames
    }
}
