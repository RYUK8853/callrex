/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who was speaking, derived from the stereo capture the daemon already has in hand.
 *
 * All of the decisions live here rather than in the capture loop, because the capture loop is the one
 * place in this app where a mistake costs a recording. Everything below runs on synthetic PCM with no
 * device and no call.
 */
class SpeakerTurnDetectorTest {

    private val sampleRate = 48_000

    @Test
    fun reports_channel_A_while_only_the_left_channel_carries_speech() {
        // Arrange
        val detector = SpeakerTurnDetector(sampleRate)

        // Act
        detector.accept(stereo(leftAmplitude = 8000, rightAmplitude = 0, millis = 500), Int.MAX_VALUE)
        val turns = detector.finish()

        // Assert
        assertEquals(listOf(SpeakerChannel.A), turns.map { it.channel })
    }

    @Test
    fun reports_channel_B_while_only_the_right_channel_carries_speech() {
        val detector = SpeakerTurnDetector(sampleRate)

        detector.accept(stereo(leftAmplitude = 0, rightAmplitude = 8000, millis = 500), Int.MAX_VALUE)

        assertEquals(listOf(SpeakerChannel.B), detector.finish().map { it.channel })
    }

    @Test
    fun reports_BOTH_when_the_two_channels_are_comparably_loud() {
        // Double-talk is common on real calls and must not be forced onto one speaker.
        val detector = SpeakerTurnDetector(sampleRate)

        detector.accept(stereo(8000, 8000, millis = 500), Int.MAX_VALUE)

        assertEquals(listOf(SpeakerChannel.BOTH), detector.finish().map { it.channel })
    }

    @Test
    fun reports_SILENCE_when_neither_channel_is_above_the_noise_floor() {
        val detector = SpeakerTurnDetector(sampleRate)

        detector.accept(stereo(5, 5, millis = 500), Int.MAX_VALUE)

        assertEquals(listOf(SpeakerChannel.SILENCE), detector.finish().map { it.channel })
    }

    @Test
    fun coalesces_consecutive_identical_windows_into_one_turn() {
        // The output has to stay small: a 30-minute call is 18000 windows but only a few hundred real
        // turns. Storing per-window would be wasteful to hold and useless to read.
        val detector = SpeakerTurnDetector(sampleRate)

        repeat(10) { detector.accept(stereo(8000, 0, millis = 100), Int.MAX_VALUE) }

        assertEquals(1, detector.finish().size)
    }

    @Test
    fun emits_a_new_turn_with_the_correct_start_time_when_the_speaker_changes() {
        val detector = SpeakerTurnDetector(sampleRate)

        detector.accept(stereo(8000, 0, millis = 1000), Int.MAX_VALUE)
        detector.accept(stereo(0, 8000, millis = 1000), Int.MAX_VALUE)

        val turns = detector.finish()

        assertEquals(listOf(SpeakerChannel.A, SpeakerChannel.B), turns.map { it.channel })
        assertEquals(0L, turns[0].startMs)
        assertEquals(1000L, turns[1].startMs)
    }

    @Test
    fun spans_chunk_boundaries_so_a_window_is_not_lost_between_reads() {
        // The capture loop delivers whatever the AudioRecord returns, which will not align to windows.
        // Ten 10 ms chunks must add up to one 100 ms window, not be discarded as ten partial ones.
        val detector = SpeakerTurnDetector(sampleRate)

        repeat(10) { detector.accept(stereo(8000, 0, millis = 10), Int.MAX_VALUE) }

        assertEquals(listOf(SpeakerChannel.A), detector.finish().map { it.channel })
    }

    @Test
    fun ignores_a_trailing_partial_frame_instead_of_throwing() {
        // Mirrors PcmDownmix.stereoToMono, which leaves a truncated final frame unconsumed.
        val detector = SpeakerTurnDetector(sampleRate)

        detector.accept(ByteArray(5), 5)

        detector.finish() // must not throw
    }

    @Test
    fun never_reads_past_the_reported_length() {
        // The capture loop passes the byte count AudioRecord actually read, which is usually less than
        // the buffer. Reading the whole buffer would score stale audio from the previous read.
        val detector = SpeakerTurnDetector(sampleRate)
        val buffer = stereo(8000, 0, millis = 200)

        // Only the first 100 ms is "read"; the rest is stale.
        detector.accept(buffer, framesFor(millis = 100) * BYTES_PER_FRAME)

        assertEquals(1, detector.finish().size)
    }

    @Test
    fun produces_nothing_at_all_when_no_audio_was_seen() {
        // A mono capture never calls accept, and downstream must see "no data", not a silent turn.
        assertTrue(SpeakerTurnDetector(sampleRate).finish().isEmpty())
    }

    @Test
    fun round_trips_through_the_codec() {
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.A),
            SpeakerTurn(1200, SpeakerChannel.BOTH),
            SpeakerTurn(3400, SpeakerChannel.SILENCE)
        )

        assertEquals(turns, SpeakerTurnCodec.decode(SpeakerTurnCodec.encode(turns)))
    }

    @Test
    fun decodes_an_empty_string_as_no_turns() {
        // What the app receives from a mono capture, or from a daemon too old to have the method.
        assertTrue(SpeakerTurnCodec.decode("").isEmpty())
    }

    @Test
    fun decoding_malformed_data_yields_no_turns_rather_than_throwing() {
        // This crosses a process boundary from the daemon. Garbage must degrade to "no speaker data",
        // never to an exception on the path that finishes a recording.
        assertTrue(SpeakerTurnCodec.decode("not;valid:at:all").isEmpty())
    }

    /** Builds interleaved stereo PCM-16 (little-endian) with constant per-channel amplitude. */
    private fun stereo(leftAmplitude: Int, rightAmplitude: Int, millis: Int): ByteArray {
        val frames = framesFor(millis)
        val out = ByteArray(frames * BYTES_PER_FRAME)
        for (frame in 0 until frames) {
            val base = frame * BYTES_PER_FRAME
            out[base] = (leftAmplitude and 0xFF).toByte()
            out[base + 1] = ((leftAmplitude shr 8) and 0xFF).toByte()
            out[base + 2] = (rightAmplitude and 0xFF).toByte()
            out[base + 3] = ((rightAmplitude shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun framesFor(millis: Int) = sampleRate * millis / 1000

    private companion object {
        const val BYTES_PER_FRAME = 4 // PCM-16 stereo
    }
}
