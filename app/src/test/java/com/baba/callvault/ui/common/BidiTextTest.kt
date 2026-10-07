/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping a Hebrew name from being torn apart by the English sentence around it.
 *
 * The failure this prevents is real and was on screen: a contact whose name is Hebrew followed by a
 * digit rendered as "Transcribing 2 גבריאלb keeps your phone busy" — the digit and the Latin letter
 * had migrated to the wrong side, because the whole paragraph was resolved left-to-right and the name
 * was just characters inside it.
 */
class BidiTextTest {

    private val FSI = '\u2068'
    private val PDI = '\u2069'

    @Test
    fun a_name_is_wrapped_so_it_keeps_its_own_direction() {
        // First-strong isolate: the run decides its own direction from its first strong character, and
        // the pop restores whatever was in force outside. Hebrew therefore reads right-to-left even
        // inside an English sentence, and vice versa.
        val isolated = BidiText.isolate("גבריאל 2")

        assertEquals(FSI + "גבריאל 2" + PDI, isolated)
    }

    @Test
    fun latin_text_is_wrapped_the_same_way() {
        // Not a Hebrew-only fix: an English name inside a Hebrew sentence has the mirror problem, and
        // a rule with an exception is a rule someone will forget.
        assertEquals(FSI + "AthenX" + PDI, BidiText.isolate("AthenX"))
    }

    @Test
    fun an_empty_name_gains_nothing() {
        // Wrapping nothing would put two invisible characters into an otherwise empty string, which
        // then reads as non-empty to every isBlank() check downstream.
        assertEquals("", BidiText.isolate(""))
        assertEquals("", BidiText.isolate("   "))
    }

    @Test
    fun wrapping_twice_does_not_nest() {
        // Labels pass through several layers; isolating an already-isolated name would accumulate
        // invisible characters on every hop.
        val once = BidiText.isolate("גבריאל")

        assertEquals(once, BidiText.isolate(once))
    }

    @Test
    fun hebrew_is_recognised_as_right_to_left() {
        assertTrue(BidiText.isRtl("שלום עולם"))
    }

    @Test
    fun latin_is_not() {
        assertTrue(!BidiText.isRtl("hello world"))
    }

    @Test
    fun leading_digits_and_punctuation_do_not_decide_the_direction() {
        // A Hebrew line often opens with a quote or a number. Neutral characters carry no direction of
        // their own, so the first *strong* character is what counts — deciding on the first character
        // outright would flip a good share of the lines in a transcript.
        assertTrue(BidiText.isRtl("\"שלום\""))
        assertTrue(BidiText.isRtl("123 שלום"))
        assertTrue(!BidiText.isRtl("123 hello"))
    }

    @Test
    fun text_with_no_strong_character_reads_left_to_right() {
        // Digits and punctuation alone. Either answer is defensible, but it has to be *an* answer:
        // choosing per line at random would make a transcript jump about as it scrolled.
        assertTrue(!BidiText.isRtl("12:34"))
        assertTrue(!BidiText.isRtl(""))
    }

    @Test
    fun an_isolate_marker_is_seen_through() {
        // Labels arrive already isolated. The marker is itself neutral, so this only works because the
        // scan looks for the first strong character rather than the first character.
        assertTrue(BidiText.isRtl(BidiText.isolate("שלום")))
    }

    @Test
    fun every_offered_language_gets_the_direction_its_script_needs() {
        // The direction comes from the text, not from the language setting — which is what makes this
        // right for all 99 languages whisper supports, not just the 13 in the dropdown, and right for a
        // call that switches language halfway through. A sample of each offered script:
        val rightToLeft = mapOf(
            "Hebrew" to "שלום, מה נשמע?",
            "Arabic" to "مرحبا، كيف حالك؟"
        )
        val leftToRight = mapOf(
            "English" to "Hello, how are you?",
            "Chinese" to "你好，最近怎么样？",
            "French" to "Bonjour, ça va ?",
            "German" to "Guten Tag, wie geht es Ihnen?",
            "Hungarian" to "Jó napot, hogy van?",
            "Italian" to "Buongiorno, come sta?",
            "Polish" to "Dzień dobry, jak się masz?",
            "Portuguese" to "Bom dia, tudo bem?",
            "Russian" to "Здравствуйте, как дела?",
            "Spanish" to "Buenos días, ¿qué tal?",
            "Vietnamese" to "Xin chào, bạn khỏe không?"
        )

        rightToLeft.forEach { (name, sample) ->
            assertTrue("$name must render right-to-left", BidiText.isRtl(sample))
        }
        leftToRight.forEach { (name, sample) ->
            assertTrue("$name must render left-to-right", !BidiText.isRtl(sample))
        }
    }

    @Test
    fun spanish_opening_punctuation_does_not_flip_the_line() {
        // "¿" and "¡" open a sentence and are neutral, so a naive first-character test would give up on
        // them. Worth its own case because it is the one Latin script that routinely starts on a
        // non-letter.
        assertTrue(!BidiText.isRtl("¿Qué tal?"))
        assertTrue(!BidiText.isRtl("¡Hola!"))
    }

    @Test
    fun the_visible_text_is_unchanged() {
        // The markers are formatting characters, not content: what the user reads must be identical.
        val name = "גבריאל 2"
        val isolated = BidiText.isolate(name)

        assertTrue(isolated.contains(name))
        assertEquals(name, isolated.filterNot { it == FSI || it == PDI })
    }
}
