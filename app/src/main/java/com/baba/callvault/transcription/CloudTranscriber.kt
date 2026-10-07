/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.net.Uri
import com.baba.callvault.data.CloudTranscriptionConfig
import com.baba.callvault.data.cloud.CloudApiClient
import com.baba.callvault.server.speakers.OfflineSpeakerLabeller
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [Transcriber] that decodes the recording on-device and sends it, as a 16 kHz WAV, to the
 * user's own OpenAI-compatible endpoint.
 *
 * The decode still happens on the phone, and it is the same decode the local engine feeds, so the
 * two halves of the run stay shared with [TranscriptionRunner]:
 *
 *  - **Speakers.** The interleaved PCM is offered to [OfflineSpeakerLabeller] on the way through,
 *    so a stereo capture gets A/B labels for a cloud transcript exactly as it gets them for a
 *    whisper one. The endpoint sees no channel information.
 *  - **Wrong-script retry.** A cloud answer in the wrong alphabet is re-requested by
 *    [WrongScriptRetry] like a local one; the retry is a fresh request with no local decode to vary.
 *
 * [modelPath] and [settings] are part of the shared interface for the local engine and are unused
 * here: there is no model to load, and the decode path is the same whichever endpoint is asked.
 */
class CloudTranscriber(
    private val config: CloudTranscriptionConfig
) : Transcriber {

    override suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelPath: String,
        language: String?,
        prompt: String?,
        speakers: OfflineSpeakerLabeller?,
        settings: DecodeSettings
    ): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        val range = AudioDecoder.decodeRange(
            context = context,
            uri = uri,
            onInterleaved = { pcm, length, channels, sampleRate, startMs ->
                speakers?.accept(pcm, length, channels, sampleRate, startMs)
            }
        )
        val wav = CloudApiClient.toWav16kMono(range.audio)

        // The runner's pin wins (it is the call's language); the endpoint's own pin is only a
        // second preference, for runs where the runner had nothing to say.
        val languageToSend =
            language?.takeIf { it.isNotBlank() }
                ?: config.language.takeIf { it.isNotBlank() }

        val timed = CloudApiClient.transcribe(config, wav, languageToSend)
        // The decode may have started slightly before 0 (a seek lands on the previous sync point),
        // so the upload's time zero is range.startMs, not the recording's.
        val base = range.startMs
        if (timed.size == 1 && timed[0].startMs == 0L && timed[0].endMs == 0L) {
            // The endpoint produced one blob with no timing: span it over the whole decode rather
            // than store two zeroes, which every consumer would read as "zero length".
            val end = base + range.audio.size * 1000L / SAMPLE_RATE
            AppLogger.i(TAG, "Cloud endpoint returned no timing; one segment spans ${end - base} ms")
            listOf(TranscriptSegment(base, end, timed[0].text))
        } else {
            timed.map { TranscriptSegment(base + it.startMs, base + it.endMs, it.text) }
        }
    }

    private companion object {
        const val TAG = "CV:CloudTranscriber"
        const val SAMPLE_RATE = 16_000
    }
}
