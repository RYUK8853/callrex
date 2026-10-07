/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.baba.callvault.utils.AppLogger

/**
 * What the device's chosen encoder will actually accept.
 *
 * Nothing used to check this: `hasEncoder()` confirmed only that *an encoder for the MIME exists*, and
 * the user's bit rate went straight into `KEY_BIT_RATE`. A `MediaCodec` handed an out-of-range value
 * does not reliably throw — it can clamp, or emit frames that decode to nothing, producing a
 * correctly-sized recording that plays silent. That is indistinguishable from the symptom in issue
 * #18, which closed without a cause.
 *
 * Encoders genuinely differ, measured on a OnePlus 12 (2026-07-31):
 *
 * | encoder | bit rate | sample rate | channels |
 * |---|---|---|---|
 * | `c2.android.aac.encoder` (software) | 8000-960000 | discrete list incl. 48000 | ≤ 6 |
 * | `c2.qti.aac.hw.encoder` (hardware)  | 4000-192000 | 8000-48000 | ≤ 2 |
 *
 * On that device the hardware one declares `special-codec required`, so `createEncoderByType` does not
 * pick it. A vendor that does not gate it that way hands us an encoder with different limits, and
 * before this we would never have known.
 */
internal object EncoderLimits {

    private const val TAG = "CV:EncoderLimits"

    /**
     * The requested rate brought inside `[min, max]`, or returned untouched when the range is missing
     * or nonsensical.
     *
     * Refusing to invent a limit matters: an encoder that reports nothing usable must leave behaviour
     * exactly as it was before this check existed, rather than having a guess imposed on it.
     */
    fun clampBitRate(requested: Int, min: Int?, max: Int?): Int {
        if (min == null || max == null) return requested
        if (min <= 0 || max <= 0 || min > max) return requested
        return requested.coerceIn(min, max)
    }

    /**
     * Logs what the encoder for [mime] supports and returns [requested] clamped into its bit-rate
     * range.
     *
     * The log line is half the point. Issue #18 cost a week partly because nothing ever recorded which
     * encoder ran or what it would accept, so no bug report could answer it. One line per recording
     * makes the next such report answerable immediately.
     */
    fun resolveBitRate(mime: String, requested: Int, sampleRate: Int, channels: Int): Int {
        val caps = runCatching {
            infoFor(mime, sampleRate, channels)?.let { it.name to it.getCapabilitiesForType(mime).audioCapabilities }
        }.getOrNull()

        if (caps?.second == null) {
            AppLogger.w(TAG, "No audio capabilities for $mime — using the requested ${requested} bps unchecked")
            return requested
        }
        val (name, audio) = caps.first to caps.second!!
        val min = runCatching { audio.bitrateRange.lower }.getOrNull()
        val max = runCatching { audio.bitrateRange.upper }.getOrNull()
        val rateOk = runCatching { audio.isSampleRateSupported(sampleRate) }.getOrDefault(true)
        val chanOk = runCatching { channels <= audio.maxInputChannelCount }.getOrDefault(true)
        val resolved = clampBitRate(requested, min, max)

        AppLogger.i(
            TAG,
            "encoder=$name mime=$mime bitrate=[$min..$max] requested=$requested resolved=$resolved " +
                "sampleRate=$sampleRate supported=$rateOk channels=$channels supported=$chanOk",
        )
        if (resolved != requested) {
            AppLogger.w(TAG, "Bit rate $requested bps is outside what $name accepts — using $resolved bps")
        }
        if (!rateOk || !chanOk) {
            // Not fatal here: the caller decides. Recording at an unsupported sample rate or channel
            // count is the shape that produces a full-length silent file, so it must at least be said.
            AppLogger.w(TAG, "$name does not advertise ${sampleRate}Hz/${channels}ch — output may be silent")
        }
        return resolved
    }

    /** True when the encoder for [mime] advertises this sample rate and channel count. */
    fun supportsFormat(mime: String, sampleRate: Int, channels: Int): Boolean = runCatching {
        val audio = infoFor(mime, sampleRate, channels)?.getCapabilitiesForType(mime)?.audioCapabilities ?: return true
        audio.isSampleRateSupported(sampleRate) && channels <= audio.maxInputChannelCount
    }.getOrDefault(true)

    /**
     * The name of the encoder the platform will actually instantiate for this format, or null when it
     * will not name one. Pass it to `MediaCodec.createByCodecName` so the codec that runs is the codec
     * whose limits were read.
     *
     * **Why this exists (issue #28c).** Everything here used to take the FIRST encoder in the list that
     * advertised the MIME type, while the recording created its codec with `createEncoderByType`, which
     * makes its own choice — and one that considers the sample rate and channel count, which the
     * first-in-list never did. On a device shipping several encoders for one MIME (Samsung ships more
     * than one AAC encoder, and exactly one Opus encoder, which is why this could only ever bite AAC)
     * the limits belonged to a codec that was not the one running. The clamp was then computed against
     * the wrong ranges, and a bit rate the real encoder refuses could sail through unchanged.
     */
    fun encoderNameFor(mime: String, sampleRate: Int, channels: Int): String? =
        runCatching { infoFor(mime, sampleRate, channels)?.name }.getOrNull()

    /**
     * The encoder the platform picks for this exact format, falling back to the first that advertises
     * the MIME type when it names none — which is what this file did for every device before.
     */
    private fun infoFor(mime: String, sampleRate: Int, channels: Int): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val chosen = runCatching {
            list.findEncoderForFormat(MediaFormat.createAudioFormat(mime, sampleRate, channels))
        }.getOrNull()
        val infos = list.codecInfos
        return infos.firstOrNull { it.name == chosen }
            ?: infos.firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
    }
}
