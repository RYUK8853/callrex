/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Moves a timestamp that landed in a pause back onto the speech it belongs to — issue #25.
 *
 * **The bug this repairs.** whisper.cpp's VAD does not trim silence, it **rebuilds** the audio from the
 * stretches it kept, and then maps times back through a table that represents *every* real gap, however
 * long, with a hardcoded 100 ms of processed time (`src/whisper.cpp`, `silence_samples = 0.1 *
 * WHISPER_SAMPLE_RATE`). Two bridges span each pause and one of them has slope zero, so a segment that
 * begins after a pause is stamped at **the instant the previous speaker stopped**. Measured on the
 * reporter's own call: dialog that starts at 88.3 s was stamped at ~76 s — **12.3 s early**, across a
 * 9.86 s pause.
 *
 * **Why this is ours to fix and not upstream's.** whisper.cpp issue #3634 is this symptom verbatim; it
 * was closed by a stale bot rather than fixed. But upstream *did* fix the same thing one level down —
 * PR #3910 gave token timestamps a segment-aware mapping that **snaps a time landing in removed silence
 * to the nearest real boundary** — and that code is already in our pinned v1.9.3
 * (`whisper_map_token_time_segment_aware`). This applies upstream's own rule at the level our
 * transcript actually uses, with public API and no patch to the submodule.
 *
 * **The seam rule, and why a simpler one does not work.** Measured on the reporter's own recording
 * (2026-09-06): the post-pause line is stamped at **76.52 s** while speech resumes at **88.53 s** — and
 * 76.52 s is *inside* the previous stretch (74.54–77.04), not in the gap. So "move starts that land in
 * a pause" would have changed nothing for him. The reason is visible in the raw numbers: whisper's own
 * timestamp for that line is 65.96 s in the concatenated timeline, about **0.4 s before** the seam,
 * i.e. still in the tail of the previous stretch — and removing an 11.5 s pause **amplifies that 0.4 s
 * into 12 s**. Small timestamp error, enormous consequence, entirely because the gap is gone.
 *
 * So a start is also moved when it lands in the **last [SEAM_TOLERANCE_MS] of a stretch** while the
 * segment itself continues past the following pause. A line that begins as one stretch ends and runs on
 * after a pause did not begin before the pause; it began after it.
 *
 * **Direction matters.** A *start* moves FORWARD, never back: the words come after the pause, which is
 * how the reporter put it — *"blank space needs to be appended to end of last time instead of beginning
 * of this one."* An *end* moves BACK to where speech stopped, so a caption cannot run on through
 * silence that was never there.
 *
 * **This is a heuristic and is written down as one.** Whisper's timestamps across a seam are not
 * trustworthy to better than a few hundred milliseconds, and no post-pass can recover what the model
 * did not know. The trade is deliberate: a line that genuinely begins in the last second of a stretch
 * and continues past a pause loses up to a second at its front, in exchange for lines that are no
 * longer stamped a dozen seconds early.
 *
 * Pure arithmetic over the stretches the VAD kept, so it is testable without a device or a model.
 */
object SpeechGapSnap {

    /** One stretch of speech the VAD kept, in ORIGINAL audio time. */
    data class Speech(val startMs: Long, val endMs: Long)

    /**
     * How close to a stretch's end a start must be to count as "really after the next pause".
     *
     * One second, because the measured error at the seam was ~0.5 s and whisper's segment starts are
     * quantised to 20 ms windows on top of that. Larger would start moving lines that genuinely begin
     * near the end of a stretch.
     */
    private const val SEAM_TOLERANCE_MS = 1_000L

    /**
     * Returns [segments] with any timestamp that fell inside a removed pause moved onto real speech.
     *
     * [speech] is what the VAD kept, in original time and in order. **Empty means "no idea"** — VAD off,
     * or a device that reported nothing — and then every timestamp is returned exactly as whisper gave
     * it. Inventing a correction without knowing where the speech was would be worse than the bug.
     */
    fun apply(segments: List<TranscriptSegment>, speech: List<Speech>): List<TranscriptSegment> {
        if (speech.isEmpty() || segments.isEmpty()) return segments
        val ordered = speech.sortedBy { it.startMs }
        return segments.map { segment ->
            val start = snapForward(segment.startMs, ordered)
                .let { seamCorrected(it, segment.endMs, ordered) }
            val end = snapBackward(segment.endMs, ordered)
            // A segment whose ends both sat in the same gap would come out inverted; keeping whisper's
            // own duration is the honest answer there, since only its position was ever wrong.
            val safeEnd = if (end > start) end else start + (segment.endMs - segment.startMs).coerceAtLeast(1L)
            if (start == segment.startMs && safeEnd == segment.endMs) segment
            else segment.copy(startMs = start, endMs = safeEnd)
        }
    }

    /**
     * A start sitting in the dying moments of a stretch, on a segment that runs on past the pause after
     * it, belongs to the speech after that pause. See the class comment for the measurement.
     */
    private fun seamCorrected(startMs: Long, segmentEndMs: Long, speech: List<Speech>): Long {
        for (i in speech.indices) {
            val s = speech[i]
            val next = speech.getOrNull(i + 1) ?: continue
            val atTheEndOfThisStretch = startMs in (s.endMs - SEAM_TOLERANCE_MS)..s.endMs
            val butTheLineRunsOnPastThePause = segmentEndMs > next.startMs
            if (atTheEndOfThisStretch && butTheLineRunsOnPastThePause) return next.startMs
        }
        return startMs
    }

    /** A start inside a pause belongs to the speech that FOLLOWS it. */
    private fun snapForward(timeMs: Long, speech: List<Speech>): Long {
        if (timeMs < speech.first().startMs) return speech.first().startMs
        for (i in speech.indices) {
            val s = speech[i]
            if (timeMs in s.startMs..s.endMs) return timeMs
            val next = speech.getOrNull(i + 1) ?: continue
            if (timeMs > s.endMs && timeMs < next.startMs) return next.startMs
        }
        return timeMs
    }

    /** An end inside a pause belongs to the speech that PRECEDED it. */
    private fun snapBackward(timeMs: Long, speech: List<Speech>): Long {
        if (timeMs > speech.last().endMs) return timeMs
        for (i in speech.indices) {
            val s = speech[i]
            if (timeMs in s.startMs..s.endMs) return timeMs
            val next = speech.getOrNull(i + 1) ?: continue
            if (timeMs > s.endMs && timeMs < next.startMs) return s.endMs
        }
        return timeMs
    }
}
