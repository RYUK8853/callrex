/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.content.Context
import java.io.File

/**
 * The first setup, written down whether or not anyone asked for a log.
 *
 * **Why it exists.** The debug log is off by default, and a phone that cannot record from the very
 * first launch is exactly the phone whose owner has not been told to switch anything on. By the time
 * we ask, the moment is gone — and one reporter had already uninstalled. This file is small, narrow
 * and always there, so that first attempt can be read afterwards.
 *
 * **What it holds.** Only the lines [SetupJournalPolicy] allows: finding the ADB service, connecting
 * to it, launching the daemon, the grants and the modes. Nothing about a call, its audio or its text.
 * The lines are already redacted by [AppLogger] before they arrive here.
 *
 * **How it ends.** It stops at [SetupJournalPolicy.MAX_BYTES] and it stops for good the first time a
 * recorder connects — the two ways of saying "there is nothing more to learn here". It never rotates:
 * the first run is the valuable part, and rotation is the one behaviour that would discard it.
 *
 * Every method swallows its own failures. A diagnostic that can break the app it diagnoses is worse
 * than no diagnostic.
 */
object SetupJournal {

    private const val TAG = "CV:SetupJournal"

    @Volatile private var file: File? = null
    @Volatile private var bytesWritten = 0L
    @Volatile private var closed = false

    /** Opens (or reopens) the journal for this process. Called once, from [AppLogger.init]. */
    fun init(context: Context) {
        runCatching {
            val f = File(context.filesDir, "setup-journal.log")
            file = f
            bytesWritten = if (f.exists()) f.length() else 0L
            // A journal that already ended stays ended across restarts: the marker is the last line,
            // so a phone that has recorded successfully once never starts writing again.
            closed = bytesWritten > 0 && f.useLines { lines -> lines.lastOrNull()?.contains(END_MARKER) == true }
            if (bytesWritten == 0L) append("=== CallVault setup journal — first run, always on ===")
        }.onFailure { android.util.Log.w(TAG, "Could not open the setup journal: ${it.message}") }
    }

    /**
     * Records one already-redacted line, if the policy allows it.
     *
     * Called from [AppLogger] on every log line in the app, so the refusal path does no work beyond a
     * set lookup and two volatile reads.
     */
    fun record(tag: String, line: String) {
        if (!SetupJournalPolicy.shouldRecord(tag, closed, bytesWritten)) return
        append(line)
        if (bytesWritten >= SetupJournalPolicy.MAX_BYTES) {
            append("=== journal full; setup was still not finished at this point $END_MARKER ===")
            closed = true
        }
    }

    /**
     * Ends the journal because a recorder connected — setup worked, and the file has served.
     *
     * Idempotent, and safe to call from any thread: the recorder can connect more than once, and only
     * the first time is worth a line.
     */
    fun markSetupSucceeded() {
        if (closed) return
        closed = true
        append("=== a recorder connected; setup finished $END_MARKER ===")
    }

    /** The journal's size on disk, for the Debug screen. Zero when there is none. */
    fun sizeBytes(): Long = runCatching { file?.takeIf { it.exists() }?.length() ?: 0L }.getOrDefault(0L)

    /** The journal's contents for a debug report, or null when there is nothing to show. */
    fun readForReport(): String? = runCatching {
        file?.takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    /**
     * Deletes it, for a user who deletes their logs. "Gone" has to mean gone here too — and then it
     * starts again, because the alternative is a journal that is silently dead until the next cold
     * start.
     *
     * That is not hypothetical: deleting the logs mid-session left nothing to collect into, the setup
     * path says nothing at all on a phone that is already recording, and the next bug report went out
     * with no journal in it. Re-seeding the header here means the file exists from this moment on.
     */
    fun clear() {
        runCatching {
            file?.delete()
            bytesWritten = 0L
            closed = false
            append("=== CallVault setup journal — restarted after the log was deleted ===")
        }.onFailure { android.util.Log.w(TAG, "Could not clear the setup journal: ${it.message}") }
    }

    private fun append(line: String) {
        val f = file ?: return
        runCatching {
            f.appendText(line + "\n")
            bytesWritten = f.length()
        }.onFailure { android.util.Log.w(TAG, "Could not write to the setup journal: ${it.message}") }
    }

    /** Written into the closing line, so a reopened journal can tell it has already finished. */
    private const val END_MARKER = "[journal-end]"
}
