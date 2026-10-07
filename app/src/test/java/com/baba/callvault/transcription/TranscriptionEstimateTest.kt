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
 * How long a transcription will take, and how that answer improves with use.
 *
 * The estimate itself is arithmetic — duration times a real-time factor — so the only thing worth
 * testing is the factor: where it comes from, how a measurement changes it, and which measurements are
 * too absurd to believe.
 */
class TranscriptionEstimateTest {

    private val published = 3.0

    @Test
    fun before_any_run_it_falls_back_to_the_published_factor() {
        // A phone that has never transcribed anything still has to answer the question.
        assertEquals(published, TranscriptionEstimate.blend(stored = null, measured = null, fallback = published), 0.001)
    }

    @Test
    fun the_first_real_measurement_replaces_the_published_guess_outright() {
        // The published figure came from one developer's phone. The moment this phone reports its own
        // speed, that is better evidence than a number measured on different hardware.
        assertEquals(1.4, TranscriptionEstimate.blend(stored = null, measured = 1.4, fallback = published), 0.001)
    }

    @Test
    fun later_measurements_move_the_estimate_without_letting_one_run_dominate() {
        // Runs vary: thermal throttling, a busy phone, a short clip with a long model load. Smoothing
        // keeps one unusual run from making every future estimate wrong.
        val next = TranscriptionEstimate.blend(stored = 2.0, measured = 4.0, fallback = published)

        assertTrue("must move toward the new measurement", next > 2.0)
        assertTrue("must not jump all the way to it", next < 4.0)
    }

    @Test
    fun an_impossible_measurement_is_ignored() {
        // A run that reports transcribing an hour of audio in a second is a bug in the measurement, not
        // a fast phone. Believing it would promise instant transcripts for ever after.
        assertEquals(2.0, TranscriptionEstimate.blend(stored = 2.0, measured = 0.0001, fallback = published), 0.001)
        assertEquals(2.0, TranscriptionEstimate.blend(stored = 2.0, measured = 5000.0, fallback = published), 0.001)
    }

    @Test
    fun measuring_needs_a_real_length_to_divide_by() {
        // Guards against dividing by zero when the container declares no duration.
        assertEquals(null, TranscriptionEstimate.measure(audioMs = 0L, workMs = 5_000L))
        assertEquals(null, TranscriptionEstimate.measure(audioMs = -1L, workMs = 5_000L))
    }

    @Test
    fun measuring_divides_the_time_taken_by_the_length_of_the_audio() {
        assertEquals(3.0, TranscriptionEstimate.measure(audioMs = 60_000L, workMs = 180_000L)!!, 0.001)
    }

    @Test
    fun the_estimate_is_a_fixed_cost_plus_a_rate_per_second_of_audio() {
        // Was audio x factor alone. A run pays for loading an 874 MB model before it looks at any
        // audio, and that cost does not shrink with a shorter call — modelling it as pure rate is
        // what made short clips quote nonsense in issue #26.
        val cost = TranscriptionEstimate.RunCost(loadMs = 5_000L, rtf = 3.0)
        assertEquals(185_000L, TranscriptionEstimate.estimateMs(audioMs = 60_000L, cost = cost))
    }

    @Test
    fun a_clip_shorter_than_whispers_window_is_billed_for_the_whole_window() {
        // whisper pads anything shorter than 30 seconds up to 30 seconds and does the same work on
        // it. A five-second voicemail therefore costs what half a minute costs, and promising
        // otherwise is how the estimate came out far below the truth for short calls.
        val cost = TranscriptionEstimate.RunCost(loadMs = 0L, rtf = 1.0)
        assertEquals(30_000L, TranscriptionEstimate.estimateMs(audioMs = 5_000L, cost = cost))
        assertEquals(30_000L, TranscriptionEstimate.estimateMs(audioMs = 29_000L, cost = cost))
        // Past the window it goes back to scaling with the audio.
        assertEquals(60_000L, TranscriptionEstimate.estimateMs(audioMs = 60_000L, cost = cost))
    }

    @Test
    fun a_short_clip_measures_the_same_speed_as_a_long_one() {
        // The heart of issue #26. The same phone, the same model, the same true speed — measured
        // from a 5-second clip and from a 5-minute one. Dividing by raw audio length made the short
        // clip look 6x slower and that number became the phone's permanent opinion.
        val fromShortClip = TranscriptionEstimate.measure(audioMs = 5_000L, workMs = 30_000L)!!
        val fromLongCall = TranscriptionEstimate.measure(audioMs = 300_000L, workMs = 300_000L)!!

        assertEquals(1.0, fromShortClip, 0.001)
        assertEquals(1.0, fromLongCall, 0.001)
    }

    @Test
    fun the_fixed_cost_is_learned_the_same_cautious_way_as_the_rate() {
        // Nothing measured yet: the published seed stands.
        assertEquals(4_000L, TranscriptionEstimate.blendLoadMs(stored = null, measured = null, fallback = 4_000L))
        // A first real measurement beats a figure from someone else's hardware outright.
        assertEquals(9_000L, TranscriptionEstimate.blendLoadMs(stored = null, measured = 9_000L, fallback = 4_000L))
        // Later ones move it without one slow run taking over.
        val next = TranscriptionEstimate.blendLoadMs(stored = 4_000L, measured = 9_000L, fallback = 4_000L)
        assertTrue("must move toward the new measurement", next > 4_000L)
        assertTrue("must not jump all the way to it", next < 9_000L)
    }

    @Test
    fun an_impossible_fixed_cost_is_refused() {
        // A load timed at zero, or at four minutes, is a broken measurement rather than a fast or
        // slow phone. Storing either would poison the estimate exactly as the rate once was.
        assertEquals(4_000L, TranscriptionEstimate.blendLoadMs(stored = 4_000L, measured = 0L, fallback = 4_000L))
        assertEquals(4_000L, TranscriptionEstimate.blendLoadMs(stored = 4_000L, measured = 600_000L, fallback = 4_000L))
    }

    @Test
    fun a_run_whose_setup_was_not_timed_still_yields_a_usable_rate() {
        // Setup time is reported by the engine. A path that never set it reports zero, and treating
        // that as "the load was instant" would inflate the rate for every future estimate — so the
        // work time simply is the whole run, which is what the old code assumed anyway.
        assertEquals(2.0, TranscriptionEstimate.measure(audioMs = 60_000L, workMs = 120_000L)!!, 0.001)
    }

    @Test
    fun an_unbelievable_fallback_is_refused_like_an_unbelievable_measurement() {
        // Defence in depth for issue #26. The clamp above only ever guarded `measured`, and the one
        // caller passed the measurement in as the fallback too — so a factor the clamp had just
        // rejected walked back in through `stored ?: fallback` and was written to preferences. A
        // stored 5000 quotes a two-minute call at about four days.
        //
        // The call site is fixed, but the fallback is now checked as well, so the same mistake
        // cannot be made again by a future caller.
        assertEquals(
            TranscriptionEstimate.DEFAULT_RTF,
            TranscriptionEstimate.blend(stored = null, measured = 5000.0, fallback = 5000.0),
            0.001,
        )
    }

    @Test
    fun an_impossible_first_measurement_leaves_the_published_figure_in_place() {
        // The same situation with the call site behaving: nothing stored, an absurd measurement, and
        // a sane published figure. The published figure must survive.
        assertEquals(
            published,
            TranscriptionEstimate.blend(stored = null, measured = 5000.0, fallback = published),
            0.001,
        )
    }
}
