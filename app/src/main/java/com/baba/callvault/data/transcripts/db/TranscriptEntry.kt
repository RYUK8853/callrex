/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** Where a recording stands in the transcription pipeline. */
enum class TranscriptState(val key: String) {
    /** Queued for a scheduled or manual run. */
    QUEUED("queued"),

    /** Currently being transcribed. */
    RUNNING("running"),

    /** Finished; segments are stored. */
    DONE("done"),

    /**
     * The last attempt failed. Deliberately NOT retried by the scheduler: a permanently undecodable
     * file would otherwise be re-attempted on every scheduled run forever, and the user's battery
     * would pay for it nightly. Only an explicit tap retries.
     */
    FAILED("failed");

    companion object {
        fun fromKey(key: String?): TranscriptState = entries.firstOrNull { it.key == key } ?: QUEUED
    }
}

/** Room cannot persist enums directly; stored as the stable [TranscriptState.key] string. */
class TranscriptStateConverter {
    @TypeConverter fun toKey(state: TranscriptState): String = state.key
    @TypeConverter fun fromKey(key: String): TranscriptState = TranscriptState.fromKey(key)
}

/**
 * One transcript per recording.
 *
 * Keyed by [com.baba.callvault.data.recordings.db.RecordingEntry.displayName] — the catalog's
 * *natural* key, whose filename template embeds a millisecond timestamp. That matters: the recordings
 * catalog is a rebuildable cache that gets dropped and re-seeded on a schema bump, so a transcript
 * keyed on anything the rebuild regenerates would be orphaned. The name survives.
 *
 * @param displayName  The recording's file name including extension.
 * @param state        Pipeline state; see [TranscriptState].
 * @param modelId      Which model produced this, so a re-transcription with a better model is
 *                     distinguishable from a re-run of the same one.
 * @param language     BCP-47-ish language code passed to whisper, or null when auto-detected.
 * @param updatedAt    Epoch millis of the last state change.
 * @param errorMessage Why the last attempt failed, shown on the retry affordance. Null unless FAILED.
 */
@Entity(tableName = "transcripts")
data class TranscriptEntry(
    @PrimaryKey val displayName: String,
    val state: TranscriptState,
    val modelId: String? = null,
    val language: String? = null,
    val updatedAt: Long = 0L,
    val errorMessage: String? = null
)
