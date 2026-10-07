/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * How long transcribing a recording will take on *this* phone.
 *
 * The estimate is arithmetic — the call's length times a real-time factor — so there is nothing to
 * compute and nothing to show a progress bar for. What actually decides whether the answer is any good
 * is the factor, and that varies by model and by hardware: the published figures were measured on one
 * device, and a slower phone would be told a number that is wrong by more than double.
 *
 * So every finished run reports what it really cost, and the factor for that model is nudged toward it.
 * The first estimate on a new phone uses the published figure; from the second run onward it is that
 * phone's own measured speed.
 */
object TranscriptionEstimate {

    /**
     * How much a single measurement moves the stored factor.
     *
     * Runs vary — thermal throttling, a busy phone, a short clip that pays a fixed model-load cost —
     * so one unusual run must not make every later estimate wrong.
     */
    private const val SMOOTHING = 0.3

    /** Factors outside this range are measurement bugs, not fast or slow phones. */
    private val BELIEVABLE = 0.05..50.0

    /**
     * Used when neither the measurement nor the published figure can be believed.
     *
     * Roughly the middle of the tiers this app ships, so a wrong answer here is wrong by a factor of
     * two rather than by an order of magnitude. It exists only so that [blend] can always return
     * something sane; reaching it means a caller passed nonsense.
     */
    const val DEFAULT_RTF = 2.0

    /**
     * whisper pads anything shorter than this up to it and does a full window of work regardless.
     *
     * So a five-second voicemail costs what half a minute costs. Dividing by the raw audio length
     * made that clip look six times slower than the phone really is, and — before the fallback hole
     * was closed — that reading became the phone's permanent opinion of itself. It is the reason
     * issue #26 saw hours quoted for calls of a few seconds.
     */
    const val WHISPER_WINDOW_MS = 30_000L

    /** Loads outside this range are broken measurements, not fast or slow storage. */
    private val BELIEVABLE_LOAD_MS = 200L..300_000L

    /** What a run actually costs on this phone: a fixed cost, plus a rate per second of audio. */
    data class RunCost(val loadMs: Long, val rtf: Double)

    /** The audio a run is really charged for — never less than one whisper window. */
    fun billableMs(audioMs: Long): Long = maxOf(audioMs, WHISPER_WINDOW_MS)

    /**
     * Observed factor for a run, or null when the audio length was not known well enough to divide by.
     *
     * [workMs] is the run WITHOUT its setup — the model load is charged separately, because it does
     * not shrink when the call is shorter. A caller that cannot separate the two passes the whole
     * elapsed time, which is what this measured before and is still safe, only pessimistic.
     */
    fun measure(audioMs: Long, workMs: Long): Double? {
        if (audioMs <= 0L) return null
        return workMs.toDouble() / billableMs(audioMs).toDouble()
    }

    /**
     * The fixed cost to estimate with, given what is stored and what was just measured.
     *
     * Same shape and the same caution as [blend], because it fails the same way: one broken reading
     * written straight to preferences is quoted back at the user for the next dozen runs.
     */
    fun blendLoadMs(stored: Long?, measured: Long?, fallback: Long): Long {
        val believable = measured?.takeIf { it in BELIEVABLE_LOAD_MS }
        val safeFallback = fallback.takeIf { it in BELIEVABLE_LOAD_MS } ?: BELIEVABLE_LOAD_MS.first
        return when {
            believable == null -> stored ?: safeFallback
            stored == null -> believable
            else -> (stored * (1 - SMOOTHING) + believable * SMOOTHING).toLong()
        }
    }

    /**
     * The factor to estimate with, given what is stored and what was just measured.
     *
     * @param stored   this phone's factor so far, or null if it has never transcribed anything.
     * @param measured the factor from the run that just finished, or null when there was none.
     * @param fallback the model's published factor, used only until this phone has measured its own.
     */
    fun blend(stored: Double?, measured: Double?, fallback: Double): Double {
        val believable = measured?.takeIf { it in BELIEVABLE }
        // The fallback is held to the same standard as the measurement, and that is not paranoia:
        // the one real caller used to pass `measured` in as `fallback`, so a factor this function
        // had just rejected came straight back through the `stored ?: fallback` branch below and was
        // written to preferences. That is issue #26's "about 3 hours" for a two-minute call. The
        // call site is fixed; this makes the same mistake impossible to repeat.
        val safeFallback = fallback.takeIf { it in BELIEVABLE } ?: DEFAULT_RTF
        return when {
            believable == null -> stored ?: safeFallback
            // The published figure came from different hardware; the first real measurement here beats
            // it outright rather than being averaged with it.
            stored == null -> believable
            else -> stored * (1 - SMOOTHING) + believable * SMOOTHING
        }
    }

    /** How long a run over [audioMs] of audio will take, at this phone's measured [cost]. */
    fun estimateMs(audioMs: Long, cost: RunCost): Long =
        cost.loadMs + (billableMs(audioMs) * cost.rtf).toLong()
}
