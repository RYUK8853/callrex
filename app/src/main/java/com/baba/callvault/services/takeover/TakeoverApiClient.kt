/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import androidx.annotation.VisibleForTesting
import com.baba.callvault.utils.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The voice-agent "dynamic takeover" API client — the owner side of an inbound SIP call that
 * Callrex may answer three ways (the owner joins, the AI agent takes over, or the call is
 * declined).
 *
 * **Two servers, one trust model.** The engine (default port 8000) owns the live call:
 * `/sip/ringing`, `/sip/human-join`, `/sip/agent-takeover`, `/sip/call-drop`. The Node API
 * (default port 3000) owns the records: `/calls`, `/calls/{id}`, `/transcript`,
 * `/database/update-call`. Both hosts are entered by the user in Settings and nothing here
 * speaks to any host the user did not name — same model as [com.baba.callvault.data.cloud.CloudApiClient].
 *
 * **Auth lives in exactly one place: [engineHeaders] / [nodeHeaders].** The local stack ships
 * with no tokens; a prod deploy will set `ZANTACALL_API_TOKEN` (engine) and a server token
 * (Node). Both builders stay one line away from prod — the sheet's "ek hi jagah" rule, kept
 * literal so the prod switch cannot break a scattered second copy.
 *
 * **Timing.** Every call here is short (connect 3 s, read 5 s) because the ringback window is
 * ~45 s and the overlay polls [getRinging] every 2 s; a hung socket is how an overlay misses
 * its window. Longer reads (transcript fetch, upload) carry their own timeouts.
 *
 * **Parsing.** The Node list endpoint wraps its array in `{"calls": [...]}` — verified against
 * the running server, which is also why the parser reads both wrapped and bare shapes: a
 * future server tightening or loosening the wrapper must not blank the "Agent Calls" section.
 */
object TakeoverApiClient {

    private const val TAG = "CV:TakeoverApi"

    /** Short calls: ringing poll, human-join, agent-takeover, call-drop, health. */
    @VisibleForTesting
    internal const val FAST_CONNECT_MS = 3_000
    @VisibleForTesting
    internal const val FAST_READ_MS = 5_000

    /** Record fetches and uploads may be slower; they never run against the ringback window. */
    @VisibleForTesting
    internal const val SLOW_CONNECT_MS = 5_000
    @VisibleForTesting
    internal const val SLOW_READ_MS = 15_000

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    /**
     * One call in the engine's ringing window. [phoneNumber] may be null on the first poll —
     * the engine has seen the room but not the caller identity yet; the next tick fills it.
     * [callDbId] is the Node-DB id the overlay passes to human-join/takeover and the library
     * section uses to look records up.
     */
    data class RingingCall(
        val roomName: String,
        val callDbId: String?,
        val phoneNumber: String?,
        /**
         * Engine-side fact (DYNAMIC-TAKEOVER 10-14): the room is still tracked by the ringing
         * monitor — either still ringing or already answered (agent/owner took over). A room
         * that has ENDED simply leaves the list, so presence in the list is the authoritative
         * "call still live" signal and this flag only tells the app WHICH half of live it is.
         */
        val answered: Boolean = false,
    )

    /**
     * Everything [joinAsOwner] returns — everything the LiveKit join needs.
     */
    data class HumanJoinResult(
        val serverUrl: String,
        val roomName: String,
        val participantToken: String,
    )

    /**
     * One Node-API call record, as stored after it leaves the ringback window.
     * [answerMode] is the doc's new field: "human" | "agent" | null (missed/legacy).
     */
    data class AgentCallRecord(
        val id: String,
        val phoneNumber: String?,
        val status: String?,
        val answerMode: String?,
        val transcript: String?,
        val summary: String?,
        val capturedMessage: String?,
        val startedAt: Long?,
        val endedAt: Long?,
        val source: String?,
        val sessionType: String?,
        val durationSeconds: Double?,
    )

    /**
     * One line of the transcript endpoint (speaker-attributed if the server provides it).
     */
    data class TranscriptEntry(
        val speaker: String?,
        val text: String,
        val timestampMs: Long?,
    )

    // ------------------------------------------------------------------
    // Engine API (port 8000 side)
    // ------------------------------------------------------------------

