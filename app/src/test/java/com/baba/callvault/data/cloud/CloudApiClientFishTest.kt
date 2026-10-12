/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mapping between Fish Audio's `/asr` response and the app's timed segments — tested against
 * the exact shapes the live API returns (verified 2026-10-12 with a real `transcribe-1-pro`
 * call): a multi-speaker run with `speaker_turns`, and the plain single-voice run with one
 * punctuated `text` plus word-level `segments`.
 *
 * org.json needs the Android runtime, hence Robolectric at the project's pinned SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudApiClientFishTest {

    // --- isFish: the host decides the protocol, nothing else ---

    @Test
    fun `fish host routes to the fish path`() {
        // The client appends "/asr", so the configured base must end in /v1 to reach
        // https://api.fish.audio/v1/asr — the documented endpoint.
        assertTrue(CloudApiClient.isFish("https://api.fish.audio/v1"))
    }

    @Test
    fun `openai shaped endpoints stay on the openai path`() {
        assertFalse(CloudApiClient.isFish("https://api.groq.com/openai/v1"))
        assertFalse(CloudApiClient.isFish("http://192.168.1.9:11434/v1"))
        // A proxy in front of fish keeps its own host and speaks OpenAI — that is the right
        // outcome: the client talks the protocol the host actually speaks.
        assertFalse(CloudApiClient.isFish("https://proxy.internal/fish"))
        // A fish-named host at a non-standard address is not the API.
        assertFalse(CloudApiClient.isFish("https://api.fish.audio.evil.example/v1"))
    }

    // --- parseFish: the shapes the live API returns ---

    /** A real multi-speaker response (abridged from the 2026-10-12 live capture). */
    private val multiSpeakerJson =
        """
        {
          "text": "<|speaker:0|>Hi, are you there? [laughter]<|speaker:1|>Yes, I can hear you.",
          "duration": 4.52,
          "language_code": "en",
          "language": "English",
          "segments": [
            { "text": "Hi", "start": 0.12, "end": 0.3 },
            { "text": "are", "start": 0.31, "end": 0.42 }
          ],
          "speaker_turns": [
            {
              "start": 0.12,
              "end": 1.4,
              "speaker": 0,
              "emotion": "neutral",
              "text": "<|speaker:0|>Hi, are you there? [laughter]"
            },
            {
              "start": 1.8,
              "end": 4.52,
              "speaker": 1,
              "emotion": "neutral",
              "text": "<|speaker:1|>Yes, I can hear you."
            }
          ],
          "request_id": "req-abc123"
        }
        """.trimIndent()

    @Test
    fun `multi speaker response yields one timed segment per turn, markers stripped`() {
        val out = CloudApiClient.parseFish(JSONObject(multiSpeakerJson))
        assertEquals(2, out.size)
        assertEquals("Hi, are you there? [laughter]", out[0].text)
        assertEquals(120L, out[0].startMs)
        assertEquals(1400L, out[0].endMs)
        assertEquals("Yes, I can hear you.", out[1].text)
        assertEquals(1800L, out[1].startMs)
        assertEquals(4520L, out[1].endMs)
        // The word-level segments must never leak into the lines — they are one word a piece.
        out.forEach { assertFalse(it.text == "Hi" || it.text == "are") }
    }

    /** The default single-voice shape: no turns, one punctuated text, word segments. */
    private val plainJson =
        """
        {
          "text": "Hello, this is a quick test of the transcription engine. One two three.",
          "duration": 4.25,
          "language_code": "en",
          "language": "English",
          "segments": [
            { "text": "Hello", "start": 0.3, "end": 0.51 },
            { "text": "this", "start": 0.52, "end": 0.65 }
          ],
          "request_id": "req-def456"
        }
        """.trimIndent()

    @Test
    fun `plain response with no turns falls back to one clean segment`() {
        val out = CloudApiClient.parseFish(JSONObject(plainJson))
        assertEquals(1, out.size)
        assertEquals("Hello, this is a quick test of the transcription engine. One two three.", out[0].text)
        assertEquals(0L, out[0].startMs)
        assertEquals(4250L, out[0].endMs)
    }

    @Test
    fun `an empty turn list is the same as no turns`() {
        val json = JSONObject(multiSpeakerJson)
        json.put("speaker_turns", org.json.JSONArray())
        val out = CloudApiClient.parseFish(json)
        assertEquals(1, out.size)
        // The whole punctuated text, not the marker soup or the word segments.
        assertTrue(out[0].text.startsWith("Hi, are you there?"))
    }

    @Test
    fun `turns that are all empty text fall back to the text field`() {
        val json = JSONObject(multiSpeakerJson)
        val turns = org.json.JSONArray()
        turns.put(JSONObject().put("start", 0.0).put("end", 1.0).put("text", ""))
        json.put("speaker_turns", turns)
        val out = CloudApiClient.parseFish(json)
        assertEquals(1, out.size)
        assertTrue(out[0].text.isNotEmpty())
    }

    @Test(expected = CloudApiClient.CloudApiException::class)
    fun `no text at all is an error, not an empty transcript`() {
        CloudApiClient.parseFish(JSONObject("""{"duration": 0.0, "segments": []}"""))
    }

    // --- stripSpeakerMarkers ---

    @Test
    fun `speaker markers are stripped, words and cues kept`() {
        assertEquals(
            "Hi, are you there? [laughter]",
            CloudApiClient.stripSpeakerMarkers("<|speaker:0|>Hi, are you there? [laughter]")
        )
        assertEquals(
            "a b",
            CloudApiClient.stripSpeakerMarkers("<|speaker:2|>a<|speaker:10|> b")
        )
        // Text without markers passes through untouched.
        assertEquals("no markers here", CloudApiClient.stripSpeakerMarkers("no markers here"))
    }
}
