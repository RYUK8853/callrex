/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baba.callvault.transcription.model.ModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What happens when a phone is asked to load a model it cannot hold.
 *
 * The requirements dialog states the cost and leaves the decision to the user, which is the right
 * call — but it means people **will** try it on phones with less memory than the model wants, and
 * the feature has to degrade honestly rather than take the app down with it. The failure has to be
 * one the app can catch and report.
 *
 * Deliberately not `assert`-heavy about the outcome: on a phone with room the model simply loads,
 * and on one without it must fail cleanly. Both are passes. What is not a pass is the process
 * disappearing, and that shows up as the instrumentation dying rather than as a failed assertion.
 *
 * Run as the app itself, not under `-PisolateTestApp`, because the model lives in the app's own
 * private files directory and the isolated test app has a different one.
 */
@RunWith(AndroidJUnit4::class)
class SummaryModelLoadInstrumentedTest {

    @Test
    fun loads_the_model_or_fails_without_taking_the_app_with_it() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = SummaryModel.DEFAULT
        val path: File? = ModelRepository.pathFor(context, model)

        assumeTrue("The summariser is not installed on this device; nothing to load", path != null)

        val totalRamMb = totalRamMb()
        val neededMb = model.peakMemoryBytes / 1_000_000
        Log.i(TAG, "RAM $totalRamMb MB, model wants about $neededMb MB")

        val outcome = runCatching {
            SummaryEngine.withModel(context, path!!.absolutePath) { session ->
                // Tokenising touches the vocabulary without generating, so it proves the context is
                // real without spending minutes of emulator CPU on a summary nobody will read.
                session.countTokens("hello")
            }
        }

        outcome.fold(
            onSuccess = { tokens ->
                Log.i(TAG, "Loaded and tokenised: $tokens token(s)")
                assertTrue("A loaded model must tokenise something", tokens > 0)
            },
            onFailure = { error ->
                // The contract: a phone that cannot hold the model gets an exception the caller can
                // show, not a dead process.
                Log.w(TAG, "Refused to load, which is the honest outcome here: ${error.message}")
                assertTrue(
                    "A failure to load must be reportable, not an Error: $error",
                    error is IllegalStateException || error is RuntimeException
                )
            }
        )
    }

    /**
     * Whether a phone that cannot hold the model can still *generate* with it.
     *
     * Loading proves less than it looks like. llama.cpp memory-maps the weights, so the file never
     * has to fit in RAM to open — the kernel pages it in on demand. Generation is the part that
     * walks the whole model for every single token, and on a device without room for it that means
     * paging from storage continuously.
     *
     * Eight tokens, because the question is only whether tokens come out at all. A device that
     * cannot do this in the runner's timeout has answered it.
     */
    @Test
    fun generates_at_least_a_few_tokens_or_says_why_not() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = ModelRepository.pathFor(context, SummaryModel.DEFAULT)
        assumeTrue("The summariser is not installed on this device", path != null)

        val started = System.currentTimeMillis()
        val outcome = runCatching {
            SummaryEngine.withModel(context, path!!.absolutePath) { session ->
                session.generate("Reply with one short word.", maxTokens = TINY_TOKEN_BUDGET)
            }
        }
        val elapsed = System.currentTimeMillis() - started

        outcome.fold(
            onSuccess = { text ->
                Log.i(TAG, "Generated ${text.length} chars in ${elapsed}ms on ${totalRamMb()} MB")
                assertTrue("Generation returned nothing at all", text.isNotEmpty())
            },
            onFailure = { error ->
                Log.w(TAG, "Generation refused after ${elapsed}ms: ${error.message}")
                assertTrue("Must be reportable, not an Error: $error", error is RuntimeException)
            }
        )
    }

    /** Total physical RAM in megabytes, read from `/proc/meminfo`. */
    private fun totalRamMb(): Long = runCatching {
        File("/proc/meminfo").readLines()
            .first { it.startsWith("MemTotal:") }
            .filter(Char::isDigit)
            .toLong() / 1024
    }.getOrDefault(0L)

    private companion object {
        const val TAG = "CV:SummaryLoadTest"

        /** Enough to prove tokens come out; far too few to be a summary. */
        const val TINY_TOKEN_BUDGET = 8
    }
}
