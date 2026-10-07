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
 * Removing the same point said twice.
 *
 * Observed on the first real Hebrew summary this app produced: "Decisions" held four items, three of
 * them the same sentence, and "Key points" repeated one line three times. The model pads towards its
 * token budget — and raising that budget, which is what stopped whole runs being lost to truncation,
 * gave it more room to do exactly that.
 *
 * The prompt now asks it not to repeat itself. Small models ignore instructions about counts and
 * repetition, so this is the part that does not ask. Same reasoning as [SummaryCitations].
 *
 * **Near-identical, not identical.** The observed repeats were not byte-equal — one differed by a
 * single mis-transcribed word — so exact matching would have removed none of them.
 */
object SummaryDedupe {

    /**
     * How much of an item's wording must be shared before it is the same point.
     *
     * High, because the error that matters runs the other way. A summary that repeats itself is
     * untidy; one missing a decision is wrong, and the user has no way to know it is missing.
     */
    private const val SAME_POINT_RATIO = 0.8

    /**
     * Below this many words, only an exact match counts.
     *
     * Short items share words trivially: "Pay now" and "Pay later" are half identical by words and
     * mean opposite things.
     */
    private const val MINIMUM_WORDS_FOR_FUZZY = 4

    /** The summary with repeated points removed, keeping the first wording of each. */
    fun apply(summary: CallSummary): CallSummary {
        // One pass down the card, each list dropping what the ones above it already said.
        //
        // The order is by how specific a claim is, and it runs one way only: a decision is never
        // dropped for restating a key point. Observed on a real call — "Worth keeping" repeated
        // four of the six "Key points" word for word, which reads as the app padding the card.
        val decisions = withoutRepeats(summary.decisions)
        val actionItems = withoutRepeats(summary.actionItems, alreadySaid = decisions)
        val keyPoints = withoutRepeats(summary.keyPoints, alreadySaid = decisions + actionItems)
        val keyFacts = withoutRepeats(summary.keyFacts, alreadySaid = decisions + actionItems + keyPoints)

        return summary.copy(
            keyPoints = keyPoints,
            decisions = decisions,
            actionItems = actionItems,
            keyFacts = keyFacts
        )
    }

    /** [items] with anything that repeats an earlier item — or anything in [alreadySaid] — dropped. */
    private fun withoutRepeats(items: List<String>, alreadySaid: List<String> = emptyList()): List<String> {
        val seen = alreadySaid.map(::wordsOf).toMutableList()
        val kept = mutableListOf<String>()

        items.forEach { item ->
            val words = wordsOf(item)
            if (words.isEmpty() || seen.none { isSamePoint(words, it) }) {
                seen += words
                kept += item
            }
        }
        return kept
    }

    /**
     * The comparable words of an item: no timestamp, no punctuation, no case.
     *
     * The stamp is stripped because two items differing only by their marker are the same point
     * cited twice, not two points.
     */
    private fun wordsOf(item: String): List<String> {
        val withoutStamp = TranscriptTimestamp.parseLeading(item)?.text ?: item
        return withoutStamp
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
    }

    /** Do these two items say the same thing? */
    private fun isSamePoint(a: List<String>, b: List<String>): Boolean {
        if (a == b) return true
        // Short items are compared strictly — see MINIMUM_WORDS_FOR_FUZZY.
        if (a.size < MINIMUM_WORDS_FOR_FUZZY || b.size < MINIMUM_WORDS_FOR_FUZZY) return false

        val shared = a.toSet().intersect(b.toSet()).size
        // Against the shorter item, so a longer restatement of a point still counts as repeating it.
        return shared.toDouble() / minOf(a.size, b.size) >= SAME_POINT_RATIO
    }
}
