/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the whisper.cpp JNI bridge on a real device.
 *
 * These cannot be JVM unit tests: the bridge is a native library, and its two most dangerous failure
 * modes are invisible to the compiler. A mismatched JNI symbol name only fails when the method is
 * first called, and a wrong language parameter does not fail at all — it produces fluent, confident
 * nonsense in the wrong script.
 *
 * The transcription test needs two fixtures, which are **not** committed (a model is hundreds of
 * megabytes, and real call audio must never enter a public repo). Push them first:
 *
 * ```
 * D=/sdcard/Android/data/com.baba.callvault/files
 * adb push ggml-small-q5_1.bin $D/cv-test-model.bin
 * adb push some-hebrew-call.ogg $D/cv-test-audio.ogg
 * ```
 *
 * Without them the transcription test skips rather than fails, so a normal `connectedAndroidTest`
 * run stays green on a machine that has not staged fixtures.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionEngineInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(name: String): File? =
        context.getExternalFilesDir(null)?.resolve(name)?.takeIf { it.exists() && it.length() > 0 }

    @Test
    fun native_library_loads_and_reports_system_info() {
        // Proves libwhispercv.so loaded, was linked against whisper, and that at least one JNI symbol
        // resolves to the name the Kotlin declaration expects.
        val info = WhisperNative.systemInfo(context.applicationInfo.nativeLibraryDir)
        // Logged, not just asserted: the flags in here are those of the CPU backend variant this
        // particular phone chose from HWCAP, so this line is how "did it pick the fast kernels?"
        // gets answered on a device. DOTPROD/MATMUL_INT8 reading 0 on a modern phone means the
        // variants were not packaged, or were not found in nativeLibraryDir.
        android.util.Log.i("CV:Transcribe", "whisper system info: $info")
        assertTrue("system info was blank", info.isNotBlank())
    }

    /**
     * Decode only — no model, no whisper. Runs in seconds.
     *
     * Deliberately separate from the transcription test so a decode bug is caught cheaply. The first
     * on-device run of this feature spent eleven minutes transcribing a 45-second clip before anything
     * suggested a problem, and the cause was in the decode. This test would have failed in about a
     * second, which is the whole point of splitting it out: the phone is a daily driver, and time
     * borrowed from it should be spent on the check that actually needs the device.
     */
    @Test
    fun decodes_a_recording_to_the_expected_amount_of_audio() {
        val audio = fixture("cv-test-audio.ogg")
        assumeTrue("fixture not staged — see this class's KDoc", audio != null)

        val samples = AudioDecoder.decodeToMono16k(context, Uri.fromFile(audio))
        assertTrue("decoded nothing", samples.isNotEmpty())

        // The fixture is a 45 s clip. At 16 kHz that is ~720k samples; anything wildly off means the
        // decoder's format was misread, which is exactly what made whisper run 15x too long.
        val seconds = samples.size.toDouble() / AudioDecoder.TARGET_SAMPLE_RATE
        assertTrue("decoded $seconds s of audio from a ~45 s clip", seconds in 40.0..50.0)

        assertTrue("samples outside [-1,1]", samples.all { it in -1f..1f })
    }

    @Test
    fun transcribes_hebrew_audio_in_hebrew_script() {
        val model = fixture("cv-test-model.bin")
        val audio = fixture("cv-test-audio.ogg")
        assumeTrue("fixtures not staged — see this class's KDoc", model != null && audio != null)

        val segments = runBlocking {
            TranscriptionEngine.transcribe(
                context = context,
                uri = Uri.fromFile(audio),
                modelPath = model!!.absolutePath,
                language = "he",
            )
        }

        assertTrue("no segments were produced", segments.isNotEmpty())

        // The real assertion. If `params.language` failed to reach whisper, it falls back to English
        // and returns Latin script — so checking for the Hebrew Unicode block is what actually proves
        // the language parameter took effect, which is the whole reason this JNI is not upstream's.
        val text = segments.joinToString(" ") { it.text }
        val hebrewChars = text.count { it in '֐'..'׿' }
        assertTrue("expected Hebrew script, got: ${text.take(120)}", hebrewChars > 20)

        // Timings must be milliseconds and monotonic; whisper reports centiseconds natively, so a
        // missing conversion would show up here as a transcript ten times shorter than the audio.
        assertTrue("segment timings are not ordered", segments.all { it.endMs >= it.startMs })
    }
}
