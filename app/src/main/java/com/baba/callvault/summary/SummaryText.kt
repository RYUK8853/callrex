/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

/**
 * Turning what a model emitted into what a person should read.
 *
 * Some models think out loud first, in a `<think>` block, and only then answer. Measured on the OP12
 * with Qwen3.5-4B, that block ran to hundreds of tokens of *"Analyze the Request... Correction: I
 * need to look at the actual user input..."* — and on two of four runs it consumed the entire token
 * budget, so the summary was never reached at all. Left in, it would be shown to the user as though
 * it were the summary of their call.
 *
 * Both things here are about a token budget that ran out, at opposite ends of the answer: one drops
 * what the model wrote before it started, the other closes what it never got to finish.
 */
object SummaryText {

    private val THINK_BLOCK = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)

    /** An opened block that never closed, because the token budget ran out mid-thought. */
    private val UNCLOSED_THINK = Regex("""<think>.*""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The answer alone, or empty when the model never got to one.
     *
     * Empty is a meaningful result rather than a failure to paper over: it means the budget was
     * spent thinking, and the caller should say so instead of showing a blank summary.
     */
    fun stripReasoning(raw: String): String =
        raw.replace(THINK_BLOCK, "")
            .replace(UNCLOSED_THINK, "")
            .trim()

    /**
     * [text] with its unfinished tail dropped and its open brackets closed, or `null` when there is
     * nothing to close.
     *
     * A generation that reaches its token budget stops mid-document, and [SummaryGrammar] fixes the
     * key order with the arrays last — so an answer that ran long used to lose the **entire chunk**
     * over a missing `}`, including every point it had already got right. Hebrew and Arabic cost
     * more tokens for the same content than English does, which means the languages that need the
     * budget most are the ones that hit it, and losing the chunk is minutes of work the user sat and
     * watched ending in nothing.
     *
     * **Only what was already finished survives.** The cut is made back at the last completed value,
     * so a half-written item is dropped rather than shown with its last word missing, and the
     * brackets open at that point are closed in order. Nothing is repaired or guessed at: this
     * closes an object, it does not finish one.
     *
     * Values are strings and arrays of strings, because that is all the grammar can produce — a bare
     * number or `true` would not be recognised as a completed value, which costs a little recovery
     * and never produces a wrong one.
     *
     * `null` for text with no object in it, for an object that is already whole, and for one cut
     * before anything at all had finished.
     */
    fun closeTruncated(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

        val open = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var cut = -1
        var openAtCut = emptyList<Char>()

        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                inString && char == '"' -> {
                    inString = false
                    // A closing quote ends a *value* only when what follows says so. Anything else
                    // — a `:`, or the end of the text, where there is no way to tell a finished
                    // value from a key whose colon never arrived — is not a place to cut.
                    if (nextMeaningful(text, index + 1)?.let { it in VALUE_FOLLOWERS } == true) {
                        cut = index + 1
                        openAtCut = open.toList()
                    }
                }
                inString -> Unit
                char == '"' -> inString = true
                char == '{' || char == '[' -> open.addLast(char)
                char == '}' || char == ']' -> {
                    open.removeLastOrNull() ?: return null
                    // The object closed on its own. Whatever is wrong with it, truncation is not it.
                    if (open.isEmpty()) return null
                    cut = index + 1
                    openAtCut = open.toList()
                }
                else -> Unit
            }
        }

        if (open.isEmpty() || cut < 0 || openAtCut.isEmpty()) return null
        return text.substring(start, cut) +
            openAtCut.reversed().joinToString("") { if (it == '[') "]" else "}" }
    }

    /** What may follow a finished value: the next item, or the end of the thing holding it. */
    private val VALUE_FOLLOWERS = setOf(',', '}', ']')

    /** The next character that is not whitespace, or `null` at the end of the text. */
    private fun nextMeaningful(text: String, from: Int): Char? =
        (from until text.length).firstOrNull { !text[it].isWhitespace() }?.let { text[it] }
}
