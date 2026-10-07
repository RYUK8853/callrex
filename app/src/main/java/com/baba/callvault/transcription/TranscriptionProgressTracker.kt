/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Keeps the percentage a run reports about itself.
 *
 * [TranscriptionProgress] decides what number to show from whisper's last anchor, the clock and the
 * figure shown before it. Those last two have to be kept somewhere, and where they were kept was the
 * bug: they lived in the Home screen's composition. Rotating the phone destroys that, so the clock
 * restarted at zero and the figure fell from (say) 49% back to 1% and climbed the curve again —
 * which reads exactly like the transcription starting over, and was reported as such (mirror176,
 * issue #34). The work was never affected; only the number was.
 *
 * Holding it here, in the worker that does the transcribing, makes the figure a property of the run.
 * Nothing the UI does — rotating, leaving the screen, coming back — can touch it. It is the same
 * arrangement summarising already used ([com.baba.callvault.summary.SummaryWorker]), so the two
 * long jobs in this app now report progress the same way.
 *
 * Android-free, and the clock is passed in rather than read, so every rule below is testable.
 */
class TranscriptionProgressTracker {

    /** The recording the figures below describe; null before the first one is announced. */
    private var current: String? = null
    private var startedAtMs: Long = 0L
    private var estimatedMs: Long = 0L
    private var shown: Int = 0

    /**
     * Announces the recording now being transcribed.
     *
     * Announcing the **same** recording again is deliberately a no-op: the announcement can arrive
     * more than once for one run, and restarting the clock is the defect this class exists to stop.
     * A different recording is a different clock — a batch must not open at the previous one's
     * figure.
     *
     * @param estimatedMs how long this recording is expected to take, or 0 when it is not known, in
     *   which case nothing is predicted and only whisper's real anchors move the number.
     */
    fun startRecording(displayName: String, estimatedMs: Long, nowMs: Long) {
        if (current == displayName) return
        current = displayName
        startedAtMs = nowMs
        this.estimatedMs = estimatedMs
        shown = 0
    }

    /**
     * The figure to publish, given whisper's latest anchor.
     *
     * Zero until a recording has been announced: there is nothing yet to be a percentage of, and
     * "1%" would claim work that has not started.
     */
    fun percent(reportedPercent: Int, nowMs: Long): Int {
        if (current == null) return 0
        shown = TranscriptionProgress.display(
            reportedPercent = reportedPercent,
            elapsedMs = nowMs - startedAtMs,
            estimatedMs = estimatedMs,
            previous = shown,
        )
        return shown
    }
}
