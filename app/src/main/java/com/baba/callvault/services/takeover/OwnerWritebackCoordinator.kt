/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.server.speakers.SpeakerChannel
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.summary.SummaryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The owner-pickup write-back, end to end.
 *
 * When the owner takes the call themselves, Callrex records it exactly like any other VoIP
 * call and the existing transcription pipeline runs on it. This coordinator is the ONLY new
 * piece: when that local call ends, it finds the recording the detector made, waits for the
 * transcript (and summary) to finish, and pushes both into the Node DB through
 * [TakeoverApiClient.updateCall] — so the Agent Calls section shows owner calls the same way
 * it shows agent calls, and the record in Node is indistinguishable from one the engine wrote.
 *
 * Trigger: [TakeoverState.ownerWriteback] is set by the overlay at join time, and the room
 * leaving `/sip/ringing` (owner answered) makes the monitor clear the current call. This
 * fires on the first moment both are true, which is always after the answer — and is
 * idempotent, so the poll cadence cannot double-fire it.
 *
 * Deliberately best-effort: a failed write-back never deletes or marks the local recording.
 * The Room keeps the call forever; only the Node-side copy is at stake, and that copy already
 * exists (as `in_progress`/`missed`) before this ever runs.
 */
object OwnerWritebackCoordinator {

    private const val TAG = "OwnerWriteback"

    /** How long after the join the recording file may appear (detector lag + daemon start). */
    private const val RECORDING_MATCH_WINDOW_MS = 5L * 60L * 1000L

    /** Total patience for transcript + summary before we write back with whatever exists. */
    private const val COMPLETION_TIMEOUT_MS = 10L * 60L * 1000L

    /** Give the scheduler's own auto-summary a grace period before forcing one. */
    private const val SUMMARY_GRACE_MS = 90L * 1000L

    private var scope: CoroutineScope? = null
    private val handling = AtomicBoolean(false)

