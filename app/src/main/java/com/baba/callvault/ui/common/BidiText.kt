/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

/**
 * Keeps a name in its own writing direction when it sits inside a sentence in another.
 *
 * Most of the calls this app records are Hebrew, and most of its UI is English, so nearly every place
 * a contact name appears is a mixed-direction paragraph. Without isolation the whole paragraph resolves
 * one way and the name is just characters inside it: a name of Hebrew followed by a digit rendered as
 * *"Transcribing 2 גבריאלb keeps your phone busy"*, with the digit and the Latin letter thrown to the
 * wrong side.
 *
 * Unicode's isolate characters are the fix the standard provides for exactly this, and they work
 * regardless of which direction the surrounding layout happens to be in — unlike right-aligning things
 * by hand, which only ever fixes one of the two cases.
 */
object BidiText {

    /** FIRST STRONG ISOLATE — the run takes its direction from its own first strong character. */
    private const val FSI = '⁨'

    /** POP DIRECTIONAL ISOLATE — restores whatever direction was in force outside. */
    private const val PDI = '⁩'

    /**
     * [text] wrapped so it reads in its own direction wherever it is placed.
     *
     * Blank text is returned untouched: wrapping it would leave two invisible characters that every
     * `isBlank()` downstream would then call content. Already-isolated text is returned untouched too,
     * since labels pass through several layers and the markers would otherwise accumulate.
     */
    fun isolate(text: String): String = when {
        text.isBlank() -> ""
        text.first() == FSI && text.last() == PDI -> text
        else -> "$FSI$text$PDI"
    }

    /**
     * Whether [text] should be laid out right-to-left.
     *
     * Decided by the **first strong character**, which is the same rule the isolates above apply — so a
     * line opening with a timestamp, a quote or a Spanish "¿" is judged on its first actual letter
     * rather than on punctuation that has no direction of its own.
     *
     * Taken from the text and never from the language setting. That is what makes it right for all 99
     * languages whisper supports rather than the handful in the dropdown, and right for a call that
     * changes language halfway through — each line simply reads the way it is written.
     *
     * Text with no strong character at all (digits, punctuation) is treated as left-to-right. Either
     * answer would be defensible, but it must be a fixed one: choosing per line would make a transcript
     * jump about as it scrolled.
     */
    fun isRtl(text: String): Boolean {
        for (ch in text) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false

                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true

                // Everything else — digits, spaces, punctuation, the isolate markers themselves — is
                // neutral and carries no direction, so keep looking.
                else -> Unit
            }
        }
        return false
    }
}
