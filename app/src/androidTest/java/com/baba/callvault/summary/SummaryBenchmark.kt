/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The measurement this whole spike exists for: can a small local model summarise a real call, in the
 * language it was spoken in, at a cost a phone can pay?
 *
 * Not a pass/fail test. It runs every model it finds against every sample and writes down what
 * happened; a human then reads the summaries and decides. Written as an instrumented test only
 * because that is the cheapest way to run real code on a real phone.
 *
 * The calls are invented rather than real — see [SummarySamples] for why, and for the list of what
 * is actually true in each, which is what makes "did it invent anything" checkable at all.
 *
 * Push one or more `.gguf` files into the isolated build's own files directory and run:
 *
 *     adb push model.gguf /sdcard/Android/data/com.baba.callvault.instrtest/files/
 *     ./gradlew :app:connectedDebugAndroidTest -PisolateTestApp \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.baba.callvault.summary.SummaryBenchmark
 */
@RunWith(AndroidJUnit4::class)
class SummaryBenchmark {

    /** Room for a summary of a chunk. Beyond this the model is padding, not summarising. */
    private val maxTokensPerChunk = 220

    private val maxTokensForMerge = 320

    @Test
    fun measure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // The app's own external files directory, not /sdcard/Download.
        //
        // Under scoped storage an app cannot list Download at all — listFiles returns null and the
        // run skipped without measuring anything, which is how this was found. This directory is
        // readable, writable, visible over adb, and removed when the app is uninstalled.
        // Shared storage first, then the app's own directory.
        //
        // A model is gigabytes and the isolated build is uninstalled after every run, which would
        // take it — and the report — with it. So both live in Download, which survives, and reading
        // them from there is why the debug manifest asks for MANAGE_EXTERNAL_STORAGE. The app's own
        // directory is still searched, for a model pushed there deliberately.
        val shared = File("/sdcard/Download")
        val own = context.getExternalFilesDir(null)
        val models = listOfNotNull(shared, own)
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty().toList() }
            .sortedBy { it.length() }

        // Skipped rather than failed when there is no model: a missing model means the measurement
        // was not set up, which is not the same as the code being broken.
        assumeTrue(
            "no .gguf in ${shared.absolutePath} or ${own?.absolutePath} — push a model, and grant " +
                "MANAGE_EXTERNAL_STORAGE if it is in shared storage",
            models.isNotEmpty()
        )

        val report = StringBuilder()
        report.appendLine("CallVault summarisation benchmark")
        report.appendLine("device: ${android.os.Build.MODEL}  android ${android.os.Build.VERSION.SDK_INT}")
        report.appendLine("llama: ${LlamaNative.systemInfo(context.applicationInfo.nativeLibraryDir)}")
        report.appendLine()

        models.forEach { model ->
            SummarySamples.all.forEach { sample ->
                // Twice per sample: once whole, and once with a small chunk limit so the split and
                // merge path is exercised too. Merging is where a summary of a long call is made or
                // lost, and it would otherwise go unmeasured on anything short enough to run here.
                report.appendLine(runOne(context, model, sample, chunkChars = SummaryChunking.DEFAULT_MAX_CHARS))
                report.appendLine()
                report.appendLine(runOne(context, model, sample, chunkChars = 400))
                report.appendLine()
            }
        }

        // Written where it survives the test app being uninstalled, falling back to the app's own
        // directory if shared storage turns out not to be writable.
        val out = runCatching {
            File(shared, "summary-benchmark.txt").apply { writeText(report.toString()) }
        }.getOrElse {
            File(own, "summary-benchmark.txt").apply { writeText(report.toString()) }
        }
        // The path, never the contents.
        println("benchmark written to ${out.absolutePath}")
    }

    private suspend fun runOne(
        context: Context,
        modelFile: File,
        sample: SummarySamples.Sample,
        chunkChars: Int
    ): String {
        val segments = sample.asSegments()
        val chunks = SummaryChunking.chunk(segments, maxChars = chunkChars)
        val language = sample.language

        val lines = StringBuilder()
        lines.appendLine("model:      ${modelFile.name}  (${modelFile.length() / 1_000_000} MB)")
        lines.appendLine("sample:     ${sample.name}")
        lines.appendLine("transcript: ${segments.size} segments, " +
            "${segments.sumOf { it.text.length }} chars, asked for: $language")
        lines.appendLine("chunks:     ${chunks.size} (limit $chunkChars chars)")

        val loadStart = SystemClock.elapsedRealtime()

        return runCatching {
            SummaryEngine.withModel(context, modelFile.absolutePath) { session ->
                val loadMs = SystemClock.elapsedRealtime() - loadStart
                lines.appendLine("load:       ${loadMs} ms")

                val runStart = SystemClock.elapsedRealtime()
                val partial = chunks.mapIndexed { index, chunk ->
                    val prompt = SummaryPrompt.forChunk(chunk, language)
                    val started = SystemClock.elapsedRealtime()
                    val text = session.generate(prompt, maxTokensPerChunk)
                    lines.appendLine(
                        "  chunk ${index + 1}: ${session.countTokens(prompt)} prompt tokens, " +
                            "${SystemClock.elapsedRealtime() - started} ms"
                    )
                    text.trim()
                }

                val summary = if (partial.size == 1) partial.first() else {
                    val started = SystemClock.elapsedRealtime()
                    val merged = session.generate(SummaryPrompt.forMerge(partial, language), maxTokensForMerge)
                    lines.appendLine("  merge:   ${SystemClock.elapsedRealtime() - started} ms")
                    merged.trim()
                }

                lines.appendLine("total:      ${SystemClock.elapsedRealtime() - runStart} ms")
                lines.appendLine("pss while loaded: ${pssWhileLoadedMb()} MB")
                lines.appendLine("--- everything that is actually true in this call ---")
                sample.facts.forEach { lines.appendLine("  - $it") }
                lines.appendLine("--- summary; anything above is fair, anything else is invented ---")
                lines.appendLine(summary)
                lines.toString()
            }
        }.getOrElse { failure ->
            // A model that cannot be loaded or run is a result, not a crash: it is exactly the sort
            // of thing this measurement exists to discover.
            lines.appendLine("FAILED: ${failure.message}")
            lines.toString()
        }
    }

    /**
     * Process PSS right now, read while the weights are still resident.
     *
     * Not a peak — Android exposes no high-water mark, and this is the present moment. Read inside
     * the model's lifetime on purpose: the same call made after the model is freed understates a
     * run by nearly four times.
     */
    private fun pssWhileLoadedMb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024
    }
}
