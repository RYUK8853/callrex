/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.cloud

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
    /** A transcription can outlive a minute of audio; a summary can outlive an hour. */
    private const val WRITE_TIMEOUT_MS = 10 * 60_000

    class CloudApiException(val status: Int, message: String) : Exception(message)

    /**
     * One recognised line of audio, timed against the start of the upload.
     */
    data class TimedSegment(val startMs: Long, val endMs: Long, val text: String)

    /**
     * Transcribes [wav] via the endpoint's `/v1/audio/transcriptions` and returns timed segments.
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
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
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
