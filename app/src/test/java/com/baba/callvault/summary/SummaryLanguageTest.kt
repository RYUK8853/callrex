/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which language a summary is written in.
 *
 * **The prompt must always name a concrete language.** A Hebrew call summarised into English is the
 * bug this exists to prevent, and the instruction that caused it was "write in the same language as
 * the conversation" — which asks the model to make a judgement it cannot make on a poor transcript.
 * Handed garbled or language-mixed text there is no identifiable main language, and the model falls
 * back to English.
 *
 * This is not a theory. A sibling project running the same shape of prompt over real Hebrew calls
 * measured **35 English summaries out of 196, 23 of them from Hebrew transcripts**, and fixed it by
 * pinning the language rather than asking the model to infer it.
 */
class SummaryLanguageTest {

    @Test
    fun `an explicit choice wins over everything`() {
        assertEquals(
            "he",
            SummaryLanguage.resolve(
                chosen = "he",
                transcriptionSetting = "en",
                transcriptLanguage = "en",
                transcriptText = "hello there",
                deviceLanguage = "en"
            )
        )
    }

    @Test
    fun `falls back to the language the transcript was made in`() {
        assertEquals(
            "he",
            SummaryLanguage.resolve(
                chosen = null,
                transcriptionSetting = null,
                transcriptLanguage = "he",
                transcriptText = "hello there",
                deviceLanguage = "en"
            )
        )
    }

    @Test
    fun `falls back to a pinned transcription setting`() {
        // The user told whisper the calls are Hebrew. Saying so once should be enough.
        assertEquals(
            "he",
            SummaryLanguage.resolve(
                chosen = null,
                transcriptionSetting = "he",
                transcriptLanguage = null,
                transcriptText = "hello there",
                deviceLanguage = "en"
            )
        )
    }

    @Test
    fun `reads the script off the transcript when nothing was pinned`() {
        // Auto-detect leaves every stored language null, so the words themselves are the only
        // evidence left — and for these scripts they are decisive.
        assertEquals(
            "he",
            SummaryLanguage.resolve(
                chosen = null,
                transcriptionSetting = null,
                transcriptLanguage = null,
                transcriptText = "שלום, מה שלומך היום",
                deviceLanguage = "en"
            )
        )
    }

    @Test
    fun `falls back to the device language only when nothing else can tell`() {
        // Latin script cannot separate English from Spanish, so this is a genuine unknown — and the
        // device's own language is a far better guess than defaulting to English.
        assertEquals(
            "es",
            SummaryLanguage.resolve(
                chosen = null,
                transcriptionSetting = null,
                transcriptLanguage = null,
                transcriptText = "hola que tal",
                deviceLanguage = "es"
            )
        )
    }

    @Test
    fun `never resolves to nothing`() {
        // The one outcome that must be impossible: an empty answer would put "the same language as
        // the conversation" back in the prompt.
        assertEquals(
            "en",
            SummaryLanguage.resolve(null, null, null, "", "en")
        )
    }

    // ---- script detection ----

    @Test
    fun `detects hebrew`() {
        assertEquals("he", SummaryLanguage.scriptOf("שלום עולם"))
    }

    @Test
    fun `detects arabic, and does not confuse it with hebrew`() {
        // Whisper is measured to return Arabic on Hebrew audio when it auto-detects. The two must
        // never collapse into each other here as well.
        assertEquals("ar", SummaryLanguage.scriptOf("مرحبا بالعالم"))
    }

    @Test
    fun `detects cyrillic and chinese`() {
        assertEquals("ru", SummaryLanguage.scriptOf("привет мир"))
        assertEquals("zh", SummaryLanguage.scriptOf("你好世界"))
    }

    @Test
    fun `latin script is not a language`() {
        // Refusing to answer is right: "hello" and "hola" share a script, and guessing English here
        // is exactly the failure being fixed.
        assertNull(SummaryLanguage.scriptOf("hello world"))
        assertNull(SummaryLanguage.scriptOf(""))
    }

    @Test
    fun `a few latin words do not outvote a hebrew call`() {
        // Real Hebrew transcripts carry brand names, numbers and the odd English word. The decision
        // is by weight of letters, not by presence.
        val mostlyHebrew = "שלום אני מדבר על Zoom ועל Google Drive בבקשה תחזור אליי מחר בבוקר"

        assertEquals("he", SummaryLanguage.scriptOf(mostlyHebrew))
    }

    @Test
    fun `digits and punctuation do not count as a script`() {
        assertNull(SummaryLanguage.scriptOf("12:30 — 1,240 (!!) ... 99%"))
    }
}
