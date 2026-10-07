/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Decides whether a transcription runs as a foreground service, which is what puts the "your phone may
 * get warm" notification in the shade.
 *
 * A run the user started from the app has just been through a confirmation dialog that said the same
 * thing, so posting it again is noise rather than information (mirror176, issue #31). An automatic
 * run — nightly, or after a call — has had no such moment, and the notification is the only sign it is
 * happening, so it keeps one.
 *
 * The exception is length. A job that is not a foreground service may be stopped by Android once it has
 * run ten minutes — always on Android 11, and from Android 12 whenever the system is busy, which a
 * transcription saturating every core makes it (JobScheduler reference). A stopped transcription starts
 * again from nothing. So a long run keeps the notification even when the user asked for it, because
 * losing the work is worse than being told twice.
 */
object TranscriptionNoticePolicy {

    /**
     * Past this a run is treated as long enough to be stopped. Two minutes short of Android's ten, to
     * leave room for an estimate that is optimistic and for the model load it does not always include.
     */
    const val LONG_RUN_MS: Long = 8 * 60_000L

    /**
     * @param userRequested the user started this run from the app, after the confirmation dialog.
     * @param estimatedMs how long the run is expected to take, or 0 when that is not known — which is
     *   treated as long, since a run that cannot be shown to be short cannot be shown to be safe.
     */
    fun showsNotification(userRequested: Boolean, estimatedMs: Long): Boolean {
        if (!userRequested) return true
        if (estimatedMs <= 0L) return true
        return estimatedMs >= LONG_RUN_MS
    }
}
