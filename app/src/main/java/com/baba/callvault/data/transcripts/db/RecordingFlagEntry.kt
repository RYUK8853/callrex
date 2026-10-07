/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A moment the user marked during a call, as an offset into the saved audio.
 *
 * [atMs] is a position in the **file**, not a wall-clock time: it comes from [
 * com.baba.callvault.services.recording.RecordingClock], which excludes paused time. That is what
 * lets the playback screen seek straight to it.
 *
 * Composite key on (name, offset) so pressing the button twice at the same instant cannot produce
 * two rows for one moment. Written only when a call finishes, against the recording's final name.
 */
@Entity(
    tableName = "recording_flags",
    primaryKeys = ["displayName", "atMs"],
    indices = [Index("displayName")]
)
data class RecordingFlagEntry(
    val displayName: String,
    val atMs: Long
)

@Dao
interface RecordingFlagDao {

    /** The marks on one recording, earliest first, for the playback screen. */
    @Query("SELECT atMs FROM recording_flags WHERE displayName = :displayName ORDER BY atMs")
    fun observeFor(displayName: String): Flow<List<Long>>

    @Upsert
    suspend fun upsertAll(entries: List<RecordingFlagEntry>)

    /** The marks on one recording, in order. Used when a merge moves them onto a new timeline. */
    @Query("SELECT atMs FROM recording_flags WHERE displayName = :displayName ORDER BY atMs")
    suspend fun flagsFor(displayName: String): List<Long>

    @Query("DELETE FROM recording_flags WHERE displayName = :displayName")
    suspend fun deleteFor(displayName: String)

    @Query("DELETE FROM recording_flags WHERE displayName = :displayName AND atMs = :atMs")
    suspend fun deleteOne(displayName: String, atMs: Long)
}
