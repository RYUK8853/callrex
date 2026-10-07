/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Issue #25, end to end through the app's own pipeline, on the reporter's own recording.
 *
 * The unit tests pin the arithmetic; this pins the thing they cannot — that the whole chain (whisper,
 * the VAD, the JNI getters, [SpeechGapSnap]) really puts his line back where it was spoken.
 *
 * **Ground truth, measured 2026-09-06:** speech stops at 77.04 s and resumes at **88.53 s**; whisper
 * stamps *"Thank you so much for connecting…"* at **76.52 s**, twelve seconds early. After the fix that
 * line must start at or after 88 s.
 *
 * Skipped unless its two inputs have been pushed to the test app's external files directory:
 * ```
 * DIR=/sdcard/Android/data/com.baba.callvault.instrtest/files
 * adb push issue25.raw $DIR/issue25.raw          # 16 kHz mono f32le, via ffmpeg
 * adb push ggml-large-v3-turbo-q8_0.bin $DIR/model.bin
 * ```
 */
class Issue25TimestampTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun the_line_after_the_long_pause_is_not_stamped_twelve_seconds_early() {
        val dir = context.getExternalFilesDir(null)
        val model = File(dir, "model.bin")
        val pcm = File(dir, "issue25.raw")
        assumeTrue("push model.bin and issue25.raw first — see the class comment", model.exists() && pcm.exists())

        val audio = pcm.readBytes().let { bytes ->
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(fb.remaining()).also { fb.get(it) }
        }
        Log.i(TAG, "decoded ${audio.size} samples (${audio.size / 16000}s)")

        val ptr = WhisperNative.initContext(model.absolutePath, context.applicationInfo.nativeLibraryDir)
        check(ptr != 0L) { "whisper would not load the model" }
        val segments = try {
            TranscriptionEngine.transcribeBuffer(
                ptr = ptr,
                audio = audio,
                language = "en",
                prompt = null,
                vadModelPath = VadModel.ensureExtracted(context),
                settings = DecodeSettings(),      // exactly what the app ships with
            )
        } finally {
            WhisperNative.freeContext(ptr)
        }

        segments.forEach { Log.i(TAG, "  ${it.startMs / 1000.0}s -> ${it.endMs / 1000.0}s  ${it.text.take(52)}") }

        // Specific on purpose: an EARLIER line contains "I'm connecting…", and matching that instead
        // is how the first run of this test failed against a fix that was working.
        val line = segments.firstOrNull { it.text.contains("Thank you so much for connecting", ignoreCase = true) }
            ?: segments.last()
        Log.i(TAG, "the line after the pause: ${line.startMs}ms — ${line.text.take(60)}")

        // Before the fix this was ~76 500. Speech resumes at 88 530.
        if (line.startMs < 88_000) {
            throw AssertionError(
                "the post-pause line is still stamped early: ${line.startMs}ms, expected >= 88000ms " +
                    "(speech resumes at 88530ms). Text: ${line.text.take(60)}",
            )
        }
    }

    private companion object {
        const val TAG = "CV:Issue25"
    }
}
