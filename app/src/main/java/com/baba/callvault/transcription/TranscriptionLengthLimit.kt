/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * How long a recording may be before on-device transcription refuses to start.
 *
 * **Why a refusal rather than a best effort.** Transcription decodes the whole recording into memory as
 * 16 kHz mono floats before the model sees any of it, so cost grows with length until the heap runs out
 * — and what the user gets is a crash or a job that dies silently, after a long wait, having produced
 * nothing. Refusing immediately with a reason is a strictly better outcome than trying and dying.
 *
 * **This is a stopgap and is meant to be deleted.** The real fix is to stop decoding the whole file at
 * once — streaming or chunked decode — at which point the limit can go. Agreed 2026-08-25: ship the
 * refusal now, do the decode work later.
 *
 * 🚨 When that chunked decode is written: whisper's own `offset_ms`/`duration_ms` are **not** the route
 * to it. `whisper_full` builds the mel for the whole file *before* those parameters are read, so a loop
 * over them costs full memory on every pass and recomputes the mel each time. Slice the PCM before
 * calling whisper instead. Confirmed twice, independently, at the source.
 *
 * The threshold is a duration rather than a file size because size depends on codec and bit rate, while
 * the decoded buffer — the thing that actually exhausts the heap — depends only on length.
 *
 * **Where it is enforced.** [TranscriptionRunner.runOne] is the funnel every route reaches — nightly
 * sweep, per-call run, and tap alike — so that is where the work is actually refused; a check at the
 * tap alone is how the automatic paths came to attempt recordings this exists to reject.
 * [TranscriptionQueue.pending] excludes them as well, because a recording nothing will ever transcribe
 * would otherwise hold one of a nightly run's limited slots for ever. The tap keeps its own check on
 * top of both: only there is there anyone to tell.
 */
object TranscriptionLengthLimit {

    /**
     * Recordings longer than this are refused.
     *
     * **15 → 20 on 2026-08-27**, after the decode path stopped making two full-length copies of every
     * call. The old 15 was not chosen — it was found, empirically, at the point transcription started
     * failing, and what it was really measuring was `AudioDecoder` widening the whole call to float32
     * while still at 48 kHz. That allocation is gone, and so is the byte staging in `decodePcm`.
     *
     * 📐 The new number is **arithmetic, not a measurement**: peak is now about **9.6 MB per minute**
     * — 5.76 MB of interleaved PCM plus 3.84 MB of 16 kHz float output — so 15 minutes costs 144 MB
     * and 20 costs 192 MB against a 256 MB heap. 25 would be 240 MB, which leaves nothing for the app
     * itself and is why this is 20 and not higher.
     *
     * **20 → 60 on 2026-08-28, once transcription stopped decoding the whole call at once.** With
     * chunked passes the peak no longer depends on call length at all — it is bounded by one chunk,
     * about six minutes — so the old arithmetic that produced 20 no longer applies. Sixty is chosen as
     * a length beyond which a *transcript* stops being the useful artefact, not as a memory ceiling.
     *
     * **If a long call ever dies rather than being refused, this is the number to lower**, and that
     * failure is worth more than the calculation: the honest ceiling can only be found by a real call.
     */
    const val MAX_MINUTES: Int = 60

    private const val MAX_MS: Long = MAX_MINUTES * 60L * 1000L

    /**
     * Whether a recording of [durationMs] is too long to transcribe.
     *
     * **Unknown length is allowed through.** `AudioDecoder.durationMs` returns 0 when the container
     * declares no duration, and a VoIP recording's length is not always in the call log either. Refusing
     * on "unknown" would block ordinary short recordings whose metadata happens to be missing, which is
     * a worse failure than letting a rare long one through to the limit that already exists — the heap.
     */
    fun isTooLong(durationMs: Long): Boolean = durationMs > MAX_MS

    /** As [isTooLong], for the call-log duration in seconds (null when it is not known). */
    fun isTooLong(durationSeconds: Long?): Boolean =
        durationSeconds != null && isTooLong(durationSeconds * 1000L)
}
