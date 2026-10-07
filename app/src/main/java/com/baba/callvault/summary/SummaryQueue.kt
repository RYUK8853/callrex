/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.transcription.TranscriptionEngine
import kotlinx.coroutines.flow.first

/** Why a call cannot be summarised right now. Each maps to something the user can be told. */
enum class SummaryBlocker {
    /** No transcript at all. The summary is read from one, so there is nothing to read. */
    NO_TRANSCRIPT,

    /** A transcript exists but is queued, running, or failed. */
    TRANSCRIPT_UNFINISHED,

    /** The transcript finished with no words in it. */
    TRANSCRIPT_EMPTY,

    /** The summarisation model has not been downloaded. */
    MODEL_MISSING,

    /** A transcription is using the same cores. */
    TRANSCRIBING,

    /** Another summary is already running. */
    SUMMARISING
}

/**
 * Whether a call may be summarised, and if not, why.
 *
 * There is deliberately **no batch queue here**, unlike transcription. Summarising costs roughly
 * ninety seconds of full CPU and about two gigabytes of memory per call, so sweeping a library would
 * be hours of heat for pages nobody asked for — the same mistake the background waveform pass made.
 * Summaries are asked for one at a time, and this is the gate they pass through.
 */
object SummaryQueue {

    /**
     * The reason [displayName] cannot be summarised, or `null` if it can.
     *
     * The order the reasons are checked in is a UI decision rather than an accident — see [blocker].
     */
    suspend fun blockerFor(
        context: Context,
        displayName: String,
        isModelInstalled: Boolean
    ): SummaryBlocker? {
        if (!TranscriptDatabase.exists(context)) return SummaryBlocker.NO_TRANSCRIPT

        val db = TranscriptDatabase.get(context)
        val transcript = db.transcriptDao().findTranscript(displayName)
        val segmentCount = if (transcript?.state == TranscriptState.DONE) {
            db.transcriptDao().observe(displayName).first()?.segments?.size ?: 0
        } else {
            0
        }

        return blocker(
            state = transcript?.state,
            segmentCount = segmentCount,
            isModelInstalled = isModelInstalled,
            isTranscribing = TranscriptionEngine.isRunning,
            isSummarising = SummaryEngine.isRunning,
            isAlreadySummarised = db.summaryDao().summary(displayName) != null
        )
    }

    /**
     * The gate itself, with every input passed in so it can be reasoned about and tested directly.
     *
     * **The order is the point.** Whatever else is wrong, the reason shown has to be the one the
     * user can act on, and the actions differ: a missing transcript means transcribe first, a
     * missing model means download 3.5 GB, a busy phone means wait. Durable problems are reported
     * before transient ones — telling somebody to wait and *then*, once they have, telling them to
     * start a multi-gigabyte download wastes the wait.
     *
     * @param isAlreadySummarised accepted, and deliberately not a blocker: redo exists precisely
     *   because the first attempt was not good enough.
     */
    fun blocker(
        state: TranscriptState?,
        segmentCount: Int,
        isModelInstalled: Boolean,
        isTranscribing: Boolean,
        isSummarising: Boolean,
        @Suppress("UNUSED_PARAMETER") isAlreadySummarised: Boolean
    ): SummaryBlocker? = when {
        state == null -> SummaryBlocker.NO_TRANSCRIPT
        state != TranscriptState.DONE -> SummaryBlocker.TRANSCRIPT_UNFINISHED
        // A done transcript with no segments is not a theoretical case: a language auto-detect bug
        // produced exactly that, and summarising one spends ninety seconds to describe nothing.
        segmentCount <= 0 -> SummaryBlocker.TRANSCRIPT_EMPTY
        !isModelInstalled -> SummaryBlocker.MODEL_MISSING
        // Both drive ggml across the same performance cores. Run together they are slower than run
        // in turn, and hot enough to be throttled for the trouble.
        isTranscribing -> SummaryBlocker.TRANSCRIBING
        // The engine serialises on a mutex, so a second run would not fail — it would wait, in
        // silence, behind a button that looks as though it did nothing.
        isSummarising -> SummaryBlocker.SUMMARISING
        else -> null
    }
}
