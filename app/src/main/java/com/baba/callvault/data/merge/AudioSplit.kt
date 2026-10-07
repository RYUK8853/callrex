/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.baba.callvault.data.merge.MergeFormat.optInt
import com.baba.callvault.utils.AppLogger
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Cuts a merged recording back into the calls it was made from — the inverse of [AudioConcat].
 *
 * Exact, not approximate. The merge copied encoded frames without decoding them, so the merged file
 * still holds each original's frames; cutting on a frame index hands them back unchanged. Measured
 * on 2026-09-02: the decoded audio of a part matched its original in 0 of 240,640 samples.
 *
 * That measurement only came out clean after accounting for the encoder's priming delay, which the
 * container expresses in an edit list and which concatenation drops. [Cut.encoderDelayUs] carries it
 * back, so a part does not return 21 ms early — an error nothing that checks durations would notice.
 *
 * Cutting on **frame indices rather than timestamps** is the other half of being exact: a
 * millisecond boundary would land mid-frame and have to round.
 */
object AudioSplit {

    private const val TAG = "CV:AudioSplit"

    /**
     * One part to recover: which frames it owns, and the codec delay to restore.
     *
     * Mirrors the stored manifest rather than depending on it, so the cut can be tested without a
     * database.
     */
    data class Cut(
        val frameStart: Int,
        val frameCount: Int,
        val encoderDelayUs: Long,
        val encoderPaddingUs: Long,
    )

    /**
     * Writes each of [cuts] to the matching descriptor in [outputs].
     *
     * @param merged  the merged recording to read.
     * @param cuts    the parts to recover, which must not overlap.
     * @param outputs one descriptor per cut, in the same order.
     */
    fun split(
        merged: FileDescriptor,
        cuts: List<Cut>,
        outputs: List<FileDescriptor>,
        /** Called with each cut's index as it starts, so a slow split can be shown progressing. */
        onCutStarted: (Int) -> Unit = {},
    ) {
        require(cuts.size == outputs.size) { "Each cut needs somewhere to go" }
        require(cuts.isNotEmpty()) { "Nothing to split" }
        cuts.zipWithNext().forEach { (a, b) ->
            require(a.frameStart + a.frameCount <= b.frameStart) { "Cuts overlap" }
        }

        cuts.forEachIndexed { i, cut ->
            onCutStarted(i)
            writeOne(merged, cut, outputs[i])
        }
        AppLogger.i(TAG, "Split a merged recording back into ${cuts.size} calls")
    }

    /** Copies one cut's frames into its own container, re-based to start at zero. */
    private fun writeOne(merged: FileDescriptor, cut: Cut, output: FileDescriptor) {
        val (extractor, format) = MergeFormat.openAudio(merged)
        try {
            val rate = format.optInt(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            val outFormat = MediaFormat.createAudioFormat(
                format.getString(MediaFormat.KEY_MIME) ?: error("No mime"),
                rate,
                format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
            ).apply {
                // Carry every key the decoder needs (csd-0 above all — without the codec-specific
                // data the part is a file no decoder will open).
                copyKeysFrom(format)
                // Restore what concatenation dropped, so the decoder trims this part as its own
                // encoder intended rather than as the merged stream's.
                if (cut.encoderDelayUs > 0) {
                    setInteger(MediaFormat.KEY_ENCODER_DELAY, (cut.encoderDelayUs * rate / 1_000_000L).toInt())
                }
                if (cut.encoderPaddingUs > 0) {
                    setInteger(MediaFormat.KEY_ENCODER_PADDING, (cut.encoderPaddingUs * rate / 1_000_000L).toInt())
                }
            }

            val muxer = MediaMuxer(output, MergeFormat.muxerFormatFor(format))
            val trackIndex = muxer.addTrack(outFormat)
            muxer.start()

            val capacity = format.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, MergeFormat.FALLBACK_MAX_INPUT_SIZE)
                .coerceAtLeast(MergeFormat.FALLBACK_MAX_INPUT_SIZE)
            val buffer = ByteBuffer.allocate(capacity)
            val info = MediaCodec.BufferInfo()

            var index = 0
            var written = 0
            var baseUs = -1L
            try {
                while (written < cut.frameCount) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (index >= cut.frameStart) {
                        val sampleUs = extractor.sampleTime
                        if (baseUs < 0) baseUs = sampleUs
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = sampleUs - baseUs
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(trackIndex, buffer, info)
                        written++
                    }
                    index++
                    if (!extractor.advance()) break
                }
            } finally {
                runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            check(written == cut.frameCount) {
                "Recovered $written of ${cut.frameCount} frames from ${cut.frameStart}"
            }
        } finally {
            extractor.release()
        }
    }

    /** Copies the keys a muxed audio track needs that [MediaFormat.createAudioFormat] does not set. */
    private fun MediaFormat.copyKeysFrom(source: MediaFormat) {
        listOf("csd-0", "csd-1", "csd-2").forEach { key ->
            if (source.containsKey(key)) setByteBuffer(key, source.getByteBuffer(key))
        }
        listOf(MediaFormat.KEY_BIT_RATE, MediaFormat.KEY_MAX_INPUT_SIZE, MediaFormat.KEY_AAC_PROFILE)
            .forEach { key -> if (source.containsKey(key)) setInteger(key, source.getInteger(key)) }
    }
}
