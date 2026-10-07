/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthetic recordings for the merge round-trip tests.
 *
 * Deliberately generated rather than taken from the device: these tests must never touch a real
 * call. Tones also make a failure legible — a part that comes back at the wrong offset or from the
 * wrong place is obvious in the samples, where speech would just sound vaguely wrong.
 *
 * The format matches what CallVault actually writes: mono, 48 kHz, AAC-LC.
 */
object MergeTestAudio {

    const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 1
    private const val BIT_RATE = 64_000

    /**
     * Writes a [seconds]-long sine at [hz] as an .m4a, and returns the file.
     *
     * [rate] is a parameter only so a test can build a deliberately incompatible recording; every
     * real CallVault capture path uses [SAMPLE_RATE].
     */
    fun writeTone(into: File, hz: Double, seconds: Double, rate: Int = SAMPLE_RATE): File {
        val pcm = ShortArray((rate * seconds).toInt()) { i ->
            (sin(2.0 * PI * hz * i / rate) * 12_000).toInt().toShort()
        }
        encodeAac(pcm, into, rate)
        return into
    }

    /** Decodes a whole file to mono PCM-16, for sample-for-sample comparison. */
    fun decode(file: File): ShortArray {
        val extractor = MediaExtractor()
        FileInputStream(file).use { extractor.setDataSource(it.fd) }
        val track = MergeFormat.audioTrackOf(extractor)
        check(track >= 0) { "no audio track in ${file.name}" }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Short>()
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        while (!sawOutputEnd) {
            if (!sawInputEnd) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val buf = codec.getOutputBuffer(outIndex)!!
                val shorts = ShortArray(info.size / 2)
                buf.position(info.offset)
                buf.asShortBuffer().get(shorts)
                out.ensureCapacity(out.size + shorts.size)
                shorts.forEach { out.add(it) }
                codec.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
            }
        }
        codec.stop(); codec.release(); extractor.release()
        return out.toShortArray()
    }

    /** How many encoded frames a file holds — the unit the merge counts in. */
    fun frameCount(file: File): Int {
        val extractor = MediaExtractor()
        FileInputStream(file).use { extractor.setDataSource(it.fd) }
        val track = MergeFormat.audioTrackOf(extractor)
        extractor.selectTrack(track)
        val buf = ByteBuffer.allocate(MergeFormat.FALLBACK_MAX_INPUT_SIZE)
        var n = 0
        while (extractor.readSampleData(buf, 0) >= 0) { n++; if (!extractor.advance()) break }
        extractor.release()
        return n
    }

    /** Read a file for MediaExtractor. */
    fun readFd(file: File): FileInputStream = FileInputStream(file)

    /** Write a file for MediaMuxer, which needs a seekable descriptor. */
    fun writeFd(file: File): RandomAccessFile = RandomAccessFile(file, "rw")

    private fun encodeAac(pcm: ShortArray, into: File, rate: Int = SAMPLE_RATE) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val raf = RandomAccessFile(into, "rw")
        raf.setLength(0)
        val muxer = MediaMuxer(raf.fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false

        val info = MediaCodec.BufferInfo()
        var offset = 0
        var sawInputEnd = false
        var sawOutputEnd = false
        while (!sawOutputEnd) {
            if (!sawInputEnd) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    buf.clear()
                    val room = buf.capacity() / 2
                    val n = minOf(room, pcm.size - offset)
                    if (n <= 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEnd = true
                    } else {
                        buf.asShortBuffer().put(pcm, offset, n)
                        val ptsUs = offset.toLong() * 1_000_000L / rate
                        codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0)
                        offset += n
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                }
                outIndex >= 0 -> {
                    val buf = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && started) {
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                }
            }
        }
        codec.stop(); codec.release()
        muxer.stop(); muxer.release(); raf.close()
    }
}
