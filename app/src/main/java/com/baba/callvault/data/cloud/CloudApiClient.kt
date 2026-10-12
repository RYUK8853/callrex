/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.cloud

import androidx.annotation.VisibleForTesting
import com.baba.callvault.utils.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The user's own OpenAI-compatible API client: audio transcription and chat completions, over
 * [HttpURLConnection] — the app deliberately has no HTTP library dependency.
 *
 * **Trust model.** The endpoint, the model name and the key are the user's, entered by the user
 * and stored on-device only. Nothing here speaks to any host the user did not name. The audio or
 * transcript text the user routed to the endpoint is the only payload; no identifier, device name
 * or setting accompanies it, and nothing is logged but the request's outcome (status and error
 * body) — never the body itself, which is the substance of a private call.
 */
object CloudApiClient {

    private const val TAG = "CV:CloudApi"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 10 * 60_000
    // Fish Audio's own ceiling for one transcription (docs: 900 s) — a 60-minute recording can
    // reach it, and a call that hits this is a failure, not a hang.
    private const val FISH_READ_TIMEOUT_MS = 15 * 60_000
    /** A transcription can outlive a minute of audio; a summary can outlive an hour. */
    private const val WRITE_TIMEOUT_MS = 10 * 60_000

    class CloudApiException(val status: Int, message: String) : Exception(message)

    /**
     * One recognised line of audio, timed against the start of the upload.
     */
    data class TimedSegment(val startMs: Long, val endMs: Long, val text: String)

    /**
     * Transcribes [wav] to the endpoint and returns timed segments.
     *
     * [fish.audio](https://docs.fish.audio/features/speech-to-text) is auto-detected by its host
     * (`api.fish.audio`): it is not OpenAI-shaped — the audio field is `audio`, the model is an
     * HTTP header (a body `model` field is ignored and silently downgraded to a different,
     * cheaper model), and the timestamp switch is `ignore_timestamps=false`. Everything else
     * speaks the OpenAI-compatible `/audio/transcriptions`.
     *
     * The response is read in the shape the server actually sent it: OpenAI's `segments`, Groq's
     * `chunks`, or a plain `text` field with no timing at all — the last is returned as a single
     * segment spanning the whole upload, which is honest about what timing the endpoint produced
     * rather than inventing some.
     */
    fun transcribe(
        config: com.baba.callvault.data.CloudTranscriptionConfig,
        wav: ByteArray,
        language: String?,
    ): List<TimedSegment> {
        return if (isFish(config.baseUrl)) {
            fishTranscribe(config, wav, language)
        } else {
            openAiTranscribe(config, wav, language)
        }
    }

    private fun openAiTranscribe(
        config: com.baba.callvault.data.CloudTranscriptionConfig,
        wav: ByteArray,
        language: String?,
    ): List<TimedSegment> {
        val url = "${config.baseUrl}/audio/transcriptions"
        val boundary = "----CallrexBoundary" + System.nanoTime().toString(16)
        val body = wavBody(boundary, config, wav, language)

        val response = post(
            url = url,
            headers = if (config.apiKey.isNotBlank())
                mapOf("Authorization" to "Bearer ${config.apiKey}") else emptyMap(),
            contentType = "multipart/form-data; boundary=$boundary",
            body = body,
        )

        val root = JSONObject(response)
        return parseTranscription(root)
    }

    /**
     * Whether [baseUrl] is Fish Audio's API. Host match, not a keyword in the URL, because a
     * user proxy in front of it would keep the host of itself — and a proxy that speaks the
     * OpenAI shape at a fish-named address is fine on the OpenAI path.
     */
    @VisibleForTesting
    internal fun isFish(baseUrl: String): Boolean =
        runCatching { URL(baseUrl).host }.getOrNull()?.equals("api.fish.audio", ignoreCase = true) == true

