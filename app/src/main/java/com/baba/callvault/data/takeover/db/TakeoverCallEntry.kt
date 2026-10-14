/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.takeover.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single SIP call handled by the voice agent, cached locally so the Agent-calls list is
 * visible offline (Node is on the same LAN and can be down). The row is the Node record
 * as last seen by a poll, keyed by Node's UUID so re-polling upserts rather than duplicates.
 *
 * [answerMode] is the Node field that tells the UI who answered: "agent" (AI took over),
 * "human" (owner picked up), or absent/missed (window closed). [transcript] / [summary] /
 * [capturedMessage] are populated by the engine over the call and arrive on later polls.
 */
@Entity(tableName = "takeover_calls")
data class TakeoverCallEntry(
    @PrimaryKey val callId: String,
    @ColumnInfo(name = "phone_number") val phoneNumber: String?,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "answer_mode") val answerMode: String?,
    @ColumnInfo(name = "source") val source: String?,
    @ColumnInfo(name = "session_type") val sessionType: String?,
    @ColumnInfo(name = "captured_message") val capturedMessage: String?,
    @ColumnInfo(name = "call_summary") val callSummary: String?,
    @ColumnInfo(name = "transcript") val transcript: String?,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long?,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long?,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Double?,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
)
