/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

/**
 * The user's own cloud endpoint for transcription (any OpenAI-compatible
 * `/v1/audio/transcriptions` server: OpenAI, Groq, DeepInfra, a local faster-whisper
 * OpenAI proxy, ...).
 *
 * **Nothing is preset.** The base URL, model name and key are entered by the user
 * and stored on-device only; nothing ever leaves the phone except the audio the
 * user explicitly sent. `null` on any of the three means "not configured".
 */
data class CloudTranscriptionConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    /** Empty = let the endpoint detect the language. */
    val language: String,
) {
    fun isValid(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()

    fun copyWith(base: CloudTranscriptionConfig): CloudTranscriptionConfig =
        CloudTranscriptionConfig(
            baseUrl = if (baseUrl.isNotBlank()) baseUrl else base.baseUrl,
            model = if (model.isNotBlank()) model else base.model,
            apiKey = if (apiKey.isNotBlank()) apiKey else base.apiKey,
            language = if (language.isNotBlank()) language else base.language,
        )

    companion object {
        /** Trims user input and normalises the endpoint down to `.../v1`. */
        fun normalizeBase(url: String): String {
            var u = url.trim()
            while (u.endsWith("/")) u = u.dropLast(1)
            if (u.endsWith("/v1")) return u
            return u
        }
    }
}

/**
 * The user's own cloud model for summarisation (any OpenAI-compatible
 * `/v1/chat/completions` server). Same trust model as [CloudTranscriptionConfig]:
 * the user's own endpoint, their own key, stored on-device, no presets.
 */
data class CloudSummaryConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    fun isValid(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()

    fun copyWith(base: CloudSummaryConfig): CloudSummaryConfig =
        CloudSummaryConfig(
            baseUrl = if (baseUrl.isNotBlank()) baseUrl else base.baseUrl,
            model = if (model.isNotBlank()) model else base.model,
            apiKey = if (apiKey.isNotBlank()) apiKey else base.apiKey,
        )
}
