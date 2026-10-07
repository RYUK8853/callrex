/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The same real call, transcribed once per [DecodeSettings] variant, so each knob can be judged on
 * its own output rather than on a citation.
 *
 * There is no WER here and there is not going to be one without a reference transcript, so the
 * evidence this produces is the **text itself**: every variant's transcript is written out in full,
 * next to its wall-clock and segment count, for a human to read side by side. A number that does not
 * come with the words it scored cannot distinguish "fewer segments" from "lost half the call".
 *
 * Variants are named on the command line so a run can be cut short — a full sweep is four passes
 * over the same audio, which on the OP9 Pro is the better part of an hour.
 *
 *     adb shell am instrument -w \
 *       -e class com.baba.callvault.transcription.DecodeVariantBenchmark \
 *       -e recording /sdcard/Download/cv-bench.ogg \
 *       -e variants baseline,vad,vad_beam,vad_beam_ctx0 \
 *       com.baba.callvault.instrtest.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The recording is named rather than discovered, for the same reason [com.baba.callvault.summary]'s
 * benchmark names it: which of someone's calls gets processed is their choice. The transcripts are a
 * real conversation and stay on the device.
 *
 * **ColorOS freezes an instrumented run whose app has no foreground window.** Launch MainActivity
 * right after `am instrument` or this stalls in a way that looks exactly like a native deadlock —
 * the process stays alive at its full RSS with `utime` frozen, which reads as a hang in whisper. It
 * is not enough to launch it once: the shade sliding down over the activity re-freezes it, so a run
 * this long needs `svc power stayon true` and something that re-foregrounds the activity if focus
 * moves. A wall-clock measured across a freeze is worthless; the first run of this benchmark
 * reported 743 s for a 350 s decode.
 *
 * MEASURED, OP9 Pro (LE2121), `large-v3-turbo-q8_0`, a real 4:05 Hebrew business call, `lang=he`:
 *
 * | variant | wall | segments | chars |
 * |---|---|---|---|
 * | `baseline`       | 362 s | 54 | 2645 |
 * | `vad`            | 341 s | 47 | 2706 |
 * | `vad_beam`       | 358 s | 47 | **2749** |
 * | `vad_beam_ctx64` | **310 s** | 42 | 2662 |
 * | `vad_beam_ctx0`  | 332 s | **79** | 2475 |
 *
 * Read the transcripts, not the table: `vad_beam_ctx0`'s 79 segments are the same words cut into
 * one-to-two-second pieces, and its 2475 characters are the same call with content missing. The
 * per-field KDoc on [DecodeSettings] quotes the specific lines each variant gained and lost.
 *
 * **A SECOND CALL OVERTURNED THE BEAM VERDICT, AND THIS IS WHY THE SWEEP EXISTS.** Same device and
 * model, a real 8:46 Hebrew support escalation that opens with music on the line — the full 2×2,
 * including the `beam` cell the first sweep deliberately skipped:
 *
 * | variant | wall | segments | chars | chars in first 94 s |
 * |---|---|---|---|---|
 * | `baseline` | 725 s | 297 | 4692 | 1008 |
 * | `vad`      | 763 s | 207 | **5970** | **1117** |
 * | `beam`     | 852 s | 276 | 5448 | 1017 |
 * | `vad_beam` | 846 s | 117 | 5022 | **21** |
 *
 * `vad_beam` — what shipped as 2.1.1 — emitted the language tag `*ערבית*` at 0 s, 34 s and 64 s and
 * **lost the first 94 seconds of the call**. Neither knob does that alone. The cell that proves it
 * is `beam`, which is exactly the cell skipped the first time on the grounds that it was "a variant
 * we have reason to believe is bad and no reason to spend twenty minutes confirming". Twenty
 * minutes would have been cheap: without it, the sweep cannot tell a VAD regression from a beam
 * regression, and the interaction that actually bit is invisible.
 *
 * Two lessons, both cheap to honour: **run the whole 2×2, including cells you expect to lose**, and
 * **one call cannot clear a decoding change** — the 4:05 call would have shipped beam-5 unopposed.
 */
