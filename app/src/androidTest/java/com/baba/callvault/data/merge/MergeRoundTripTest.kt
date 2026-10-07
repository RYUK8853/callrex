/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The gate on the whole merge feature: merge two calls, take them apart again, and prove the audio
 * that comes back is the audio that went in.
 *
 * This has to be an instrumented test. `MediaExtractor` and `MediaMuxer` are native, so Robolectric
 * cannot exercise them, and every claim behind "a merge may delete the originals" rests on what the
 * platform's muxer actually does rather than on what the documentation says.
 *
 * If this test ever fails, **merging must stop deleting originals** until it passes again. That is
 * the entire safety argument, in one place.
 */
@RunWith(AndroidJUnit4::class)
class MergeRoundTripTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "merge-round-trip"
        ).apply { deleteRecursively(); mkdirs() }
    }

    private fun tone(name: String, hz: Double, seconds: Double) =
        MergeTestAudio.writeTone(File(dir, name), hz, seconds)

    private fun concat(inputs: List<File>, output: File): List<AudioConcat.PartBoundary> {
        val streams = inputs.map { MergeTestAudio.readFd(it) }
        val out = MergeTestAudio.writeFd(output).apply { setLength(0) }
        try {
            return AudioConcat.concat(streams.map { it.fd }, out.fd)
        } finally {
            streams.forEach { it.close() }; out.close()
        }
    }

    private fun split(merged: File, cuts: List<AudioSplit.Cut>, outputs: List<File>) {
        val input = MergeTestAudio.readFd(merged)
        val outs = outputs.map { MergeTestAudio.writeFd(it).apply { setLength(0) } }
        try {
            AudioSplit.split(input.fd, cuts, outs.map { it.fd })
        } finally {
            input.close(); outs.forEach { it.close() }
        }
    }

    private fun cutsFrom(boundaries: List<AudioConcat.PartBoundary>) = boundaries.map {
        AudioSplit.Cut(it.frameStart, it.frameCount, it.encoderDelayUs, it.encoderPaddingUs)
    }

    /**
     * Compares two decodings allowing for a constant leading offset.
     *
     * The offset is expected and is the point of the exercise: a container expresses its encoder's
     * priming delay in an edit list, and how much of it a decoder hands back differs between the
     * merged stream and a standalone part. What must NOT differ is the audio once aligned.
     */
    private fun assertSameAudio(expected: ShortArray, actual: ShortArray, label: String) {
        val maxShift = MergeTestAudio.SAMPLE_RATE / 10 // 100 ms is far more than any priming delay
        var bestShift = 0
        var bestScore = Long.MAX_VALUE
        val window = 4_000
        val from = 2_000
        for (shift in -maxShift..maxShift) {
            if (from + shift < 0 || from + shift + window > actual.size) continue
            if (from + window > expected.size) continue
            var score = 0L
            for (i in from until from + window) score += abs(expected[i] - actual[i + shift]).toLong()
            if (score < bestScore) { bestScore = score; bestShift = shift }
        }
        val n = minOf(expected.size, actual.size - bestShift) - maxOf(0, -bestShift)
        if (n <= 0) fail("$label: nothing to compare (expected=${expected.size} actual=${actual.size})")
        var differing = 0
        var worst = 0
        for (i in 0 until n) {
            val e = expected[i]
            val a = actual[i + bestShift]
            if (e != a) { differing++; worst = maxOf(worst, abs(e - a)) }
        }
        val pct = 100.0 * differing / n
        assertTrue(
            "$label: $differing of $n samples differ (${"%.4f".format(pct)}%), worst delta $worst, " +
                "alignment shift $bestShift samples",
            differing == 0
        )
    }

    @Test
    fun merging_two_calls_keeps_every_frame() {
        // Arrange
        val a = tone("a.m4a", 440.0, 5.0)
        val b = tone("b.m4a", 880.0, 3.0)
        val framesA = MergeTestAudio.frameCount(a)
        val framesB = MergeTestAudio.frameCount(b)

        // Act
        val merged = File(dir, "merged.m4a")
        val boundaries = concat(listOf(a, b), merged)

        // Assert — nothing is dropped at the seam, and the manifest describes where things landed
        assertEquals(framesA + framesB, MergeTestAudio.frameCount(merged))
        assertEquals(0, boundaries[0].frameStart)
        assertEquals(framesA, boundaries[0].frameCount)
        assertEquals(framesA, boundaries[1].frameStart)
        assertEquals(framesB, boundaries[1].frameCount)
        assertEquals(0L, boundaries[0].startTimeUs)
        assertTrue("second part must start after the first", boundaries[1].startTimeUs > 0)
    }

    @Test
    fun un_merging_returns_the_original_audio_sample_for_sample() {
        // Arrange
        val a = tone("a.m4a", 440.0, 5.0)
        val b = tone("b.m4a", 880.0, 3.0)
        val originalA = MergeTestAudio.decode(a)
        val originalB = MergeTestAudio.decode(b)

        val merged = File(dir, "merged.m4a")
        val boundaries = concat(listOf(a, b), merged)

        // Act
        val backA = File(dir, "back-a.m4a")
        val backB = File(dir, "back-b.m4a")
        split(merged, cutsFrom(boundaries), listOf(backA, backB))

        // Assert — this is the claim that lets a merge delete the originals
        assertSameAudio(originalA, MergeTestAudio.decode(backA), "part A")
        assertSameAudio(originalB, MergeTestAudio.decode(backB), "part B")
    }

    @Test
    fun three_calls_survive_the_round_trip_in_the_order_they_were_ticked() {
        // Not chronological: the middle tone is the one the user ticked first. The merge must honour
        // the caller's order rather than sorting behind their back.
        val a = tone("a.m4a", 440.0, 2.0)
        val b = tone("b.m4a", 880.0, 3.0)
        val c = tone("c.m4a", 1320.0, 1.5)
        val originals = listOf(b, a, c).map { MergeTestAudio.decode(it) }

        val merged = File(dir, "merged3.m4a")
        val boundaries = concat(listOf(b, a, c), merged)
        assertEquals(listOf(0, boundaries[1].frameStart, boundaries[2].frameStart), boundaries.map { it.frameStart })

        val backs = listOf("back-0.m4a", "back-1.m4a", "back-2.m4a").map { File(dir, it) }
        split(merged, cutsFrom(boundaries), backs)

        originals.forEachIndexed { i, expected ->
            assertSameAudio(expected, MergeTestAudio.decode(backs[i]), "part $i")
        }
    }

    @Test
    fun a_merged_recording_can_itself_be_merged_and_still_come_apart() {
        // Chaining, flattened: [a,b] then + c must un-merge to a, b, c — not to "the merge" and c.
        val a = tone("a.m4a", 440.0, 2.0)
        val b = tone("b.m4a", 880.0, 2.5)
        val c = tone("c.m4a", 1320.0, 1.5)
        val originals = listOf(a, b, c).map { MergeTestAudio.decode(it) }

        val first = File(dir, "first.m4a")
        val firstBoundaries = concat(listOf(a, b), first)

        val second = File(dir, "second.m4a")
        val secondBoundaries = concat(listOf(first, c), second)

        // Flatten: the parts of `first` keep their offsets, c appends after them.
        val flattened = listOf(
            AudioSplit.Cut(firstBoundaries[0].frameStart, firstBoundaries[0].frameCount,
                firstBoundaries[0].encoderDelayUs, firstBoundaries[0].encoderPaddingUs),
            AudioSplit.Cut(firstBoundaries[1].frameStart, firstBoundaries[1].frameCount,
                firstBoundaries[1].encoderDelayUs, firstBoundaries[1].encoderPaddingUs),
            AudioSplit.Cut(secondBoundaries[1].frameStart, secondBoundaries[1].frameCount,
                secondBoundaries[1].encoderDelayUs, secondBoundaries[1].encoderPaddingUs),
        )

        val backs = listOf("f0.m4a", "f1.m4a", "f2.m4a").map { File(dir, it) }
        split(second, flattened, backs)

        originals.forEachIndexed { i, expected ->
            assertSameAudio(expected, MergeTestAudio.decode(backs[i]), "flattened part $i")
        }
    }

    @Test
    fun recordings_encoded_differently_are_refused_rather_than_silently_re_encoded() {
        // Arrange — same tone, different sample rate. Frames from these two do not mean the same
        // thing, so appending one to the other would produce a file that plays at the wrong speed.
        val a = tone("a.m4a", 440.0, 1.0)
        val odd = MergeTestAudio.writeTone(File(dir, "odd.m4a"), 440.0, 1.0, rate = 44_100)

        // Act / Assert
        try {
            concat(listOf(a, odd), File(dir, "bad.m4a"))
            fail("A merge across two sample rates must be refused, not silently produced")
        } catch (e: MergeFormat.Incompatible) {
            assertTrue(
                "the message has to name both formats, since the user has to decide what to do: ${e.message}",
                e.message!!.contains("48000") && e.message!!.contains("44100")
            )
        }
    }

    @Test
    fun identical_formats_are_not_refused() {
        // The guard must not be so eager that it blocks the ordinary case it exists to protect.
        val a = tone("a.m4a", 440.0, 1.0)
        val b = tone("b.m4a", 880.0, 1.0)
        concat(listOf(a, b), File(dir, "ok.m4a"))
    }
}
