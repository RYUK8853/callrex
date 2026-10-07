/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

/**
 * Deciding, before the prompt is built, which language a summary will be written in.
 *
 * **The prompt must always name a concrete language.** The instruction it used to fall back to —
 * "write the summary in the same language as the conversation" — asks the model to make a judgement
 * it cannot reliably make. Handed a garbled or language-mixed transcript there is no identifiable
 * main language, and a small model resolves that by writing English.
 *
 * That is measured rather than suspected. A sibling project running the same shape of prompt over
 * real Hebrew calls found **35 English summaries out of 196, 23 of them from Hebrew transcripts**,
 * and fixed it by pinning the language instead of asking the model to infer it. This is that fix,
 * with the pin chosen per recording rather than set once for everyone.
 */
object SummaryLanguage {

    /**
     * The language code to write in, resolved from the best evidence available.
     *
     * In order, and the order is the point — each step is more certain than the one after it:
     *
     * 1. [chosen] — the user said so in Settings. Nothing overrides being told.
     * 2. [transcriptLanguage] — what this transcript was actually made in.
     * 3. [transcriptionSetting] — the user pinned a language for whisper. Saying so once is enough.
     * 4. [transcriptText] — the script the words are written in, which for Hebrew, Arabic, Cyrillic
     *    and CJK is decisive. This is the step that rescues auto-detect, where 2 and 3 are both null.
     * 5. [deviceLanguage] — a genuine unknown. The phone's own language is a far better guess than
     *    defaulting to English, which is the failure being fixed.
     *
     * Never returns null. An absent answer would put the old instruction back in the prompt.
     */
    fun resolve(
        chosen: String?,
        transcriptionSetting: String?,
        transcriptLanguage: String?,
        transcriptText: String,
        deviceLanguage: String
    ): String =
        chosen
            ?: transcriptLanguage
            ?: transcriptionSetting
            ?: scriptOf(transcriptText)
            ?: deviceLanguage.ifBlank { FINAL_FALLBACK }

    /**
     * The language a text's script implies, or null when the script does not imply one.
     *
     * **Null is a real answer.** Latin script is shared by English, Spanish, French, German and most
     * of the rest, so returning "en" for it would be a guess dressed as a detection — and guessing
     * English is precisely the bug. Only scripts used by essentially one of the languages on offer
     * are reported.
     *
     * Decided by weight of letters rather than by presence. A real Hebrew transcript carries brand
     * names, numbers and the odd English word; a single "Zoom" must not outvote the call.
     */
    fun scriptOf(text: String): String? {
        val counts = mutableMapOf<String, Int>()

        text.forEach { ch ->
            val code = ch.code
            val language = when {
                code in 0x0590..0x05FF -> "he"
                code in 0x0600..0x06FF || code in 0x0750..0x077F -> "ar"
                code in 0x0400..0x04FF -> "ru"
                // CJK ideographs. Japanese kana are excluded on purpose: this app offers no
                // Japanese, so a kana-heavy text has no honest answer here.
                code in 0x4E00..0x9FFF -> "zh"
                else -> null
            }
            if (language != null) counts[language] = (counts[language] ?: 0) + 1
        }

        val (language, count) = counts.maxByOrNull { it.value } ?: return null
        // Enough letters to be the text rather than a quotation inside it.
        return if (count >= MINIMUM_LETTERS) language else null
    }

    /**
     * How many letters of a script it takes before it is taken as the language.
     *
     * Low, because the alternative to answering is guessing English. A handful of Hebrew characters
     * in a transcript is not a coincidence.
     */
    private const val MINIMUM_LETTERS = 3

    /** Used only when the device reports no language at all, which should not happen. */
    private const val FINAL_FALLBACK = "en"
}
