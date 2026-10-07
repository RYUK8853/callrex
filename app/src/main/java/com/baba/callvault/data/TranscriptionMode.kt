/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

/**
 * When calls get transcribed.
 *
 * Manual is the default deliberately: transcription costs roughly the call's own duration in CPU, so
 * it is not something to start doing to someone's phone unasked.
 *
 *  - [MANUAL] — only what the user taps.
 *  - [AFTER_EACH_CALL] — each call as it ends, and nothing else. Keeps up with new calls without ever
 *    touching a back catalogue that may be years deep.
 *  - [AUTOMATIC] — sweeps everything not yet transcribed at a chosen time.
 *
 * Only [AUTOMATIC] schedules periodic work; the other two leave WorkManager with no sweep at all.
 *
 * Shaped like [SyncScheduleMode] so both can drive the same dropdown pattern in Settings.
 */
enum class TranscriptionMode(val key: String) {
    MANUAL("manual"),
    AFTER_EACH_CALL("after_each_call"),
    AUTOMATIC("automatic");

    /** Whether this mode transcribes a call the moment it finishes. */
    val transcribesOnCallEnd: Boolean get() = this == AFTER_EACH_CALL

    /** Whether this mode needs the periodic sweep scheduled. */
    val needsPeriodicSweep: Boolean get() = this == AUTOMATIC

    companion object {
        fun fromKey(k: String?): TranscriptionMode = entries.firstOrNull { it.key == k } ?: MANUAL
    }
}
