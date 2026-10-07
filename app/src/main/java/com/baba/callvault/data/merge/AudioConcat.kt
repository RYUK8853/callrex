/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.baba.callvault.data.merge.MergeFormat.optInt
import com.baba.callvault.utils.AppLogger
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Joins several recordings into one by copying their encoded frames, without decoding.
 *
 * This is why a merge is instant and lossless: nothing is re-encoded, so the output physically
 * contains each input's frames unchanged. It is also why [AudioSplit] can hand them back exactly,
 * and therefore why merging is allowed to delete the originals.
 *
 * The seam is a butt-join. The real gap between two calls — the minute spent redialling — is not
 * padded with silence: that would bloat the file to represent nothing. The gap is recorded as a mark
 * instead, which keeps the information and makes the seam navigable.
 */
object AudioConcat {

    private const val TAG = "CV:AudioConcat"

    /** Where one input landed inside the joined stream. */
    data class PartBoundary(
        val frameStart: Int,
        val frameCount: Int,
        val startTimeUs: Long,
        val durationUs: Long,
        val encoderDelayUs: Long,
        val encoderPaddingUs: Long,
    )

    /**
     * Appends [inputs] in the order given and writes them to [output].
     *
     * The order is the caller's — the order the user ticked the calls — and is deliberately not
     * sorted here. Sorting chronologically at this level would quietly override a choice the UI
     * made visible with numbered badges.
     *
     * @return one [PartBoundary] per input, in the same order, for the merge manifest.
     * @throws MergeFormat.Incompatible if the inputs were not recorded the same way.
     */
    fun concat(
        inputs: List<FileDescriptor>,
        output: FileDescriptor,
        /** Called with each input's index as it starts, so a long join can be shown progressing. */
        onPartStarted: (Int) -> Unit = {},
    ): List<PartBoundary> {
        require(inputs.size >= 2) { "A merge needs at least two recordings" }

        val (firstExtractor, firstFormat) = MergeFormat.openAudio(inputs.first())
        firstExtractor.release()

        val muxer = MediaMuxer(output, MergeFormat.muxerFormatFor(firstFormat))
        val trackIndex = muxer.addTrack(firstFormat)
        muxer.start()

        val boundaries = mutableListOf<PartBoundary>()
        var frameCursor = 0
        var timeCursor = 0L
        try {
            inputs.forEachIndexed { index, fd ->
                onPartStarted(index)
                val boundary = appendOne(fd, firstFormat, muxer, trackIndex, frameCursor, timeCursor)
                boundaries += boundary
                frameCursor += boundary.frameCount
                timeCursor = boundary.startTimeUs + boundary.durationUs
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        AppLogger.i(
            TAG,
            "Joined ${inputs.size} recordings: $frameCursor frames, ${timeCursor / 1000} ms, " +
                MergeFormat.describe(firstFormat)
        )
        return boundaries
    }

    /** Copies one input's frames onto the end of the muxer's timeline. */
    private fun appendOne(
        fd: FileDescriptor,
        expected: MediaFormat,
        muxer: MediaMuxer,
        trackIndex: Int,
        frameStart: Int,
        startTimeUs: Long,
    ): PartBoundary {
        val (extractor, format) = MergeFormat.openAudio(fd)
        try {
            MergeFormat.requireCompatible(expected, format)

            val capacity = format.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, MergeFormat.FALLBACK_MAX_INPUT_SIZE)
                .coerceAtLeast(MergeFormat.FALLBACK_MAX_INPUT_SIZE)
            val buffer = ByteBuffer.allocate(capacity)
            val info = MediaCodec.BufferInfo()

            var frames = 0
            var firstSampleUs = -1L
            var lastSampleUs = 0L
            var lastStepUs = 0L

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleUs = extractor.sampleTime
                if (firstSampleUs < 0) firstSampleUs = sampleUs else lastStepUs = sampleUs - lastSampleUs
                lastSampleUs = sampleUs

                info.offset = 0
                info.size = size
                info.presentationTimeUs = startTimeUs + (sampleUs - firstSampleUs)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(trackIndex, buffer, info)

                frames++
                if (!extractor.advance()) break
            }
            require(frames > 0) { "Recording has no audio frames" }

            // The part runs to the end of its LAST frame, not to that frame's start — otherwise
            // every join would swallow one frame's worth of the previous call.
            val durationUs = (lastSampleUs - firstSampleUs) + lastStepUs
            val (delayUs, paddingUs) = MergeFormat.codecDelayUs(format)
            return PartBoundary(
                frameStart = frameStart,
                frameCount = frames,
                startTimeUs = startTimeUs,
                durationUs = durationUs,
                encoderDelayUs = delayUs,
                encoderPaddingUs = paddingUs,
            )
        } finally {
            extractor.release()
        }
    }
}
