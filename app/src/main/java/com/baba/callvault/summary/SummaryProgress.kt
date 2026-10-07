/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

/**
 * How a summary's progress is turned into a number.
 *
 * Split out of [SummaryWorker] so the two judgements in it can be pinned by tests rather than left
 * as constants nobody revisits. Both exist because a summary reports far less often than a
 * transcription does: whisper emits a segment every few seconds, while this produces one anchor per
 * chunk, and a chunk is about two minutes.
 *
 * The gaps between those anchors are filled by
 * [com.baba.callvault.transcription.TranscriptionProgress], which is the same problem and the same
 * fix — a curve that always moves and never arrives — and is already tested.
 */
object SummaryProgress {

    /**
     * The share of the bar the chunk passes own, leaving the rest for the merge.
     *
     * A multi-chunk call ends with a merge that is the single longest generation of the run, because
     * it carries every part's points forward at once. Letting the chunks reach 100 would park the
     * bar at finished for all of it — the same defect as a transcription that vanished at 70%
     * because segment timestamps stop at the last words spoken.
     */
    const val CHUNKS_SHARE = 85

    /**
     * Milliseconds a chunk is expected to take.
     *
     * Measured on the OP12: a real call took about 97 s, or 130 s once per-line timestamps were in
     * the prompt — and the prompt used here carries them. Used only to keep the bar moving between
     * anchors, so being wrong makes the motion badly paced rather than the figure wrong.
     */
    const val MILLIS_PER_CHUNK = 130_000L

    /**
     * How far through, from chunks actually finished.
     *
     * Zero before the first one lands, and never [CHUNKS_SHARE] until every chunk is done. A single
     * chunk still stops short of 100 because it too is followed by parsing and a write.
     */
    fun chunkAnchor(completed: Int, total: Int): Int {
        if (total <= 0 || completed <= 0) return 0
        return (completed.coerceAtMost(total) * CHUNKS_SHARE) / total
    }

    /** How long the whole run is expected to take. At least one chunk's worth. */
    fun estimatedMs(chunks: Int): Long = MILLIS_PER_CHUNK * (if (chunks <= 0) 1 else chunks)
}
