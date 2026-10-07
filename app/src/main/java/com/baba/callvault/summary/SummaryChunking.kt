/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry

/**
 * Cutting a transcript into pieces a small model can actually read.
 *
 * A ninety-minute call is far past any context window worth running on a phone, so summarising it
 * means summarising parts and then summarising those — and where the cuts fall decides what the
 * model sees as one thought.
 *
 * Free of Compose and of Android, like [com.baba.callvault.data.recordings.RecordingSelection] and
 * for the same reason: this decides what gets read and what gets left out, so it has to be testable
 * without a device.
 */
object SummaryChunking {

    /**
     * A conservative chunk size in characters.
     *
     * Characters rather than tokens, because the tokeniser lives on the far side of JNI and chunking
     * has to work before a model is loaded. The ratio is not constant across the languages this app
     * supports — Hebrew and Arabic cost noticeably more tokens per character than English — so the
     * figure is deliberately well under what a 4k-token window would hold at English rates.
     *
     * [SummaryEngine.Session.countTokens] exists to check this guess against the truth once a model
     * is in memory.
     */
    const val DEFAULT_MAX_CHARS = 6_000

    /**
     * Groups [segments] into chunks of at most [maxChars], never splitting a segment.
     *
     * Segments are the natural seam: whisper emits them at pauses, so a cut between two of them
     * falls where the speaker stopped rather than mid-sentence.
     */
    fun chunk(
        segments: List<TranscriptSegmentEntry>,
        maxChars: Int = DEFAULT_MAX_CHARS
    ): List<List<TranscriptSegmentEntry>> {
        if (segments.isEmpty()) return emptyList()

        val chunks = mutableListOf<List<TranscriptSegmentEntry>>()
        var current = mutableListOf<TranscriptSegmentEntry>()
        var size = 0

        segments.forEach { segment ->
            // Start a new chunk when this one would overflow — but never emit an empty chunk, which
            // is what a segment longer than the whole limit would otherwise cause. Such a segment
            // travels alone and slightly oversized; the model truncates it, whereas dropping it
            // would lose part of the call with nothing to show that it had.
            if (current.isNotEmpty() && size + segment.text.length > maxChars) {
                chunks += current
                current = mutableListOf()
                size = 0
            }
            current += segment
            size += segment.text.length
        }

        if (current.isNotEmpty()) chunks += current
        return chunks
    }
}
