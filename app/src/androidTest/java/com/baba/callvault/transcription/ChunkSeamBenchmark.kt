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
 * The same recording transcribed twice — whole-file, then in chunks — so the two can be read against
 * each other.
 *
 * **Why this exists.** Chunked transcription was written, unit-tested, shipped, and made real
 * transcripts worse: lines that did not match the audio, and repeated lines. It was reverted the same
 * day. The ten unit tests behind it all passed, because they tested the *planning arithmetic* — which
 * was never where the risk was. The risk was in the audio, and nothing touched audio until a person
 * listened to a real call.
 *
 * So the chunked path lives **here**, not in [TranscriptionEngine], until this benchmark says it is
 * safe. Production still transcribes whole files.
 *
 *     adb shell am instrument -w \
 *       -e class com.baba.callvault.transcription.ChunkSeamBenchmark \
 *       -e recording /sdcard/Download/cv-bench.ogg \
 *       com.baba.callvault.instrtest.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Use a recording **longer than [ChunkPlan.TARGET_CHUNK_MS]**, or there is only one chunk and no seam
 * to examine. Read [DecodeVariantBenchmark]'s KDoc first: the same ColorOS foreground-window trap
 * applies, and a wall-clock measured across a freeze is worthless.
 *
 * ## What this is actually for
 *
 * Not a score — a **discriminator**. Four hypotheses survived the revert and each predicts a different
 * signature, so the report below is built to tell them apart rather than to say "worse":
 *
 *  1. **Seek reports a rebased timestamp.** The design stitches from where the decoder says its audio
 *     really began. If that comes back at or near 0 for every chunk, every timestamp after the first is
 *     wrong. → the `decoded from` column shows it outright, and needs no transcript reading at all.
 *  2. **Codec priming after a seek.** Opus pre-skip and AAC priming are emitted after a seek and are
 *     not real audio. → `decoded from` sits slightly *before* the request, consistently, by tens of ms.
 *  3. **Cold starts hallucinate.** Every chunk begins with no conditioning on a 10 s run-up, which is
 *     exactly when whisper loops. → repetition clusters at the *start* of chunks 2+, and the duplicated
 *     text sits just after a seam rather than anywhere.
 *  4. **The seams simply cost more than predicted.** → no timing anomaly, no clustering, just worse
 *     text everywhere in the chunked run.
 *
 * Hypotheses 1 and 2 are settled by the header alone. 3 and 4 need the transcripts read side by side,
 * which is why both are written out in full.
 */