    /**
     * Fish Audio speech-to-text, `POST {base}/asr`.
     *
     * The model must ride the `model` HTTP header, written exactly in lowercase — the docs are
     * explicit that a header miss does not error, it silently serves and bills `transcribe-1`
     * instead, so the value comes from [com.baba.callvault.data.CloudTranscriptionConfig.model]
     * verbatim and the UI hint names the exact string. Timestamps are always requested
     * ([segment]s, word-level, seconds): this app's transcript view is a timed one, and the
     * [TranscriptSegment] shape downstream already carries start/end per line.
     */
    private fun fishTranscribe(
        config: com.baba.callvault.data.CloudTranscriptionConfig,
        wav: ByteArray,
        // No `language` field: the /asr request takes no language parameter — the server
        // auto-detects and reports what it found in the response's `language` / `language_code`.
        // Sending a hint would be an unknown field at best and a 400 `invalid_parameter` at
        // worst. The parameter stays for signature parity with [openAiTranscribe].
        @Suppress("UNUSED_PARAMETER") language: String?,
    ): List<TimedSegment> {
        val url = "${config.baseUrl}/asr"
        val boundary = "----CallrexBoundary" + System.nanoTime().toString(16)
        val out = ByteArrayOutputStream()
        fun header(vararg lines: String) {
            lines.forEach { out.write(it.toByteArray()); out.write("\r\n".toByteArray()) }
        }
        header(
            "--$boundary",
            "Content-Disposition: form-data; name=\"audio\"; filename=\"call.wav\"",
            "Content-Type: audio/wav",
            ""
        )
        out.write(wav)
        out.write("\r\n".toByteArray())
        fun field(name: String, value: String) {
            header("--$boundary", "Content-Disposition: form-data; name=\"$name\"", "")
            out.write(value.toByteArray())
            out.write("\r\n".toByteArray())
        }
        // Off by default on the server: without this the response has no `segments` (and no
        // `speaker_turns`) at all.
        field("ignore_timestamps", "false")
        // No `language` field: the /asr request takes no language parameter — the server
        // auto-detects and reports what it found in the response's `language` / `language_code`.
        // Sending a hint would be an unknown field at best and a 400 `invalid_parameter` at worst.
        out.write("--$boundary--\r\n".toByteArray())

        val headers = mutableMapOf("model" to config.model)
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
        val response = post(
            url = url,
            headers = headers,
            contentType = "multipart/form-data; boundary=$boundary",
            body = out.toByteArray(),
            readTimeoutMs = FISH_READ_TIMEOUT_MS,
        )
        return parseFish(JSONObject(response))
    }