@RunWith(AndroidJUnit4::class)
class DecodeVariantBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The sweep, in the order the changes have to land in.
     *
     * Beam search appears both with and without VAD. It originally did not — the argument was that
     * arXiv:2501.11378 measures higher beam widths hallucinating more on non-speech, so beam without
     * VAD was believed bad and not worth twenty minutes to confirm. That reasoning cost the project
     * a bad release: `beam` turned out to be *fine* and `vad_beam` catastrophic, which is the one
     * conclusion the incomplete sweep could not reach. Keep the full 2×2.
     */
    private val variants = linkedMapOf(
        // What shipped before any of this: greedy, no VAD, whisper's own conditioning cap.
        "baseline" to DecodeSettings(beamSize = 1, maxTextCtx = -1, useVad = false),
        // Change 1 alone.
        "vad" to DecodeSettings(beamSize = 1, maxTextCtx = -1, useVad = true),
        // Change 2 alone, without change 1. Believed bad on paper (arXiv:2501.11378), which is why
        // it was skipped the first time — but "believed bad" is not a measurement, and without this
        // cell the sweep cannot tell a VAD regression from a beam regression.
        "beam" to DecodeSettings(beamSize = 5, maxTextCtx = -1, useVad = false),
        // Change 2, on top of change 1.
        "vad_beam" to DecodeSettings(beamSize = 5, maxTextCtx = -1, useVad = true),
        // Change 3, both directions, on top of both.
        "vad_beam_ctx64" to DecodeSettings(beamSize = 5, maxTextCtx = 64, useVad = true),
        "vad_beam_ctx0" to DecodeSettings(beamSize = 5, maxTextCtx = 0, useVad = true),
    )

    @Test
    fun sweep() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val recordingPath = args.getString("recording")
        assumeTrue("pass -e recording <path to an audio file>", recordingPath != null)
        val recording = File(recordingPath!!)
        assumeTrue("no such recording: $recordingPath", recording.isFile)

        // By name and not by extension: phones accumulate unrelated .bin files, and "the first one"
        // picks a stranger's and then blames whisper for failing to load it.
        val dirs = listOfNotNull(File("/sdcard/Download"), context.getExternalFilesDir(null))
        val model = dirs.flatMap { it.listFiles()?.toList().orEmpty() }
            .firstOrNull { it.isFile && it.name.startsWith("ggml-") && it.name.endsWith(".bin") }
        assumeTrue("no ggml-*.bin in ${dirs.joinToString { it.absolutePath }}", model != null)

        // Pinned, not auto-detected. Auto-detect writes Hebrew in Latin letters often enough that a
        // variant could look worse for a reason that has nothing to do with the variant.
        val language = args.getString("language") ?: "he"
        val selected = args.getString("variants")?.split(",")?.map { it.trim() }?.toSet()
        val toRun = variants.filterKeys { selected == null || it in selected }
        assumeTrue("no variants matched ${args.getString("variants")}", toRun.isNotEmpty())

        val metrics = StringBuilder()
        metrics.appendLine("CallVault decode-variant sweep")
        metrics.appendLine("device:    ${android.os.Build.MODEL}")
        metrics.appendLine("model:     ${model!!.name}")
        metrics.appendLine("recording: ${recording.name} (${recording.length() / 1024} KB)")
        metrics.appendLine("language:  $language")
        metrics.appendLine()

        toRun.forEach { (name, settings) ->
            val started = SystemClock.elapsedRealtime()
            val segments = TranscriptionEngine.transcribe(
                context = context,
                uri = Uri.fromFile(recording),
                modelPath = model.absolutePath,
                language = language,
                settings = settings,
            )
            val elapsedMs = SystemClock.elapsedRealtime() - started

            // Characters, not just segment count. A variant that halves the segments has either
            // merged them or dropped them, and only the character total tells those apart —
            // silently losing speech is the failure mode that segment counts hide.
            val chars = segments.sumOf { it.text.length }
            metrics.appendLine(
                "$name  [$settings]\n" +
                    "  ${elapsedMs / 1000}s wall-clock, ${segments.size} segments, $chars chars"
            )
            // Written per variant rather than at the end, so a sweep interrupted halfway still
            // leaves everything it had finished measuring.
            File(outputDir(), "decode-$name.txt").writeText(
                segments.joinToString("\n") { "[${it.startMs / 1000}s] ${it.text}" }
            )
            File(outputDir(), "decode-metrics.txt").writeText(metrics.toString())
            android.util.Log.i("CV:Bench", "$name: ${elapsedMs / 1000}s, ${segments.size} seg, $chars chars")
        }
    }

    /** Shared storage where it is writable, the app's own directory where ColorOS will not allow it. */
    private fun outputDir(): File =
        File("/sdcard/Download").takeIf { it.canWrite() } ?: context.getExternalFilesDir(null)!!
}
