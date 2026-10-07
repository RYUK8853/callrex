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
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One starred recording, keyed by the same [displayName] natural key the rest of the app uses.
 *
 * Presence is the whole state — a row means starred, no row means not — so there is no boolean column
 * to fall out of step with itself. It lives here beside `recording_tags` rather than in the recordings
 * catalog because it is the user's own judgement about a call, not a fact about the file, and it must
 * survive the catalog being rebuilt from a folder scan.
 *
 * A star is NOT a tag, despite the similar shape. Tags are renameable and deletable by the user, and a
 * star that could be renamed out of existence would silently withdraw the protection below.
 *
 * **This table is load-bearing for data safety.** A starred recording is exempt from every automatic
 * delete — the retention sweep and the storage cap both skip it — so a bug that loses a row here does
 * not merely lose a star, it re-exposes the one recording the user said to keep.
 */
@Entity(tableName = "recording_favourites")
data class RecordingFavouriteEntry(
    @PrimaryKey val displayName: String
)

@Dao
interface RecordingFavouriteDao {

    /** Whether [displayName] is starred, as a stream, for the playback screen's star button. */
    @Query("SELECT EXISTS(SELECT 1 FROM recording_favourites WHERE displayName = :displayName)")
    fun observeIsFavourite(displayName: String): Flow<Boolean>

    /**
     * Every starred name, for the Home filter.
     *
     * The whole set rather than a count, because Home needs to know *which* recordings the filter
     * covers, the same shape as the tag facet beside it.
     */
    @Query("SELECT displayName FROM recording_favourites")
    fun observeAll(): Flow<List<String>>

    /**
     * One-shot read of every starred name, for the sweeps.
     *
     * Not a Flow: the retention sweep and the storage cap each need one consistent snapshot to decide
     * a batch of deletes against, and a stream that updated mid-sweep could let a recording be both
     * protected and deleted within the same run.
     */
    @Query("SELECT displayName FROM recording_favourites")
    suspend fun allFavourites(): List<String>

    @Query("INSERT OR REPLACE INTO recording_favourites (displayName) VALUES (:displayName)")
    suspend fun add(displayName: String)

    /** Whether one recording is starred. Used when a merge carries the star across. */
    @Query("SELECT EXISTS(SELECT 1 FROM recording_favourites WHERE displayName = :displayName)")
    suspend fun isFavourite(displayName: String): Boolean

    @Query("DELETE FROM recording_favourites WHERE displayName = :displayName")
    suspend fun deleteFor(displayName: String)
}
