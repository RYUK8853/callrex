/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * How far into the **saved audio** a recording currently is.
 *
 * Not the same as wall-clock time since the call started, and the difference is the whole reason
 * this exists: pausing stops frames being written but does not stop the clock on the wall. A
 * bookmark placed using wall time would drift by exactly the length of every pause before it, so a
 * flag dropped after a two-minute pause would land two minutes late in a file that never contained
 * those two minutes.
 *
 * Deliberately does not touch the capture path — it only counts. The audio pipeline is the most
 * safety-critical code in the app and a bookmark feature has no business reaching into it.
 *
 * @param now Injectable so the arithmetic can be tested without sleeping.
 */
class RecordingClock(private val now: () -> Long = System::currentTimeMillis) {

    private var startedAtMs: Long? = null
    private var pausedAtMs: Long? = null
    private var pausedTotalMs: Long = 0L

    /** Begins timing. Also resets, so a reused instance cannot inherit the previous call's total. */
    fun start() {
        startedAtMs = now()
        pausedAtMs = null
        pausedTotalMs = 0L
    }

    /** Marks the start of a pause. Ignored when not running, or already paused. */
    fun pause() {
        if (startedAtMs == null || pausedAtMs != null) return
        pausedAtMs = now()
    }

    /** Ends a pause, banking its length. Ignored when not paused. */
    fun resume() {
        val pausedAt = pausedAtMs ?: return
        pausedTotalMs += (now() - pausedAt).coerceAtLeast(0L)
        pausedAtMs = null
    }

    /** Forgets everything, so the next call starts clean. */
    fun reset() {
        startedAtMs = null
        pausedAtMs = null
        pausedTotalMs = 0L
    }

    /**
     * Milliseconds of audio written so far, or null when nothing is being recorded.
     *
     * While paused this holds still — which is the point. Two flags dropped either side of a pause
     * are both correct positions in the finished file, and a flag dropped *during* a pause lands at
     * the moment the audio stopped, which is the only honest answer available.
     */
    fun audioElapsedMs(): Long? {
        val startedAt = startedAtMs ?: return null
        val pausedSoFar = pausedTotalMs + (pausedAtMs?.let { now() - it } ?: 0L)
        return (now() - startedAt - pausedSoFar).coerceAtLeast(0L)
    }
}
