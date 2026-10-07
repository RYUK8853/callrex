/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An open/close ledger for every microphone this process opens.
 *
 * **Written because "we released it" and "the microphone is free" are different claims, and the logs
 * only ever made the first one.** A user reported the recorder process left holding the microphone
 * after a call; the teardown logs looked perfect, because every one of them announced an *intention*:
 *
 *  - `releaseHeld` printed "released" after two `runCatching` blocks that swallow whatever happened.
 *  - The handoff record is one of **five** `AudioRecord` sites in this process — direct capture and the
 *    VoIP session's near/re-take records are the others — so releasing it says nothing about the rest.
 *  - Nothing ever asked, at the end of a call, whether anything was still open.
 *
 * So this records outcomes instead. Each capture gets an id that appears at both ends, releases log
 * what actually happened, and [assertNoneLive] turns "did we leak a microphone?" from an inference into
 * a line in the report.
 *
 * Worth knowing when reading one of those reports: this process runs as uid 2000, which belongs to
 * `com.android.shell`, and it presents **no package of its own** to AppOps (`opPackage=<none>`). Android
 * therefore attributes any capture here to "Shell" in the privacy indicator — our daemon, a leftover
 * scrcpy-server and a Shizuku-hosted service are indistinguishable to the user, and all three look like
 * the system's Shell app. Telling them apart is only possible from in here, which is the whole point.
 */
object CaptureAudit {

    private const val TAG = "CV:CaptureAudit"

    private val nextId = AtomicInteger(1)

    /** id -> what it was opened for. Concurrent: VoIP opens two records from different threads. */
    private val live = ConcurrentHashMap<Int, String>()

    /**
     * Records that a microphone was opened, and returns the id to close it with.
     *
     * @param what human-readable and specific — it is what a report will name when something leaks.
     */
    fun opened(what: String): Int {
        val id = nextId.getAndIncrement()
        live[id] = what
        AppLogger.i(TAG, "capture#$id opened: $what (now live: ${live.size})")
        return id
    }

    /**
     * Records the **outcome** of closing a capture.
     *
     * @param error whatever `release()` threw, or null when it returned normally. Passing the failure
     *   rather than swallowing it is the entire difference between this and the logging it replaces.
     */
    fun released(id: Int, error: Throwable? = null) {
        val what = live.remove(id) ?: "unknown (already released, or never registered)"
        if (error == null) {
            AppLogger.i(TAG, "capture#$id released: $what (still live: ${live.size})")
        } else {
            AppLogger.w(TAG, "capture#$id release FAILED: $what — ${error.message} (still live: ${live.size})")
        }
    }

    /**
     * Says whether this process is still holding a microphone, and names what if so.
     *
     * Called at the end of every capture teardown. A clean line here is the evidence that was missing
     * from the stuck-microphone report; a dirty one names the culprit outright, which no amount of
     * reading the old logs could do.
     */
    fun assertNoneLive(context: String) {
        if (live.isEmpty()) {
            AppLogger.i(TAG, "$context: no capture left open in this process")
        } else {
            val held = live.entries.joinToString { "#${it.key} ${it.value}" }
            AppLogger.w(TAG, "$context: ${live.size} capture(s) STILL OPEN — $held")
        }
    }

    /** How many captures this process currently believes it holds. */
    fun liveCount(): Int = live.size
}