    /**
     * `GET {engine}/sip/ringing` — the overlay's heartbeat. Returns the currently-ringing calls
     * (at most one in practice: one trunk, one room). An empty list is a healthy answer, not an
     * error; [TakeoverException] is thrown only when the ENGINE ITSELF is unreachable — the
     * monitor treats those as "server down, back off", which is different from "no call".
     */
    @JvmOverloads
    fun getRinging(engineUrl: String, engineToken: String? = null): List<RingingCall> {
        val json = fastGet("$engineUrl/sip/ringing", engineHeaders(engineToken))
        val arr = JSONObject(json).optJSONArray("ringing") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val room = o.optString("room_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RingingCall(
                roomName = room,
                callDbId = o.optString("call_db_id").takeIf { it.isNotBlank() },
                phoneNumber = o.optString("phone_number").takeIf { it.isNotBlank() },
                // 10-14 engine contract: answered=true means the room is tracked but the call
                // is no longer ringing (agent/owner took over). Older engines omit the field → false.
                answered = o.optBoolean("answered"),
            )
        }
    }

    /**
     * `POST {engine}/sip/human-join` — the "Main baat karta hoon" button.
     *
     * Failure semantics, straight from the integration sheet:
     * - 404 `room gone`            -> the window closed; the overlay dismisses.
     * - 409 `already answered`     -> the other button won the race; the overlay switches to
     *   "AI baat kar raha hai" instead of surfacing an error.
     * Both arrive as [TakeoverException] with a [status] the caller can branch on.
     */
    @JvmOverloads
    fun joinAsOwner(engineUrl: String, roomName: String, engineToken: String? = null): HumanJoinResult {
        val body = JSONObject().put("room_name", roomName).toString()
        val json = fastPost("$engineUrl/sip/human-join", body, engineHeaders(engineToken))
        val o = JSONObject(json)
        return HumanJoinResult(
            serverUrl = o.getString("serverUrl"),
            roomName = o.optString("roomName").ifBlank { roomName },
            participantToken = o.getString("participantToken"),
        )
    }

    /**
     * `POST {engine}/sip/agent-takeover` — the "AI agent utha le" button. Same 404/409
     * semantics as [joinAsOwner]; the agent then talks and the transcript lands in the Node DB,
     * which the app reads through [getCallRecord]/[getTranscript] — never from the engine.
     */
    @JvmOverloads
    fun agentTakeover(engineUrl: String, roomName: String, engineToken: String? = null) {
        val body = JSONObject().put("room_name", roomName).toString()
        fastPost("$engineUrl/sip/agent-takeover", body, engineHeaders(engineToken))
    }

    /**
     * `POST {engine}/sip/call-drop` — the Decline button, best-effort. A drop after the room is
     * already gone is a no-op on the server; the call returns nothing and swallows the 404 so
     * the overlay can always finish cleanly.
     */
    @JvmOverloads
    fun dropCall(engineUrl: String, roomName: String, engineToken: String? = null) {
        val body = JSONObject().put("room_name", roomName).toString()
        try {
            fastPost("$engineUrl/sip/call-drop", body, engineHeaders(engineToken))
        } catch (e: TakeoverException) {
            if (e.status != 404) throw e
            AppLogger.i(TAG, "dropCall: room already gone (404) — fine")
        }
    }

    /**
     * `POST {engine}/health` — the Settings "Test connection" button.
     *
     * Returns true only for a live engine; the [message] in a failed [TakeoverException] is
     * safe to show (it is the server's own error text, never a token or a call body).
     */
    @JvmOverloads
    fun checkEngineHealth(engineUrl: String, engineToken: String? = null): Boolean {
        fastPost("$engineUrl/health", JSONObject().toString(), engineHeaders(engineToken))
        return true
    }

    // ------------------------------------------------------------------
    // Node API (port 3000 side) — the "Agent Calls" data source
    // ------------------------------------------------------------------

    /**
     * `GET {node}/calls` — the Agent Calls section, newest first. Optional [status] filter
     * ("ringing" | "in_call" | "completed" | "missed"). Reads both the wrapped
     * `{"calls": [...]}` shape and a bare array.
     */
    @JvmOverloads
    fun listAgentCalls(nodeUrl: String, nodeToken: String? = null, status: String? = null): List<AgentCallRecord> {
        val path = if (status.isNullOrBlank()) "/calls" else "/calls?status=$status"
        val json = slowGet(nodeUrl + path, nodeHeaders(nodeToken))
        val root = JSONObject(json)
        // Verified against the live server: the list is wrapped in {"calls": [...]}.
        // Bare-array tolerance stays because the wrapper is a server choice, not a contract we own.
        val arr = root.optJSONArray("calls") ?: root.takeIf { it.length() == 0 }.let {
            if (root.has("calls")) null else rootOptAsArray(json)
        } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            parseCallRecord(arr.optJSONObject(i))
        }
    }

    private fun rootOptAsArray(json: String): org.json.JSONArray? =
        runCatching { org.json.JSONArray(json) }.getOrNull()

    /**
     * `GET {node}/calls/{id}` — one record. Transcript/summary live on the record itself
     * (verified against the live server: the detail response carries `transcript` and
     * `call_summary` fields), so the detail screen usually needs no second fetch.
     */
    @JvmOverloads
    fun getCallRecord(nodeUrl: String, callId: String, nodeToken: String? = null): AgentCallRecord? {
        val json = slowGet("${nodeUrl}/calls/${URLEncoder.encode(callId, "UTF-8")}", nodeHeaders(nodeToken))
        return parseCallRecord(JSONObject(json))
    }

    /**
     * `GET {node}/calls/{id}/transcript` — the line-by-line transcript, for the detail view's
     * expandable conversation. Returns an empty list when the call has no transcript yet (the
     * agent case before the first exchange is exactly that shape).
     */
    @JvmOverloads
    fun getTranscript(nodeUrl: String, callId: String, nodeToken: String? = null): List<TranscriptEntry> {
        // Route verified against the live Node (routes/calls.js, mounted at /calls):
        // GET /calls/{call_id}/transcript → {status:"success", transcript:{items:[{role,content:[...]}]}}.
        // The earlier guess (`/transcript?call_id=...`) was a 404 — there is no root /transcript route.
        val path = "/calls/${URLEncoder.encode(callId, "UTF-8")}/transcript"
        val json = slowGet(nodeUrl + path, nodeHeaders(nodeToken))
        return parseTranscript(json)
    }

    /**
     * The transcript-endpoint parser, split out for unit tests. Tolerates every shape the stack
     * has ever produced: a bare `{items:[...]}`, the engine's `{transcript:{items:[...]}}`
     * wrapper, an `{entries:[...]}` fallback, or a top-level array.
     */
    @VisibleForTesting
    internal fun parseTranscript(json: String): List<TranscriptEntry> {
        val trimmed = json.trim()
        // A bare top-level array is an allowed shape too — JSONObject(trimmed) would throw on it,
        // so take it before any object parsing.
        if (trimmed.startsWith("[")) {
            // Malformed JSON must yield an empty list, not a crash mid-render.
            return runCatching { parseItems(JSONArray(trimmed)) }.getOrElse { emptyList() }
        }
        val root = JSONObject(trimmed)
        // The engine stores `transcript` as session history: an OBJECT `{items:[{role, content:[str]}]}`,
        // not an array. Tolerance for a bare array is kept for anything else that may one day write one.
        val items = root.optJSONArray("items")
            ?: root.optJSONObject("transcript")?.optJSONArray("items")
            ?: root.optJSONArray("entries")
            ?: return emptyList()
        return parseItems(items)
    }

    /** The per-item reader shared by every accepted top-level shape. */
    private fun parseItems(items: JSONArray): List<TranscriptEntry> =
        (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            // Engine items look like {"role":"user","content":["Yes please..."]} — content is a LIST of
            // strings (one per utterance in the item). Plain-string content is tolerated too.
            // The opt()/as?-String reads matter twice over: org.json's no-arg optString("missing")
            // returns the literal string "null", and optString on an EMPTY array returns "[]" —
            // both would leak into the transcript text. Only a real JSON string is accepted.
            val text = o.optJSONArray("content")?.let { arr ->
                (0 until arr.length()).joinToString("\n") { j -> arr.optString(j) }
                    .trim().takeIf { it.isNotEmpty() }
            } ?: (o.opt("text") as? String)?.takeIf { it.isNotBlank() }
                ?: (o.opt("content") as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            TranscriptEntry(
                speaker = (o.opt("speaker") as? String)?.takeIf { it.isNotBlank() }
                    ?: (o.opt("role") as? String)?.takeIf { it.isNotBlank() },
                text = text,
                timestampMs = o.optLong("timestamp_ms", -1L).takeIf { it >= 0 },
            )
        }

    /**
     * `POST {node}/database/update-call` — the owner-pickup write-back. After the owner's call
     * ends and Callrex has transcribed + summarised the WebRTC recording, the transcript and
     * summary land in the Node DB through this endpoint so the Agent Calls section (and anything
     * else that reads it) sees owner calls the same way it sees agent calls.
     *
     * **Body shape (verified against the live Node, `routes/database.js`):**
     * `{"call_id": "...", "update_data": {...}}` — the `update_data` object is `$set` directly onto
     * the call document. The python engine writes the same fields: `transcript`, `status`,
     * `end_time`, `session_data`. Our write-back reuses exactly that shape so a record owned by
     * the owner and a record owned by the agent are indistinguishable to readers.
     *
     * [fields] is the `update_data` payload (`status`, `transcript`, `summary`, ...).
     * Best-effort by design: a failed write-back must never lose the local recording, which
     * stays in the Room either way.
     */
    @JvmOverloads
    fun updateCall(nodeUrl: String, callId: String, fields: JSONObject, nodeToken: String? = null): Boolean {
        val body = buildUpdateBody(callId, fields)
        val json = slowPost("${nodeUrl}/database/update-call", body, nodeHeaders(nodeToken))
        // Server answers {"success": true|false, ...}; a 200 with success:false (e.g. a validation
        // 400 surface) is a failed write-back and must be reported as such.
        return runCatching { JSONObject(json).optBoolean("success", false) }.getOrDefault(false)
    }

    /**
     * The exact wire body for [updateCall]: `{"call_id": "...", "update_data": {...}}`. Pulled out
     * so the one contract that, if wrong, silently `$set`s the wrong document, is unit-tested.
     */
    @VisibleForTesting
    internal fun buildUpdateBody(callId: String, updateData: JSONObject): String =
        JSONObject()
            .put("call_id", callId)
            .put("update_data", updateData)
            .toString()

    /**
     * `POST {node}/health` — the Settings "Test connection" button for the Node side.
     */
    @JvmOverloads
    fun checkNodeHealth(nodeUrl: String, nodeToken: String? = null): Boolean {
        slowPost("${nodeUrl}/health", JSONObject().toString(), nodeHeaders(nodeToken))
        return true
    }

    /**
     * `POST {node}/debug/logs` — the log beacon. The phone ships its own redacted log tail
     * (plus an optional persisted crash report) to the local Node. Returns the server's
     * `{accepted: true}` on success; any HTTP failure throws [TakeoverApiException], which the
     * caller (LogBeacon) catches and treats as "not sent, will retry next trigger".
     *
     * This is a fire-and-forget diagnostics channel: callers must always wrap in runCatching
     * and must never block a real code path on it.
     */
    fun postDebugLog(nodeUrl: String, nodeToken: String?, body: JSONObject): Boolean {
        val json = slowPost("${nodeUrl}/debug/logs", body.toString(), nodeHeaders(nodeToken))
        return runCatching { JSONObject(json).optBoolean("accepted", false) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @VisibleForTesting
    internal fun parseCallRecord(o: JSONObject?): AgentCallRecord? {
        if (o == null) return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val num = o.optString("phone_number").takeIf { it.isNotBlank() }
            ?: o.optString("phone").takeIf { it.isNotBlank() }
        // Timestamps: the server has sent epoch-seconds and ISO strings across versions —
        // normalise to epoch millis and keep null honest when neither parses.
        val started = parseEpochMillis(o.opt("started_at") ?: o.opt("created_at"))
        val ended = parseEpochMillis(o.opt("ended_at"))
        return AgentCallRecord(
            id = id,
            phoneNumber = num,
            status = o.optString("status").takeIf { it.isNotBlank() },
            answerMode = o.optString("answer_mode").takeIf { it.isNotBlank() },
            transcript = o.optString("transcript").takeIf { it.isNotBlank() }
                ?: o.optString("full_transcript").takeIf { it.isNotBlank() },
            summary = o.optString("call_summary").takeIf { it.isNotBlank() }
                ?: o.optString("summary").takeIf { it.isNotBlank() },
            capturedMessage = o.optString("captured_message").takeIf { it.isNotBlank() },
            startedAt = started,
            endedAt = ended,
            source = o.optString("source").takeIf { it.isNotBlank() },
            sessionType = o.optString("session_type").takeIf { it.isNotBlank() },
            durationSeconds = o.optDouble("duration_seconds", -1.0).takeIf { it >= 0.0 },
        )
    }

    /**
     * Epoch-millis normaliser for the Node API's mixed timestamp conventions: a number is taken
     * as seconds when it looks like seconds (< 10^12) and millis otherwise; an ISO-8601 string
     * is parsed; anything else is null rather than a guessed date.
     */
    @VisibleForTesting
    internal fun parseEpochMillis(raw: Any?): Long? {
        raw ?: return null
        return when (raw) {
            is Number -> {
                val v = raw.toLong()
                if (v <= 0) null else if (v < 1_000_000_000_000L) v * 1000 else v
            }
            is String -> {
                val s = raw.trim()
                if (s.isEmpty()) return null
                s.toLongOrNull()?.let { v ->
                    if (v <= 0) null else if (v < 1_000_000_000_000L) v * 1000 else v
                } ?: runCatching {
                    java.time.Instant.parse(s).toEpochMilli()
                }.getOrNull()
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Auth — the ONE place (sheet section 3: "ek hi jagah handle karo")
    // ------------------------------------------------------------------

    /**
     * Engine request headers. Blank/null token = local stack = no Authorization header at all,
     * which is what the engine accepts today; a set token becomes `Authorization: Bearer ...`.
     */
    @VisibleForTesting
    internal fun engineHeaders(token: String?): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        token?.takeIf { it.isNotBlank() }?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    /**
     * Node request headers. Same one-place rule, the Node's own header name: `X-Server-Token`.
     */
    @VisibleForTesting
    internal fun nodeHeaders(token: String?): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        token?.takeIf { it.isNotBlank() }?.let { h["X-Server-Token"] = it }
        return h
    }

    // ------------------------------------------------------------------
    // HTTP core
    // ------------------------------------------------------------------

    /**
     * A completed request that was not a 2xx. [status] is 0 for unreachable/timeout/network —
     * the UI and the poller branch on "server down" (back off, stay alive) vs "server answered"
     * (trust the body, even an error).
     */
    class TakeoverException(val status: Int, message: String) : Exception(message)

    @VisibleForTesting
    internal fun fastGet(url: String, headers: Map<String, String>): String =
        request(url, headers, method = "GET", connectMs = FAST_CONNECT_MS, readMs = FAST_READ_MS)

    @VisibleForTesting
    internal fun fastPost(url: String, body: String, headers: Map<String, String>): String =
        request(url, headers, method = "POST", body = body, connectMs = FAST_CONNECT_MS, readMs = FAST_READ_MS)

    @VisibleForTesting
    internal fun slowGet(url: String, headers: Map<String, String>): String =
        request(url, headers, method = "GET", connectMs = SLOW_CONNECT_MS, readMs = SLOW_READ_MS)

    @VisibleForTesting
    internal fun slowPost(url: String, body: String, headers: Map<String, String>): String =
        request(url, headers, method = "POST", body = body, connectMs = SLOW_CONNECT_MS, readMs = SLOW_READ_MS)

    private fun request(
        url: String,
        headers: Map<String, String>,
        method: String,
        body: String? = null,
        connectMs: Int,
        readMs: Int,
    ): String {
        val conn: HttpURLConnection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectMs
                readTimeout = readMs
                doInput = true
                if (body != null) {
                    doOutput = true
                }
            }
        } catch (e: IOException) {
            throw TakeoverException(0, "Unreachable: $url")
        }
        try {
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            body?.let {
                conn.outputStream.use { os -> os.write(it.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val bodyText = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { readAll(it) } ?: ""
            if (status !in 200..299) {
                // The error body is the server's own one-line message ("room gone", "already
                // answered") — safe to carry; never a token, never a call body.
                throw TakeoverException(status, bodyText.ifBlank { "HTTP $status" })
            }
            return bodyText
        } catch (e: IOException) {
            throw TakeoverException(0, "Unreachable: $url")
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n = input.read(buf)
        while (n >= 0) {
            out.write(buf, 0, n)
            n = input.read(buf)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
