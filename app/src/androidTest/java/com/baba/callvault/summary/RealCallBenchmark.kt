/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.ui.common.BidiText
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The same measurement, on a real call end to end: real audio, transcribed by whisper with all its
 * mistakes intact, then summarised.
 *
 * Separate from [SummaryBenchmark] because it answers a different question. That one asks whether a
 * model invents, using written calls whose facts are known. This one asks whether any of it survives
 * contact with reality — twenty times the length, and speech that stops, restarts and overlaps.
 *
 * **The recording is named on the command line rather than discovered.** Which of someone's calls
 * gets processed is their choice, not a matter of whichever file sorted first.
 *
 * Everything stays on the device: the transcript and the summary go to a file on the phone, and only
 * timings are ever quoted anywhere else.
 *
 *     adb shell am instrument -w \
 *       -e class com.baba.callvault.summary.RealCallBenchmark \
 *       -e recording "/sdcard/CallRecording/<file>.ogg" \
 *       com.baba.callvault.instrtest.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RealCallBenchmark {

    // 220 cut a real summary off mid-word: "...the speaker asked to wai". A budget that truncates
    // is worse than one that costs a little more, because half a sentence reads as a crash.
    private val maxTokensPerChunk = 420
    private val maxTokensForMerge = 520

    @Test
    fun measure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()

        // Either transcribe a recording, or reuse a transcript already produced from one.
        //
        // Reuse exists because transcribing this call takes ten minutes and summarising it takes
        // ninety seconds. Iterating on the prompt against a fixed transcript is the same experiment
        // eight times faster, and it holds the input still so a change in the output is attributable
        // to the change in the prompt.
        val recordingPath = args.getString("recording")
        val transcriptPath = args.getString("transcript")
        assumeTrue(
            "pass -e recording <path to .ogg> or -e transcript <path to .txt>",
            recordingPath != null || transcriptPath != null
        )

        val shared = File("/sdcard/Download")
        // Logged before the assumptions, because the runner reports a skipped assumption as "OK"
        // with no reason attached — a silent pass that looks identical to a successful measurement.
        val own0 = context.getExternalFilesDir(null)
        android.util.Log.i(
            "CV:Bench",
            "own=${own0?.absolutePath} exists=${own0?.exists()} " +
                "ownFiles=${own0?.listFiles()?.joinToString { it.name }} " +
                "sharedFiles=${File("/sdcard/Download").listFiles()?.size}"
        )
        // By name, not by extension. The phone already had unrelated .bin files in Download from
        // someone else's experiments, and "the first .bin" picked one of those and then blamed
        // whisper for failing to load it. ggml- is whisper.cpp's own naming.
        // Both directories, because which one holds the model depends on the phone. Shared storage
        // needs MANAGE_EXTERNAL_STORAGE, which ColorOS will not let adb grant without a setting the
        // phone's owner has to toggle by hand; the app's own directory always works. Searching only
        // Download is why the OP9 run skipped — and reported OK while measuring nothing.
        val dirs = listOfNotNull(shared, context.getExternalFilesDir(null))
        fun find(match: (File) -> Boolean): File? =
            dirs.flatMap { it.listFiles()?.toList().orEmpty() }.firstOrNull { it.isFile && match(it) }

        val speechModel = find { it.name.startsWith("ggml-") && it.name.endsWith(".bin") }
        val summaryModel = find { it.name.endsWith(".gguf") }
        assumeTrue("no .gguf in ${dirs.joinToString { it.absolutePath }}", summaryModel != null)

        val lines = StringBuilder()
        lines.appendLine("CallVault real-call summarisation benchmark")
        lines.appendLine("summary:    ${summaryModel!!.name}")

        val segments = if (transcriptPath != null) {
            val file = File(transcriptPath)
            assumeTrue("no such transcript: $transcriptPath", file.isFile)
            lines.appendLine("transcript: reused from ${file.name}")
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapIndexed { index, text ->
                    TranscriptSegmentEntry(
                        id = index.toLong(),
                        displayName = file.name,
                        startMs = index * 1_000L,
                        endMs = (index + 1) * 1_000L,
                        text = text
                    )
                }
        } else {
            val recording = File(recordingPath!!)
            assumeTrue("no such recording: $recordingPath", recording.isFile)
            assumeTrue("no ggml-*.bin to transcribe with", speechModel != null)
            lines.appendLine("recording:  ${recording.name}  (${recording.length() / 1024} KB)")
            lines.appendLine("speech:     ${speechModel!!.name}")

            // Auto-detect, as the app's own default does, so the transcript is one a user would get.
            val transcribeStart = SystemClock.elapsedRealtime()
            val spoken = TranscriptionEngine.transcribe(
                context = context,
                uri = Uri.fromFile(recording),
                modelPath = speechModel.absolutePath,
                language = null
            )
            lines.appendLine(
                "transcribe: ${SystemClock.elapsedRealtime() - transcribeStart} ms for ${spoken.size} segments"
            )
            assumeTrue("whisper produced nothing", spoken.isNotEmpty())
            spoken.mapIndexed { index, segment ->
                TranscriptSegmentEntry(
                    id = index.toLong(),
                    displayName = recording.name,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    text = segment.text
                )
            }
        }
        val chars = segments.sumOf { it.text.length }
        val language = if (BidiText.isRtl(segments.joinToString(" ") { it.text })) "he" else "en"
        lines.appendLine("transcript: $chars chars, detected as $language")

        val chunks = SummaryChunking.chunk(segments)
        lines.appendLine("chunks:     ${chunks.size}")

        val summary = SummaryEngine.withModel(context, summaryModel.absolutePath) { session ->
            val runStart = SystemClock.elapsedRealtime()
            val partial = chunks.mapIndexed { index, chunk ->
                val prompt = if (args.getString("json") != null) {
                    SummaryPrompt.forChunkJson(chunk, language, withTimestamps = true)
                } else {
                    SummaryPrompt.forChunk(chunk, language)
                }
                val started = SystemClock.elapsedRealtime()
                val text = session.generate(
                    prompt = prompt,
                    maxTokens = maxTokensPerChunk,
                    grammar = if (args.getString("json") != null) SummaryGrammar.JSON else null
                )
                lines.appendLine(
                    "  chunk ${index + 1}: ${session.countTokens(prompt)} prompt tokens, " +
                        "${SystemClock.elapsedRealtime() - started} ms"
                )
                text.trim()
            }
            val merged = if (partial.size == 1) partial.first() else {
                val started = SystemClock.elapsedRealtime()
                val text = session.generate(SummaryPrompt.forMerge(partial, language), maxTokensForMerge)
                lines.appendLine("  merge:   ${SystemClock.elapsedRealtime() - started} ms")
                text.trim()
            }
            lines.appendLine("summarise:  ${SystemClock.elapsedRealtime() - runStart} ms")
            // Read here, with the weights still resident. Taken after withModel returns it measures
            // the process AFTER they are freed — which reported 1088 MB for a run that `top` showed
            // at 4.0 GB, an understatement of nearly four times.
            //
            // Still not a peak: Debug.getMemoryInfo reports the present moment and Android exposes
            // no high-water mark. It is a reading during the heaviest phase, and is named as such.
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            lines.appendLine("pss while loaded: ${info.totalPss / 1024} MB")
            merged
        }

        // The numbers go in a file anyone may read. The words go in a file only the phone's owner
        // should — this is a real conversation, not an invented one.
        File(shared, "real-call-metrics.txt").writeText(lines.toString())
        File(shared, "real-call-summary.txt").writeText(
            "SUMMARY\n\n$summary\n\n\nTRANSCRIPT\n\n" +
                segments.joinToString("\n") { "${it.startMs / 1000}s  ${it.text}" }
        )
    }
}
