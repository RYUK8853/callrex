/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.takeover.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data-access for the locally-cached SIP calls handled by the voice agent ([TakeoverCallEntry]).
 * All writes are upserts keyed by Node's UUID, so repeated polls of the same call replace the row
 * rather than stacking duplicates, and a call that fills in transcript/summary on a later poll is
 * simply the same row, enriched.
 */
@Dao
interface TakeoverDao {

    /** All cached agent calls, newest-first (the order the list renders). */
    @Query("SELECT * FROM takeover_calls ORDER BY COALESCE(ended_at_ms, started_at_ms, last_seen_ms) DESC")
    fun observeAll(): Flow<List<TakeoverCallEntry>>

    @Query("SELECT * FROM takeover_calls ORDER BY COALESCE(ended_at_ms, started_at_ms, last_seen_ms) DESC")
    suspend fun getAll(): List<TakeoverCallEntry>

    /** Live count for the hub tile. */
    @Query("SELECT COUNT(*) FROM takeover_calls")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM takeover_calls WHERE callId = :callId LIMIT 1")
    suspend fun findById(callId: String): TakeoverCallEntry?

    /** Insert or replace one row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TakeoverCallEntry)

    /** Insert or replace many rows (a poll's batch). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<TakeoverCallEntry>)

    @Query("DELETE FROM takeover_calls WHERE callId = :callId")
    suspend fun deleteById(callId: String)
}
