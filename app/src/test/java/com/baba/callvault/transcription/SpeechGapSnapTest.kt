/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #25 — a line stamped a dozen seconds before it was spoken.
 *
 * **The numbers here are not invented.** They were measured on 2026-09-06 by running the reporter's own
 * recording (`callvault-issue25-28-sample.ogg`) through whisper.cpp with CallVault's exact decode and
 * VAD settings, and reading the segment and VAD-stretch tables out of the library directly.
 */
class SpeechGapSnapTest {

    /** The stretches Silero kept on the reporter's call, in original time. */
    private val reportersSpeech = listOf(
        0.98 to 2.10, 2.90 to 5.87, 7.44 to 38.96, 40.62 to 56.59, 57.46 to 58.67,
        60.14 to 69.81, 74.54 to 77.04, 88.53 to 93.07, 97.71 to 99.76,
    ).map { (from, to) -> SpeechGapSnap.Speech((from * 1000).toLong(), (to * 1000).toLong()) }

    private fun seg(startMs: Long, endMs: Long, text: String = "hello") =
        TranscriptSegment(startMs = startMs, endMs = endMs, text = text)

    @Test
    fun `the reporter case — the line stamped at 76 belongs at 88`() {
        // Measured: whisper stamped "Thank you so much for connecting…" at 76.52s, while speech
        // actually resumes at 88.53s. Note 76.52s is INSIDE the previous stretch (74.54-77.04), which
        // is why moving only starts-that-land-in-a-gap would have done nothing for him.
        val snapped = SpeechGapSnap.apply(listOf(seg(76_520, 99_750)), reportersSpeech)
        assertEquals(88_530, snapped.single().startMs)
        assertEquals("the end was already right and must not move", 99_750, snapped.single().endMs)
    }

    @Test
    fun `the two lines before the pause are left exactly alone`() {
        // Both begin comfortably inside stretch 5 (60.14-69.81), so nothing about them is suspect.
        val before = listOf(seg(61_230, 66_850, "qualifies"), seg(66_850, 76_520, "finalize"))
        assertEquals(before, SpeechGapSnap.apply(before, reportersSpeech))
    }

    @Test
    fun `a start stranded in a removed pause moves to where speech resumes`() {
        val snapped = SpeechGapSnap.apply(listOf(seg(80_000, 92_000)), reportersSpeech)
        assertEquals(88_530, snapped.single().startMs)
    }

    @Test
    fun `an end stranded in a pause is pulled back to where speech stopped`() {
        val snapped = SpeechGapSnap.apply(listOf(seg(60_500, 80_000)), reportersSpeech)
        assertEquals(60_500, snapped.single().startMs)
        assertEquals("a line cannot run on through silence that was never there", 77_040, snapped.single().endMs)
    }

    @Test
    fun `a line that ends before the pause keeps its start`() {
        // Begins in the last second of stretch 6 but does NOT run past the pause, so the seam rule
        // must not touch it — this is the case the heuristic could most easily get wrong.
        val kept = listOf(seg(76_600, 77_000, "okay"))
        assertEquals(kept, SpeechGapSnap.apply(kept, reportersSpeech))
    }

    @Test
    fun `a segment never comes back inverted`() {
        val snapped = SpeechGapSnap.apply(listOf(seg(78_000, 85_000)), reportersSpeech)
        val s = snapped.single()
        assertTrue("start ${s.startMs} must precede end ${s.endMs}", s.endMs > s.startMs)
    }

    @Test
    fun `nothing is touched when the VAD kept nothing`() {
        // VAD off, or a device that reported no stretches: leave whisper's numbers exactly as they are
        // rather than inventing a correction with no idea where the speech was.
        val original = listOf(seg(76_520, 99_750))
        assertEquals(original, SpeechGapSnap.apply(original, emptyList()))
    }

    @Test
    fun `text and order survive`() {
        val snapped = SpeechGapSnap.apply(
            listOf(seg(61_230, 66_850, "first"), seg(76_520, 99_750, "second")),
            reportersSpeech,
        )
        assertEquals(listOf("first", "second"), snapped.map { it.text })
        assertEquals(61_230, snapped[0].startMs)
        assertEquals(88_530, snapped[1].startMs)
    }
}
