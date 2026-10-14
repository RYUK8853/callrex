/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.takeover

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.takeover.db.TakeoverCallEntry
import com.baba.callvault.data.takeover.db.TakeoverDatabase
import com.baba.callvault.services.takeover.TakeoverApiClient
import com.baba.callvault.services.takeover.TakeoverApiClient.AgentCallRecord
import com.baba.callvault.services.takeover.TakeoverApiClient.TakeoverException
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The bridge between the voice-agent Node API (the source of truth for agent-handled calls) and
 * Callrex's local Room cache. Two jobs:
 *
 *  1. [observeAll] — the Agent-calls list, straight from the local Room (visible offline,
 *     live-updated as polls land).
 *  2. [refresh] — one poll of the Node: fetch the call records (completed + missed + ringing) and
 *     upsert each into the local Room. The sheet's contract: Node is the transcript source for the
 *     agent case; the app only ever *reads* the Node's view of a call here (owner-case records are
 *     pushed by the recording pipeline, not by this repository).
 *
 * **Why a manual refresh and not a timer.** The list is a secondary view of a phone whose primary
 * job is answering calls. Polling it on a timer would double the engine traffic for nothing — the
 * list refreshes when the user opens it and while the live agent view is open (the overlay runs
 * its own transcript poll for the in-call case, which is the one that must be frequent).
 *
 * **Unreachable Node is a non-error here.** The cache still renders what it has; [refresh] logs
 * and returns, because "Node is down" is not a state the list should scream about.
 */
class TakeoverCallRepository(context: Context) {

    private val dao = TakeoverDatabase.get(context).takeoverDao()

    private val prefs: AppPreferences = AppPreferences(context.applicationContext)

    /** The local cache, newest-first, as a stream. The list UI collects this. */
    fun observeAll(): Flow<List<TakeoverCallEntry>> = dao.observeAll()

    /** Live row count for the hub tile. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun getById(callId: String): TakeoverCallEntry? = dao.findById(callId)

    /**
     * One poll of the Node. Fetches the completed, missed and (to catch a call that just ended)
     * ringing records, and upserts each into the Room. All failures are logged and swallowed —
     * the UI keeps the cached rows. A 401 is logged more loudly because it means the token in
     * Settings is wrong and the list will never fill.
     */
    suspend fun refresh() {
        val nodeUrl = prefs.getTakeoverNodeUrl()
        val nodeToken = prefs.getTakeoverNodeToken()
        if (nodeUrl.isNullOrBlank() || nodeToken.isNullOrBlank()) {
            AppLogger.w(TAG, "refresh skipped: node URL/token not configured")
            return
        }
        val now = System.currentTimeMillis()
        for (status in listOf("completed", "missed", "ringing")) {
            val records = try {
                TakeoverApiClient.listAgentCalls(nodeUrl, nodeToken, status)
            } catch (e: TakeoverException) {
                if (e.status == 401) {
                    AppLogger.e(TAG, "node 401 on $status — token wrong?")
                } else {
                    AppLogger.w(TAG, "node error ($e) for $status")
                }
                null
            } catch (e: Exception) {
                AppLogger.w(TAG, "node unreachable for $status: ${e.message}")
                null
            } ?: continue
            records.forEach { rec ->
                runCatching {
                    dao.upsert(toEntry(rec, now))
                }.onFailure { AppLogger.w(TAG, "upsert failed for ${rec.id}: ${it.message}") }
            }
        }
    }

    /**
     * The live transcript for one in-call, as a stream of the whole accumulated text. The agent
     * live-view collects this while the AI is on the line; the overlay diffs against what it last
     * saw and scrolls only the new lines in. Each poll fetches the Node's `/transcript` for the
     * call id and flattens it to a single string.
     */
    fun observeTranscript(callId: String): Flow<String?> = flow {
        val nodeUrl = prefs.getTakeoverNodeUrl()
        val nodeToken = prefs.getTakeoverNodeToken()
        if (nodeUrl.isNullOrBlank() || nodeToken.isNullOrBlank()) {
            emit(null); return@flow
        }
        while (true) {
            val text = runCatching {
                // (nodeUrl, callId, nodeToken) — the call id is the PATH segment, not a query param.
                TakeoverApiClient.getTranscript(nodeUrl, callId, nodeToken)
                    .joinToString("\n") { e ->
                        val spk = e.speaker?.takeIf { it.isNotBlank() }
                        if (spk != null) "$spk: ${e.text}" else e.text
                    }
                    .ifBlank { null }
            }.getOrNull()
            emit(text)
            delay(TRANSCRIPT_POLL_MS)
        }
    }

    private fun toEntry(rec: AgentCallRecord, now: Long): TakeoverCallEntry = TakeoverCallEntry(
        callId = rec.id,
        phoneNumber = rec.phoneNumber,
        status = rec.status ?: "unknown",
        answerMode = rec.answerMode,
        source = rec.source,
        sessionType = rec.sessionType,
        capturedMessage = rec.capturedMessage,
        callSummary = rec.summary,
        transcript = rec.transcript,
        startedAtMs = rec.startedAt,
        endedAtMs = rec.endedAt,
        durationSeconds = rec.durationSeconds,
        lastSeenMs = now,
    )

    companion object {
        private const val TAG = "CV:TakeoverRepo"
        private const val TRANSCRIPT_POLL_MS = 5_000L
    }
}
