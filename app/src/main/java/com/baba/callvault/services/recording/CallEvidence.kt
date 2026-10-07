/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder.AudioSource
import com.baba.callvault.utils.AppLogger

/**
 * Whether anything on the phone is actually behaving like a call — issue #29.
 *
 * **The problem this exists for.** [VoipCallDetector] has one signal, `MODE_IN_COMMUNICATION`, and that
 * is a *routing* state rather than a call state: any app that wants its audio in the earpiece takes it.
 * Samsung's voicemail app plays through the earpiece, so every voicemail playback looked like an app
 * call starting, failed to record, and told the user a call had been missed — writing an unexplained
 * gap into the health record each time.
 *
 * **The evidence used instead.** A two-way call means somebody is *capturing the microphone*. Playback
 * captures nothing. `AudioManager.getActiveRecordingConfigurations()` is documented as a device-wide
 * view — *"a general view of all active recordings on the device"* — and, measured on an OP12 on
 * 2026-09-06 from an ordinary (unprivileged) app process, it reports other apps' sessions and does
 * **not** redact `clientAudioSource`. So this needs no daemon, no privilege and no new permission,
 * which matters: the daemon being absent is exactly the case that produced the false warning.
 *
 * | measured state | recording configurations seen |
 * |---|---|
 * | audio merely playing | none |
 * | a real WhatsApp call | `VOICE_COMMUNICATION` (theirs) + `MIC` (ours) |
 * | idle | none, or a stray `MIC` session |
 *
 * **Why the source matters and a count does not.** Our own VoIP capture opens [AudioSource.MIC] and
 * appears in the same list, so counting sessions would let our recording confirm itself; and a stray
 * background `MIC` session was observed twice on an idle phone. Only a communication-type source is
 * evidence of a call.
 *
 * **`TelecomManager.isInCall()` is deliberately not used.** It reads false for the entire duration of a
 * real WhatsApp call on this phone — WhatsApp registers no self-managed `ConnectionService` — so it is
 * not even a positive signal, let alone a safe one. Do not reintroduce it.
 */
object CallEvidence {

    private const val TAG = "CV:CallEvidence"

    /** Sources an app uses when it is on a call, as opposed to recording a voice note or a video. */
    private val CALL_SOURCES = setOf(
        AudioSource.VOICE_COMMUNICATION,
        AudioSource.VOICE_CALL,
        AudioSource.VOICE_UPLINK,
        AudioSource.VOICE_DOWNLINK,
    )

    /**
     * True when [sources] contains a capture that only a call would open.
     *
     * Null means the device would not answer, and is deliberately **not** treated as "no call": the
     * caller's contract is that a false answer here only ever costs a warning, never a recording.
     */
    fun corroboratesCall(sources: List<Int>?): Boolean = sources?.any { it in CALL_SOURCES } == true

    /**
     * Asks the platform who is capturing right now. Null when the list cannot be read at all.
     *
     * Safe on any thread and cheap — one binder call into AudioService.
     */
    fun activeCaptureSources(context: Context): List<Int>? = runCatching {
        context.getSystemService(AudioManager::class.java)
            ?.activeRecordingConfigurations
            ?.map { it.clientAudioSource }
    }.onFailure { AppLogger.d(TAG, "Could not read active recordings: ${it.message}") }.getOrNull()

    /**
     * Whether the phone currently looks like it is on a call, by the evidence above.
     *
     * Use this to decide whether to *say* a call was missed — never to decide whether to record. An app
     * that captures with plain `MIC`, or that opens its capture a moment after the audio mode changes,
     * fails this test, and the cost of that must be a missing warning rather than a missing recording.
     */
    fun looksLikeACall(context: Context): Boolean = corroboratesCall(activeCaptureSources(context))
}
