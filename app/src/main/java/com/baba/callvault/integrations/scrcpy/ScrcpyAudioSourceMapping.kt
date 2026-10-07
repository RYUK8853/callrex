/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.scrcpy

import android.media.MediaRecorder

/**
 * The [MediaRecorder.AudioSource] a [ScrcpyAudioSource] can be captured with directly, or `null` when
 * only scrcpy can capture it.
 *
 * This is the single gate for both non-scrcpy capture paths — the daemon's direct `AudioRecord`
 * recorder ([com.baba.callvault.server.DirectAudioRecorderSession]) and the "Resilient recording"
 * handoff ([com.baba.callvault.services.recording.handoff.HandoffSource]) — so the two can never
 * disagree about which sources they support.
 *
 * `null` covers the sources that are NOT a plain microphone-style input: `output`/`playback` are
 * playback-capture (scrcpy-only), and the remaining mic variants are deliberately left to scrcpy
 * rather than silently switching their capture path.
 */
val ScrcpyAudioSource.androidAudioSource: Int?
    get() = when (cliKey) {
        "voice-call" -> MediaRecorder.AudioSource.VOICE_CALL
        "mic-voice-communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        "mic" -> MediaRecorder.AudioSource.MIC
        "mic-voice-recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        "voice-performance" -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
        else -> null
    }

/** Resolves a scrcpy `audio_source` cliKey to its direct [MediaRecorder.AudioSource], or null. */
fun androidAudioSourceForKey(cliKey: String): Int? =
    ScrcpyAudioSource.entries.firstOrNull { it.cliKey == cliKey }?.androidAudioSource
