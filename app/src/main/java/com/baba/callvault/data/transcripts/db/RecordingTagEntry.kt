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
 * One label the user has put on one recording.
 *
 * **In the transcripts database, not the recordings catalog**, for the same reason notes are: the
 * catalog has a destructive fallback because it can be rebuilt from the storage folders, and a tag
 * cannot be rebuilt from anything. It is a judgement the user made — *this call was about the flat* —
 * and nothing on disk records it.
 *
 * **Keyed by `displayName` and the tag together**, so applying a tag twice is a no-op rather than a
 * duplicate row, and so removing one is a delete rather than a search. The index on `tag` is what
 * makes filtering the list by a tag cheap once someone has years of calls.
 *
 * The best evidence for this feature is a request that also explains it: contacts are not enough,
 * because a great many calls are with numbers that are not in anybody's address book.
 */
@Entity(
    tableName = "recording_tags",
    primaryKeys = ["displayName", "tag"],
    indices = [Index("tag")]
)
data class RecordingTagEntry(
    val displayName: String,
    val tag: String
)

@Dao
interface RecordingTagDao {

    /** The tags on one recording, alphabetically, so a row's chips do not reorder as they are added. */
    @Query("SELECT tag FROM recording_tags WHERE displayName = :displayName ORDER BY tag COLLATE NOCASE")
    fun observeFor(displayName: String): Flow<List<String>>

    /**
     * Every tag in use, with how many recordings carry it.
     *
     * Counted rather than merely listed because the filter row shows them, and a tag applied to one
     * call three years ago should not sit at the front of a row the user reads every day.
     */
    @Query(
        """
        SELECT tag AS tag, COUNT(*) AS count
        FROM recording_tags
        GROUP BY tag
        ORDER BY count DESC, tag COLLATE NOCASE
        """
    )
    fun observeAll(): Flow<List<TagCount>>

    /** The recordings carrying [tag]. */
    @Query("SELECT displayName FROM recording_tags WHERE tag = :tag")
    fun observeTagged(tag: String): Flow<List<String>>

    /**
     * Every assignment, for the Home filter.
     *
     * One flow rather than a query per tag: Home needs both the list of tags to offer *and* which
     * recordings each covers, and deriving both from one map keeps the filtering a pure function of
     * state — the same shape as the contact and date facets beside it.
     */
    @Query("SELECT * FROM recording_tags")
    fun observeAllAssignments(): Flow<List<RecordingTagEntry>>

    /** One-shot read of every distinct tag, for canonicalising a newly typed one. */
    @Query("SELECT DISTINCT tag FROM recording_tags")
    suspend fun allTags(): List<String>

    @Upsert
    suspend fun add(entry: RecordingTagEntry)

    @Query("DELETE FROM recording_tags WHERE displayName = :displayName AND tag = :tag")
    suspend fun remove(displayName: String, tag: String)

    /**
     * Renames [from] to [to] on every recording that carries it.
     *
     * **`UPDATE OR REPLACE`, not a plain UPDATE.** Renaming *work* to *admin* on a call that already
     * carries *admin* would collide with the composite primary key and abort the whole statement —
     * leaving the rename half-applied across the library, which is worse than not offering it. The
     * conflict resolution merges those rows instead, which is exactly what merging two tags means.
     */
    @Query("UPDATE OR REPLACE recording_tags SET tag = :to WHERE tag = :from")
    suspend fun renameEverywhere(from: String, to: String)

    /** Removes [tag] from every recording. */
    @Query("DELETE FROM recording_tags WHERE tag = :tag")
    suspend fun deleteEverywhere(tag: String)

    /** Part of the delete cascade: a tag outliving its recording is a record of a deleted call. */
    /** One recording's tags. Used when a merge unions them onto the merged recording. */
    @Query("SELECT tag FROM recording_tags WHERE displayName = :displayName ORDER BY tag COLLATE NOCASE")
    suspend fun tagsFor(displayName: String): List<String>

    @Query("DELETE FROM recording_tags WHERE displayName = :displayName")
    suspend fun deleteFor(displayName: String)
}

/** A tag and how many recordings carry it. */
data class TagCount(val tag: String, val count: Int)