    /**
     * Fish Audio's response, mapped to the app's timed segments.
     *
     * **`speaker_turns` when timestamps are requested.** With `ignore_timestamps=false`, a
     * multi-speaker run also returns the turns: each is an utterance with a clean [text] and
     * [start]/[end] in seconds — the granularity the local whisper path stores, and what the
     * runner's speaker labeller assigns A/B to by time overlap.
     *
     * **`text` fallback (the default shape).** A plain run — or a single voice with no turns —
     * returns one punctuated [text] plus word-level `segments`. The word segments are the wrong
     * unit here (one word a piece, no line breaks, no punctuation), so the whole [text] becomes
     * a single segment spanning the reported [duration]: the transcript stays complete and clean,
     * and only the per-line scrubbing timing is coarse. That is the honest trade rather than
     * inventing word-line breaks.
     */
    @VisibleForTesting
    internal fun parseFish(root: JSONObject): List<TimedSegment> {
        root.optJSONArray("speaker_turns")?.let { turns ->
            val parsed = (0 until turns.length()).mapNotNull { i ->
                val t = turns.optJSONObject(i) ?: return@mapNotNull null
                val text = stripSpeakerMarkers(t.optString("text")).trim()
                if (text.isEmpty()) null
                else TimedSegment(
                    startMs = (t.optDouble("start", 0.0) * 1000).toLong(),
                    endMs = (t.optDouble("end", 0.0) * 1000).toLong(),
                    text = text,
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }
        // The `text` field carries inline `<|speaker:N|>` markers on multi-speaker runs; they
        // belong to no one in the app's lines.
        val text = stripSpeakerMarkers(root.optString("text")).trim()
        if (text.isEmpty()) throw CloudApiException(0, "The transcription response carried no text")
        val durationMs = (root.optDouble("duration", 0.0) * 1000).toLong()
        return listOf(TimedSegment(0L, durationMs, text))
    }

    /** Drop the inline `<|speaker:0|>` turn markers so a turn's text is the words alone. */
    @VisibleForTesting
    internal fun stripSpeakerMarkers(text: String): String =
        text.replace(Regex("<\\|speaker:\\d+\\|>"), "")

    /**
     * Runs one chat completion and returns the first choice's text.
     */
    fun chatCompletion(
        config: com.baba.callvault.data.CloudSummaryConfig,
        prompt: String,
        maxTokens: Int,
    ): String {
        val payload = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            put("max_tokens", maxTokens)
            // Temperature stays out of the payload: the user's server decides its own defaults,
            // and a forced value here would be a second opinion the user never asked for.
        }
        val response = post(
            url = "${config.baseUrl}/chat/completions",
            headers = if (config.apiKey.isNotBlank())
                mapOf("Authorization" to "Bearer ${config.apiKey}") else emptyMap(),
            contentType = "application/json",
            body = payload.toString().toByteArray(Charsets.UTF_8),
        )
        val root = JSONObject(response)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw CloudApiException(0, "No choices in the completion response")
        val text = choice.optJSONObject("message")?.optString("content")
            ?: choice.optString("text")
        if (text.isBlank()) throw CloudApiException(0, "The completion returned no text")
        return text
    }

    // --- Response shapes, without requiring any one of them ---

    private fun parseTranscription(root: JSONObject): List<TimedSegment> {
        // OpenAI: { "segments": [ { "start": s, "end": s, "text": t }, ... ] } in SECONDS.
        root.optJSONArray("segments")?.let { segments ->
            val parsed = (0 until segments.length()).mapNotNull { i ->
                val s = segments.optJSONObject(i) ?: return@mapNotNull null
                val text = s.optString("text").trim()
                if (text.isEmpty()) null
                else TimedSegment(
                    startMs = (s.optDouble("start", 0.0) * 1000).toLong(),
                    endMs = (s.optDouble("end", 0.0) * 1000).toLong(),
                    text = text,
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }
        // Groq: { "chunks": [ { "timestamp": [s, e], "text": t }, ... ] } in SECONDS.
        root.optJSONArray("chunks")?.let { chunks ->
            val parsed = (0 until chunks.length()).mapNotNull { i ->
                val c = chunks.optJSONObject(i) ?: return@mapNotNull null
                val text = c.optString("text").trim()
                if (text.isEmpty()) null
                else {
                    val ts = c.optJSONArray("timestamp")
                    TimedSegment(
                        startMs = ((ts?.optDouble(0) ?: 0.0) * 1000).toLong(),
                        endMs = ((ts?.optDouble(1) ?: 0.0) * 1000).toLong(),
                        text = text,
                    )
                }
            }
            if (parsed.isNotEmpty()) return parsed
        }
        // Plain text: { "text": "..." } — no timing exists, so one segment covers the whole upload.
        val text = root.optString("text").trim()
        if (text.isEmpty()) throw CloudApiException(0, "The transcription response carried no text")
        return listOf(TimedSegment(0L, 0L, text))
    }

    // --- Transport ---

    private fun wavBody(
        boundary: String,
        config: com.baba.callvault.data.CloudTranscriptionConfig,
        wav: ByteArray,
        language: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun header(vararg lines: String) {
            lines.forEach { out.write(it.toByteArray()); out.write("\r\n".toByteArray()) }
        }
        // The audio, as a file part with its own disposition — not a form field.
        header(
            "--$boundary",
            "Content-Disposition: form-data; name=\"file\"; filename=\"call.wav\"",
            "Content-Type: audio/wav",
            ""
        )
        out.write(wav)
        out.write("\r\n".toByteArray())
        fun field(name: String, value: String) {
            header("--$boundary", "Content-Disposition: form-data; name=\"$name\"", "")
            out.write(value.toByteArray())
            out.write("\r\n".toByteArray())
        }
        field("model", config.model)
        if (!language.isNullOrBlank()) field("language", language)
        // json, explicitly: every shape the parser above accepts is JSON, and a server that answers
        // verbose text anyway is not a server this app should guess at.
        field("response_format", "json")
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    private fun post(
        url: String,
        headers: Map<String, String>,
        contentType: String,
        body: ByteArray,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val text = if (status in 200..299) {
                String(BufferedInputStream(connection.inputStream).use { it.readBytes() }, Charsets.UTF_8)
            } else {
                // The error body is a server message, not call content — safe to quote back in an error.
                runCatching {
                    String(
                        BufferedInputStream(connection.errorStream ?: connection.inputStream).use {
                            it.readBytes()
                        },
                        Charsets.UTF_8
                    )
                }.getOrDefault("")
            }
            if (status !in 200..299) {
                AppLogger.w(TAG, "Cloud request to ${maskUrl(url)} failed: HTTP $status $text")
                throw CloudApiException(status, "HTTP $status: $text".ifBlank { "HTTP $status" })
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    /** The host out, the path in — a full URL with a token or path segment in it is not a log line. */
    private fun maskUrl(url: String): String =
        runCatching { URL(url).host }.getOrNull() ?: "<unparsable>"

    // --- WAV encoding ---

    /**
     * 16 kHz mono float → little-endian WAV bytes (PCM 16, 16 kHz, 1 channel), the container every
     * OpenAI-compatible transcription endpoint accepts. The caller decodes with
     * [com.baba.callvault.transcription.AudioDecoder.decodeToMono16k], which returns exactly this
     * shape, so no resampling happens here.
     */
    fun toWav16kMono(samples: FloatArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        fun b32(v: Long) {
            out.write((v and 0xFF).toInt())
            out.write(((v shr 8) and 0xFF).toInt())
            out.write(((v shr 16) and 0xFF).toInt())
            out.write(((v shr 24) and 0xFF).toInt())
        }
        fun b16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray()); b32(36L + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); b32(16L)
        b16(1) // PCM
        b16(1) // mono
        b32(16_000L)
        b32(16_000L * 2) // byte rate
        b16(2) // block align
        b16(16) // bits per sample
        out.write("data".toByteArray()); b32(dataBytes.toLong())
        for (s in samples) {
            val clamped = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            b16(clamped)
        }
        return out.toByteArray()
    }
}
