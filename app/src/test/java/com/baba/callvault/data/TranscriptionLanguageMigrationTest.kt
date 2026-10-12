/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The 2.4.11 re-pin of a stored auto-detect transcription language, and the per-call ask that
 * covers the languages a single pin cannot.
 *
 * The field measurement that forced it: a phone running `lang=auto` transcribed consecutive calls
 * as Hindi, then Urdu, then English, with the wrong script twice. Auto-detect on this phone's
 * Hindi-and-English calls flaps — the pin, and a one-tap ask for the other language, is the fix.
 *
 * The migration marks the phone "done" after one run, and tests in a class share the app instance
 * (and with it the prefs file), so each test starts from a cleared prefs file — the fresh-install
 * state the migration actually sees on a phone. The phone's locale is pinned the same way, with the
 * plain JDK [Locale.setDefault] (Robolectric tests run on the JVM, so the JVM default is what
 * [TranscriptionLanguageChoice.defaultLanguage] reads), and restored afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionLanguageMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(context)
    private lateinit var savedLocale: Locale

    @Before
    fun freshInstallState() {
        savedLocale = Locale.getDefault()
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun a_stored_auto_detect_is_re_pinned_to_the_phone_language_once() {
        Locale.setDefault(Locale.forLanguageTag("en-GB"))
        prefs.setTranscriptionLanguage(null) // what the wizard stored on the measured phone

        // The measured flap: auto-detect never lands on the same language twice. The migration
        // re-pins to the phone's own language — the best default available without asking.
        assertTrue(prefs.migrateAutoDetectToLocalePin())
        assertEquals("en", prefs.getTranscriptionLanguage())

        // Exactly once: a second run must not re-touch a language the user may have changed.
        prefs.setTranscriptionLanguage("hi")
        assertFalse(prefs.migrateAutoDetectToLocalePin())
        assertEquals("hi", prefs.getTranscriptionLanguage())
    }

    @Test
    fun a_hindi_phone_migrates_to_hindi() {
        // The main focus of the measured fleet: Hindi-and-English calls. A Hindi-set phone must
        // come out of the migration pinned to Hindi — never auto-detect, never English.
        Locale.setDefault(Locale.forLanguageTag("hi"))
        prefs.setTranscriptionLanguage(null)

        assertTrue(prefs.migrateAutoDetectToLocalePin())
        assertEquals("hi", prefs.getTranscriptionLanguage())
    }

    @Test
    fun a_named_language_is_left_alone() {
        // A pin the user named is never second-guessed, even on the first migration run.
        prefs.setTranscriptionLanguage("ur")

        assertFalse(prefs.migrateAutoDetectToLocalePin())
        assertEquals("ur", prefs.getTranscriptionLanguage())
    }

    @Test
    fun a_fresh_install_pins_the_phone_language_and_asks_per_call() {
        // Nothing stored: the fresh-install default must be a real pin, never auto-detect — the
        // auto-detect-on-a-Hebrew-phone failure this exists to avoid.
        Locale.setDefault(Locale.forLanguageTag("en-GB"))
        assertEquals("en", prefs.getTranscriptionLanguage())

        // The per-call ask is on by default: one confirming tap for the usual language, one switch
        // for the other, instead of whisper guessing per call.
        assertTrue(prefs.getTranscriptionAskLanguage())
    }
}
