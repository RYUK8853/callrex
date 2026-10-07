/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

/**
 * What the Home pill is saying about transcription right now.
 *
 * Transcription is deliberately silent — no notification, nothing asked of the user — but an overnight
 * backlog is hours of sustained CPU, and a phone that runs warm with nothing on screen to explain it
 * is not acceptable. So: invisible when idle, and a small pill while working.
 *
 * The choice between the forms is a pure function because every wrong answer here still *renders*:
 * "0/0" before the worker has said anything, a stuck-looking "1/1" for a single tapped recording, or
 * a pill left on screen for ever because a finished worker keeps its last progress.
 */
sealed interface TranscribingPillState {

    /** Whether this state claims Home's single `titleTrailing` slot from the support pill. */
    val occupiesTitleSlot: Boolean get() = this != Hidden

    /** Nothing is being transcribed. */
    data object Hidden : TranscribingPillState

    /** Running, but the worker has not reported what it is doing yet. */
    data object Starting : TranscribingPillState

    /** One recording, transcribed because the user asked for it. Shown without a count. */
    data class Single(val current: String?, val percent: Int = 0) : TranscribingPillState

    /** A backlog: which one is in hand, how many there are, and how far into this one. */
    data class Batch(
        val position: Int,
        val total: Int,
        val current: String?,
        val percent: Int = 0
    ) : TranscribingPillState

    /** The recording being worked on, when one is known. */
    fun currentName(): String? = when (this) {
        is Single -> current
        is Batch -> current
        Hidden, Starting -> null
    }

    /** The same state, showing [percent] instead of whatever it was carrying. */
    fun withPercent(percent: Int): TranscribingPillState = when (this) {
        is Single -> copy(percent = percent)
        is Batch -> copy(percent = percent)
        Hidden, Starting -> this
    }

    /**
     * How far into [displayName], or 0 when that is not the recording being worked on.
     *
     * Asked per row rather than pushed, because only one recording is ever in hand: every other row
     * in a queued batch has not started, and showing them a proportion would be a lie.
     */
    fun percentFor(displayName: String): Int = when (this) {
        is Single -> if (current == displayName) percent else 0
        is Batch -> if (current == displayName) percent else 0
        Hidden, Starting -> 0
    }

    companion object {

        /**
         * @param isRunning whether a transcription worker is actually running. Required, and not
         *   inferable from the numbers: WorkManager retains a finished worker's last progress, so
         *   without this the pill would survive the run that produced it.
         * @param completed how many recordings this run has finished.
         * @param total how many it was given.
         * @param current the recording in hand, when known.
         * @param percent how far into the current recording, 0-100. Zero means "not known yet",
         *   which is honest for the first moments of a run and is shown as no number rather than
         *   as 0% — a bar pinned at zero looks stuck, which is the very impression to avoid.
         */
        fun from(
            isRunning: Boolean,
            completed: Int,
            total: Int,
            current: String?,
            percent: Int = 0
        ): TranscribingPillState = when {
            !isRunning -> Hidden
            total <= 0 -> Starting
            total == 1 -> Single(current, percent)
            // People count the item being worked on, not the ones behind it — so the first of twelve
            // is "1/12". Capped, so the last is "12/12" rather than "13/12".
            else -> Batch(
                position = (completed + 1).coerceAtMost(total),
                total = total,
                current = current,
                percent = percent
            )
        }
    }
}