@RunWith(AndroidJUnit4::class)
class ChunkSeamBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Seek accuracy alone — no model, no transcription, seconds rather than an hour.
     *
     * Two of the four hypotheses behind the chunking failure are about the **decoder**, not about
     * whisper, and both are answered by one number per chunk: the gap between where a slice was asked
     * to start and where the decoder says it actually started.
     *
     *  - **drift ≈ 0** → the seek reports an absolute timestamp. The stitching design holds, and the
     *    damage came from whisper, not from the offsets. Go read the transcripts.
     *  - **drift ≈ −(requested)** → the timestamp is rebased to the seek point, so it is ~0 for every
     *    chunk and every offset after the first is wrong. That is the bug, on its own, and no
     *    transcript needs reading.
     *  - **drift small and consistently negative** (tens of ms) → codec priming after a seek.
     *
     * Run this FIRST. An hour of transcription to learn something a decode answers in seconds is the
     * same mistake as shipping the feature on unit tests that never touched audio.
     *
     *     adb shell am instrument -w \
     *       -e class com.baba.callvault.transcription.ChunkSeamBenchmark#seekDrift \
     *       -e recording /sdcard/Download/cv-bench.ogg \
     *       com.baba.callvault.instrtest.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun seekDrift() {
        val args = InstrumentationRegistry.getArguments()
        val recordingPath = args.getString("recording")
        assumeTrue("pass -e recording <path to an audio file>", recordingPath != null)
        val recording = File(recordingPath!!)
        assumeTrue("no such recording: $recordingPath", recording.isFile)

        val uri = Uri.fromFile(recording)
        val durationMs = AudioDecoder.durationMs(context, uri)
        val plan = ChunkPlan.plan(durationMs)

        val report = StringBuilder()
        report.appendLine("CallVault seek-drift probe")
        report.appendLine("recording: ${recording.name}, ${durationMs / 1000}s, ${plan.size} chunk(s)")
        report.appendLine()
        report.appendLine("chunk  requested-from  decoded-from  drift-ms  samples  seconds-of-audio")

        plan.forEachIndexed { index, chunk ->
            val slice = AudioDecoder.decodeRange(context, uri, fromMs = chunk.decodeFromMs, toMs = chunk.endMs)
            val seconds = slice.audio.size.toLong() / AudioDecoder.TARGET_SAMPLE_RATE
            report.appendLine(
                "%5d  %14d  %12d  %8d  %7d  %16d".format(
                    index, chunk.decodeFromMs, slice.startMs,
                    slice.startMs - chunk.decodeFromMs, slice.audio.size, seconds,
                )
            )
        }

        report.appendLine()
        report.appendLine("drift ~0 => seek is absolute, stitching design is sound, blame whisper")
        report.appendLine("drift ~ -(requested) => timestamp is rebased; THIS is the bug")

        File(outputDir(), "chunkseam-drift.txt").writeText(report.toString())
        android.util.Log.i("CV:Bench", report.toString())
    }

    @Test
    // Explicit Unit: JUnit4 rejects a test method with a return value, and runBlocking returns
    // whatever its block's last expression evaluates to.
    fun wholeFileVersusChunked(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val recordingPath = args.getString("recording")
        assumeTrue("pass -e recording <path to an audio file>", recordingPath != null)
        val recording = File(recordingPath!!)
        assumeTrue("no such recording: $recordingPath", recording.isFile)

        val dirs = listOfNotNull(File("/sdcard/Download"), context.getExternalFilesDir(null))
        val model = dirs.flatMap { it.listFiles()?.toList().orEmpty() }
            .firstOrNull { it.isFile && it.name.startsWith("ggml-") && it.name.endsWith(".bin") }
        assumeTrue("no ggml-*.bin in ${dirs.joinToString { it.absolutePath }}", model != null)

        // Pinned, for the same reason the variant sweep pins it: auto-detect writes Hebrew in Latin
        // letters often enough that one arm could look worse for a reason unrelated to chunking.
        val language = args.getString("language") ?: "he"
        val settings = DecodeSettings.DEFAULT
        val uri = Uri.fromFile(recording)
        val durationMs = AudioDecoder.durationMs(context, uri)
        val plan = ChunkPlan.plan(durationMs)

        val report = StringBuilder()
        report.appendLine("CallVault whole-file vs chunked")
        report.appendLine("device:    ${android.os.Build.MODEL}")
        report.appendLine("model:     ${model!!.name}")
        report.appendLine("recording: ${recording.name}, ${durationMs / 1000}s")
        report.appendLine("settings:  $settings, lang=$language")
        report.appendLine("plan:      ${plan.size} chunk(s)")
        report.appendLine()

        assumeTrue(
            "recording is shorter than one chunk (${durationMs / 1000}s) — there is no seam to test",
            plan.size > 1,
        )

        // ---- control: exactly what production does today ----
        val controlStart = SystemClock.elapsedRealtime()
        val control = TranscriptionEngine.transcribe(
            context = context, uri = uri, modelPath = model.absolutePath,
            language = language, settings = settings,
        )
        val controlMs = SystemClock.elapsedRealtime() - controlStart
        writeTranscript("chunkseam-whole.txt", control)

        // ---- chunked, orchestrated here rather than in the engine ----
        val vadModelPath = if (settings.useVad) VadModel.ensureExtracted(context) else null
        val ptr = WhisperNative.initContext(model.absolutePath, context.applicationInfo.nativeLibraryDir)
        assumeTrue("could not load the model", ptr != 0L)

        val chunked = mutableListOf<TranscriptSegment>()
        val seams = mutableListOf<Long>()
        val chunkedStart = SystemClock.elapsedRealtime()
        try {
            report.appendLine("chunk  requested-from  decoded-from  drift   raw-segments  kept")
            plan.forEachIndexed { index, chunk ->
                val slice = AudioDecoder.decodeRange(
                    context, uri, fromMs = chunk.decodeFromMs, toMs = chunk.endMs,
                )
                val raw = TranscriptionEngine.transcribeBuffer(
                    ptr, slice.audio, language, null, vadModelPath, settings,
                )
                val kept = ChunkPlan.stitch(raw, chunk.copy(decodeFromMs = slice.startMs))
                chunked += kept
                if (index > 0) seams += chunk.keepFromMs

                // THE line that settles hypotheses 1 and 2. A drift of ~0 means the seek reported an
                // absolute timestamp and the design holds; a drift equal to -requested means it was
                // rebased to the seek point, and every offset after chunk 0 is wrong.
                report.appendLine(
                    "%5d  %14d  %12d  %6d  %12d  %4d".format(
                        index, chunk.decodeFromMs, slice.startMs,
                        slice.startMs - chunk.decodeFromMs, raw.size, kept.size,
                    )
                )
            }
        } finally {
            WhisperNative.freeContext(ptr)
        }
        val chunkedMs = SystemClock.elapsedRealtime() - chunkedStart
        writeTranscript("chunkseam-chunked.txt", chunked)

        report.appendLine()
        report.appendLine("whole-file: ${controlMs / 1000}s, ${control.size} segments, ${control.sumOf { it.text.length }} chars")
        report.appendLine("chunked:    ${chunkedMs / 1000}s, ${chunked.size} segments, ${chunked.sumOf { it.text.length }} chars")
        report.appendLine()

        // Repeated text, and WHERE it repeats. Clustering near a seam is hypothesis 3; scattered
        // repetition is the model doing what it always does and is not evidence of anything.
        report.appendLine("repeated lines (text seen more than once):")
        chunked.groupBy { it.text }
            .filterValues { it.size > 1 }
            .forEach { (text, hits) ->
                val nearSeam = hits.any { h -> seams.any { kotlin.math.abs(h.startMs - it) < 20_000 } }
                report.appendLine("  x${hits.size} ${if (nearSeam) "NEAR-SEAM" else "scattered "} at ${hits.map { it.startMs / 1000 }}s: ${text.take(60)}")
            }
        report.appendLine()

        // What each transcript says around every seam, so a person can read the join directly.
        seams.forEach { seam ->
            report.appendLine("--- seam at ${seam / 1000}s ---")
            report.appendLine("  whole-file:")
            control.filter { kotlin.math.abs(it.startMs - seam) < 15_000 }
                .forEach { report.appendLine("    [${it.startMs / 1000}s] ${it.text}") }
            report.appendLine("  chunked:")
            chunked.filter { kotlin.math.abs(it.startMs - seam) < 15_000 }
                .forEach { report.appendLine("    [${it.startMs / 1000}s] ${it.text}") }
            report.appendLine()
        }

        File(outputDir(), "chunkseam-report.txt").writeText(report.toString())
        android.util.Log.i("CV:Bench", report.toString())
    }

    private fun writeTranscript(name: String, segments: List<TranscriptSegment>) {
        File(outputDir(), name).writeText(
            segments.joinToString("\n") { "[${it.startMs / 1000}s] ${it.text}" }
        )
    }

    /** Shared storage where it is writable, the app's own directory where ColorOS will not allow it. */
    private fun outputDir(): File =
        File("/sdcard/Download").takeIf { it.canWrite() } ?: context.getExternalFilesDir(null)!!
}
