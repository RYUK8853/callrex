/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.ui.common.TranscriptTimestamp

/**
 * Removing `[m:ss]` citations the transcript does not support.
 *
 * The prompt asks for the timestamp "copied verbatim from the transcript" and tells the model never
 * to invent one. It invents them anyway: a sibling project measured, across its stored summaries,
 * **11 carrying a marker that appears nowhere in their transcript and 3 citing a moment past the end
 * of the recording** — including `[24:50]` on a call lasting 8:49.
 *
 * An instruction is a request. This is the part that cannot be argued with, and it matters more here
 * than it did there because these markers are **tappable and seek the player**. A fabricated one is
 * a false citation on the one surface whose whole value is being checkable against the recording.
 *
 * Pure and Android-free, so it can be reasoned about and tested without a device.
 */
object SummaryCitations {

    /** A checked summary, and the markers taken out of it. */
    data class Checked(val summary: CallSummary, val removed: List<String>)

    /**
     * Slack past the end of a recording before a citation is impossible.
     *
     * Not zero. A marker names the **start** of a segment and the duration comes from a decoder, so
     * an exact-boundary citation on the last line is legitimate. Deleting a true citation is the
     * error that would matter — a missing marker is an inconvenience, a wrongly removed one destroys
     * something the user could have checked.
     */
    private const val DURATION_SLACK_MS = 3_000L

    private val MARKER = Regex("""\[(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?]""")

    /**
     * Strips every citation [offeredMs] does not vouch for.
     *
     * **The text it prefixed is kept.** The claim may well be right — the model read the transcript,
     * it just could not point at where — and deleting a whole item over an unverifiable marker would
     * throw away good analysis to punish a formatting error.
     *
     * @param offeredMs the segment start times the model was actually shown.
     * @param durationMs the recording's length, or 0 when unknown. Unknown disables the second gate
     *   rather than deleting everything, because a missing duration is not evidence of invention.
     */
    fun strip(summary: CallSummary, offeredMs: Set<Long>, durationMs: Long): Checked {
        val removed = mutableListOf<String>()

        fun clean(text: String): String = MARKER
            .replace(text) { match ->
                if (isCredible(match.value, offeredMs, durationMs)) match.value
                else {
                    removed += match.value
                    ""
                }
            }
            // A removed leading marker leaves a gap; close it without touching spacing the model
            // meant to be there.
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        // An item that was *only* a marker becomes empty, and an empty bullet reads as a fact the
        // app has lost rather than one the model never had.
        fun cleanAll(items: List<String>) = items.map(::clean).filter { it.isNotEmpty() }

        return Checked(
            summary = summary.copy(
                intent = clean(summary.intent),
                summary = clean(summary.summary),
                keyPoints = cleanAll(summary.keyPoints),
                decisions = cleanAll(summary.decisions),
                actionItems = cleanAll(summary.actionItems),
                keyFacts = cleanAll(summary.keyFacts)
            ),
            removed = removed
        )
    }

    /** Is this marker one the transcript vouches for, and one the recording can contain? */
    private fun isCredible(marker: String, offeredMs: Set<Long>, durationMs: Long): Boolean {
        val millis = TranscriptTimestamp.parseLeading("$marker x")?.millis ?: return false
        // Compared against what the model was shown, formatted the same way, so a segment starting
        // at 90_400 ms is vouched for by the [1:30] the prompt actually carried.
        val vouched = offeredMs.any { TranscriptTimestamp.format(it) == TranscriptTimestamp.format(millis) }
        if (!vouched) return false
        if (durationMs > 0L && millis > durationMs + DURATION_SLACK_MS) return false
        return true
    }
}