    /** Called once from [com.baba.callvault.CallVaultApplication]. */
    fun attach(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            // Collect on changes to EITHER signal: the overlay sets ownerWriteback at join
            // (current still set → guarded out), the monitor clears current when the room
            // leaves /sip/ringing (ownerWriteback unchanged → only combine re-fires here).
            kotlinx.coroutines.flow.combine(
                TakeoverState.ownerWriteback,
                TakeoverState.current,
            ) { w, c -> w to c }.collect { (w, current) ->
                // w != null means the owner is (or was) in the room; current == null means the
                // room left /sip/ringing, i.e. the call is over. First such moment wins.
                if (w != null && current == null && handling.compareAndSet(false, true)) {
                    launch { writeBack(app, w) }
                }
            }
        }
    }

    private suspend fun writeBack(app: Context, w: TakeoverState.OwnerWriteback) {
        try {
            // 1. Find the recording the VoIP detector made for this call. The trigger fired at
            //    answer time; the catalog row only exists at call end — so poll for it.
            val entry = waitForRecording(app, w)
            if (entry == null) {
                AppLogger.w(TAG, "no local recording found for call ${w.callDbId} — write-back skipped (Room is untouched)")
                return
            }

            // 2. Wait for the transcription pipeline: transcript DONE, then summary (forced if
            //    the auto pass is late). Runs on the worker, off any UI clock.
            val transcript = waitTranscript(app, entry.displayName)
            val summaryEntry = waitSummary(app, entry.displayName)

            // 3. Build the Node-side payload in the exact shape the python engine writes
            //    (verified: session.history.to_dict() → {"items":[{"type":"message",
            //    "role":"user|assistant","content":[str,...]}]}).
            val items = transcriptItems(app, entry.displayName, transcript)
            val updateData = JSONObject()
            if (items != null && items.length() > 0) {
                updateData.put("transcript", JSONObject().put("items", items))
            }
            summaryEntry?.let { e ->
                val doc = runCatching { CallSummary.parse(e.document) }.getOrNull()
                if (doc != null) {
                    updateData.put("summary", doc.summary)
                    updateData.put("structured_summary", JSONObject(e.document))
                }
            }
            updateData.put("status", "completed")
            updateData.put(
                "end_time",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(Date()),
            )
            val duration = entry.durationSeconds
                ?: (transcript?.let { t -> t.segments.maxOfOrNull { it.endMs } }?.let { it / 1000L })
            if (duration != null && duration > 0) updateData.put("duration_seconds", duration)
            updateData.put(
                "session_data",
                JSONObject()
                    .put("call_id", w.callDbId)
                    .put("handled_by", "owner")
                    .put("app", "callrex"),
            )

            // 4. Push it. Retries do not make sense here — the record already exists and the
            //    endpoint is idempotent ($set); one honest attempt plus logging is the design.
            val ok = if (w.nodeUrl.isBlank()) {
                AppLogger.w(TAG, "node URL not configured — transcript stays local only (${w.callDbId})")
                false
            } else {
                runCatching {
                    TakeoverApiClient.updateCall(w.nodeUrl, w.callDbId, updateData, w.nodeToken)
                }.getOrElse {
                    AppLogger.e(TAG, "write-back threw for ${w.callDbId}: ${it.message}")
                    false
                }
            }
            AppLogger.i(
                TAG,
                "write-back for ${w.callDbId}: ${if (ok) "LANDED in Node" else "failed — local Room copy is the record of truth"}",
            )
            // The call's log is most valuable right after it happened: ship it whether the
            // write-back landed or not — a failed write-back is exactly the moment to have logs.
            LogBeacon.sendAfterCall(w.callDbId, ok)
        } finally {
            handling.set(false)
            TakeoverState.setOwnerWriteback(null)
        }
    }

    /** How long the call itself may run before we give up waiting for it to end. */
    private const val MAX_CALL_WAIT_MS = 2L * 60L * 60L * 1000L

    /** Poll interval while waiting for the call to end and the recording to be catalogued. */
    private const val RECORDING_POLL_MS = 10L * 1000L

    /**
     * The trigger fires at ANSWER time (the room leaves `/sip/ringing` the moment the owner
     * answers), but the recording is only catalogued at call END — the stop path writes the
     * catalog row. So the file is not there yet; poll for it until the call actually ends.
     *
     * `_voip` is required: the WebRTC join puts the audio mode into MODE_IN_COMMUNICATION,
     * which is exactly what the detector records, but a carrier call running in the same
     * window must never be mistaken for the WebRTC one.
     */
    private suspend fun waitForRecording(app: Context, w: TakeoverState.OwnerWriteback): RecordingEntry? {
        var found: RecordingEntry? = null
        withTimeoutOrNull(MAX_CALL_WAIT_MS) {
            while (found == null) {
                found = findRecording(app, w)
                if (found == null) kotlinx.coroutines.delay(RECORDING_POLL_MS)
            }
        }
        return found
    }

    /**
     * The recording file the detector made for this call. [RecordingEntry.displayName] is the
     * file name `yyyyMMdd_HHmmss.SSSZ_voip…` — the timestamp prefix is the start time, so the
     * match is a time-window lookup, closest one wins.
     */
    private suspend fun findRecording(app: Context, w: TakeoverState.OwnerWriteback): RecordingEntry? {
        return try {
            val all = RecordingDatabase.get(app).recordingDao().getAll()
            matchRecording(all, w.recordingStartedMs)
        } catch (e: Exception) {
            AppLogger.e(TAG, "recording lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Pure matching: from every catalogued VoIP recording, the one whose file-name timestamp is
     * closest to [recordingStartedMs] within [RECORDING_MATCH_WINDOW_MS]. `_voip` is required —
     * a carrier call in the same window must never be mistaken for the WebRTC one. Exposed for
     * unit tests (the DB and the clock are the only things left untested here).
     */
    @VisibleForTesting
    internal fun matchRecording(
        entries: List<RecordingEntry>,
        recordingStartedMs: Long,
    ): RecordingEntry? {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.US)
        return entries
            .filter { it.displayName.contains("_voip") }
            .mapNotNull { e ->
                val ts = runCatching { fmt.parse(e.displayName.take(24))?.time }
                    .getOrNull() ?: return@mapNotNull null
                if (kotlin.math.abs(ts - recordingStartedMs) > RECORDING_MATCH_WINDOW_MS) null else e to ts
            }
            .minByOrNull { it.second }?.first
    }

    private suspend fun waitTranscript(
        app: Context,
        displayName: String,
    ): com.baba.callvault.data.transcripts.db.TranscriptWithSegments? =
        withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
            TranscriptDatabase.get(app).transcriptDao()
                .observe(displayName)
                .firstOrNull { it != null && it.transcript.state in setOf(TranscriptState.DONE, TranscriptState.FAILED) }
        }

    private suspend fun waitSummary(app: Context, displayName: String): CallSummaryEntry? {
        val dao = TranscriptDatabase.get(app).summaryDao()
        // The transcription worker triggers the summary itself; this is the safety net for a
        // scheduler that got starved — one forced pass, then wait to the cap. Fires on the
        // app scope (SupervisorJob) so it does not join this call's cancellation.
        scope?.launch {
            kotlinx.coroutines.delay(SUMMARY_GRACE_MS)
            runCatching { SummaryScheduler.runNow(app, displayName) }
                .onFailure { AppLogger.w(TAG, "forced summary run failed: ${it.message}") }
        }
        return withTimeoutOrNull(COMPLETION_TIMEOUT_MS) { dao.observe(displayName).firstOrNull() }
    }

    /**
     * Local segments → engine-shaped items. Role mapping uses the app's own trusted speaker map
     * ([SpeakerTurnsRepository.trustedMap]) — the same mapping the in-app transcript display
     * uses — so a user who fixed "which one is you" once gets it right here too. While the map
     * is still unknown the conventional A=user, B=caller stands, and a wrong guess is far less
     * costly than a record with no readable roles at all.
     */
    private suspend fun transcriptItems(
        app: Context,
        displayName: String,
        t: com.baba.callvault.data.transcripts.db.TranscriptWithSegments?,
    ): JSONArray? {
        val segs = t?.segments ?: return null
        val map = runCatching { SpeakerTurnsRepository.trustedMap(app) }.getOrDefault(ChannelMap.UNKNOWN)
        return buildItems(segs, map)
    }

    /**
     * Local segments → engine-shaped items, as a pure function for unit tests. Skips BOTH /
     * SILENCE / unlabeled turns; every other segment becomes `{"type":"message","role":...,
     * "content":[text],"created_at":secs}` in segment order.
     */
    @VisibleForTesting
    internal fun buildItems(
        segs: List<com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry>,
        map: ChannelMap,
    ): JSONArray {
        val out = JSONArray()
        for (s in segs) {
            val ch = SpeakerChannel.fromKey(s.speaker ?: "")
            // Unlabeled turns have no channel; BOTH is double-talk (no honest owner for the
            // text) and SILENCE has no text — all three must be skipped, not role-mapped.
            if (ch == null || ch == SpeakerChannel.BOTH || ch == SpeakerChannel.SILENCE) continue
            out.put(
                JSONObject()
                    .put("type", "message")
                    .put("role", roleForChannel(map, ch))
                    .put("content", JSONArray().put(s.text))
                    .put("created_at", s.startMs / 1000.0),
            )
        }
        return out
    }

    /**
     * Channel → Node role for one labelled segment. "Far" is the person on the other end —
     * for these SIP-inbound calls the CALLER, which the engine/Node record as "user" (they
     * talk to "assistant"). The owner (the app's user) is "assistant" on the record.
     */
    internal fun roleForChannel(map: ChannelMap, ch: SpeakerChannel): String =
        when (map) {
            // A is the far party (the caller) → A="user", B=owner="assistant".
            ChannelMap.A_IS_FAR -> if (ch == SpeakerChannel.A) "user" else "assistant"
            // B is the far party (the caller) → B="user", A=owner="assistant".
            ChannelMap.B_IS_FAR -> if (ch == SpeakerChannel.A) "assistant" else "user"
            // Not learned yet: convention A=caller="user", B=owner="assistant".
            ChannelMap.UNKNOWN -> if (ch == SpeakerChannel.A) "user" else "assistant"
        }
}
