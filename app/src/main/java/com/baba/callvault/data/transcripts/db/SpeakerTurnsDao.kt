/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Who was speaking when, per recording. */
@Dao
interface SpeakerTurnsDao {

    @Query("SELECT * FROM speaker_turns WHERE displayName = :displayName")
    suspend fun turnsFor(displayName: String): SpeakerTurnsEntry?

    @Upsert
    suspend fun upsert(entry: SpeakerTurnsEntry)

    @Query("DELETE FROM speaker_turns WHERE displayName = :displayName")
    suspend fun deleteFor(displayName: String)

    /**
     * The most recent outgoing calls that observed a mapping, newest first.
     *
     * Feeds the corroboration rule: a mapping is trusted only once two calls agree. Limited because
     * the answer is a property of the device, not of the call — once two agree, older calls cannot
     * change it, and a device whose first observations disagree needs the *newest* ones anyway.
     */
    @Query(
        "SELECT * FROM speaker_turns WHERE outgoing = 1 AND observedMap != 'unknown' " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun recentObservations(limit: Int): List<SpeakerTurnsEntry>

    /**
     * The most recent outgoing calls that have turns, newest first — including those whose stored
     * observation says nothing.
     *
     * The mapping is judged from the turns rather than from what was concluded when each call ended.
     * That way a rule improved today applies to every recording already on the phone, instead of
     * only to calls made after it shipped: the evidence was always there, only the reading of it
     * changed. It is also what lets an answer be *lost* — deleting the calls that taught it
     * un-teaches it, which a stored conclusion could not do.
     */
    @Query(
        "SELECT * FROM speaker_turns WHERE outgoing = 1 AND turns != '' " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun recentOutgoing(limit: Int): List<SpeakerTurnsEntry>
}
