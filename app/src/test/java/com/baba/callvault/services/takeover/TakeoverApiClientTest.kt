/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import com.baba.callvault.services.takeover.TakeoverApiClient.AgentCallRecord
import com.baba.callvault.services.takeover.TakeoverApiClient.TranscriptEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Node-API parsers of the takeover client — pinned to the shapes verified against the
 * running stack (2026-10-14): the list endpoint wraps its array in `{"calls":[...]}`, records
 * carry `source`/`session_type`/`answer_mode`, timestamps arrive as epoch-seconds or ISO
 * strings, and the transcript endpoint is the engine's `session.history.to_dict()` object
 * `{"items":[{"type":"message","role":...,"content":[str,...]}]}`.
 *
 * org.json needs the Android runtime, hence Robolectric at the project's pinned SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TakeoverApiClientTest {

    // --- parseCallRecord: the record fields the Agent Calls list renders ---

    @Test
    fun `full record parses every field`() {
        val o = JSONObject()
            .put("id", "call-123")
            .put("phone_number", "+919876543210")
            .put("status", "completed")
            .put("answer_mode", "agent")
            .put("transcript", "hi there")
            .put("call_summary", "customer asked about pricing")
            .put("captured_message", "voicemail message")
            .put("started_at", 1_760_565_600)
            .put("ended_at", 1_760_565_660)
            .put("source", "sip")
            .put("session_type", "sip_inbound")
            .put("duration_seconds", 60)
        val r = TakeoverApiClient.parseCallRecord(o)
        assertNotNull(r)
        assertEquals("call-123", r!!.id)
        assertEquals("+919876543210", r.phoneNumber)
        assertEquals("completed", r.status)
        assertEquals("agent", r.answerMode)
        assertEquals("hi there", r.transcript)
        assertEquals("customer asked about pricing", r.summary)
        assertEquals("voicemail message", r.capturedMessage)
        assertEquals(1_760_565_600_000L, r.startedAt)
        assertEquals(1_760_565_660_000L, r.endedAt)
        assertEquals("sip", r.source)
        assertEquals("sip_inbound", r.sessionType)
        assertEquals(60.0, r.durationSeconds!!, 0.0)
    }

    @Test
    fun `alias fields are honoured`() {
        val r = TakeoverApiClient.parseCallRecord(
            JSONObject()
                .put("id", "c1")
                .put("phone", "123")
                .put("summary", "sum")
                .put("full_transcript", "tx"),
        )
        assertEquals("123", r!!.phoneNumber)
        assertEquals("sum", r.summary)
        assertEquals("tx", r.transcript)
    }

    @Test
    fun `a record without an id is dropped`() {
        assertNull(TakeoverApiClient.parseCallRecord(JSONObject().put("status", "completed")))
        assertNull(TakeoverApiClient.parseCallRecord(null))
    }

    @Test
    fun `blank answer mode and duration become null not junk`() {
        val r = TakeoverApiClient.parseCallRecord(
            JSONObject().put("id", "c1").put("answer_mode", "").put("duration_seconds", -1),
        )
        assertNull(r!!.answerMode)
        assertNull(r.durationSeconds)
    }

    // --- parseEpochMillis: the mixed timestamp conventions ---

    @Test
    fun `epoch seconds become millis`() {
        assertEquals(1_760_565_600_000L, TakeoverApiClient.parseEpochMillis(1_760_565_600))
    }

    @Test
    fun `epoch millis pass through`() {
        assertEquals(1_760_565_600_000L, TakeoverApiClient.parseEpochMillis(1_760_565_600_000L))
    }

    @Test
    fun `numeric strings are parsed like numbers`() {
        assertEquals(1_760_565_600_000L, TakeoverApiClient.parseEpochMillis("1760565600"))
    }

    @Test
    fun `iso-8601 strings are parsed`() {
        assertEquals(1_760_509_200_000L, TakeoverApiClient.parseEpochMillis("2025-10-15T06:20:00Z"))
    }

    @Test
    fun `zero negative blank and garbage are null not a guessed date`() {
        assertNull(TakeoverApiClient.parseEpochMillis(0))
        assertNull(TakeoverApiClient.parseEpochMillis(-5))
        assertNull(TakeoverApiClient.parseEpochMillis(""))
        assertNull(TakeoverApiClient.parseEpochMillis("not-a-date"))
        assertNull(TakeoverApiClient.parseEpochMillis(null))
    }

    // --- parseTranscript: the engine's history.to_dict() shape ---

    @Test
    fun `wrapped items object with string-list content parses`() {
        val items = JSONArray()
            .put(
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", JSONArray().put("Yes I would like to book")),
            )
            .put(
                JSONObject()
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("content", JSONArray().put("Of course").put("when should it be?")),
            )
        val lines = TakeoverApiClient.parseTranscript(
            JSONObject().put("status", "success").put("transcript", JSONObject().put("items", items)).toString(),
        )
        assertEquals(2, lines.size)
        assertEquals("user", lines[0].speaker)
        assertEquals("Yes I would like to book", lines[0].text)
        assertEquals("assistant", lines[1].speaker)
        assertEquals("Of course\nwhen should it be?", lines[1].text)
    }

    @Test
    fun `bare items array at the root parses too`() {
        val lines = TakeoverApiClient.parseTranscript(
            """{"items":[{"role":"user","content":["hello"]}]}""",
        )
        assertEquals(1, lines.size)
        assertEquals("hello", lines[0].text)
    }

    @Test
    fun `plain string content and text fields are tolerated`() {
        val lines = TakeoverApiClient.parseTranscript(
            """{"entries":[{"role":"user","content":"plain"},{"text":"t2"},{"speaker":"x","text":"t3"}]}""",
        )
        assertEquals(3, lines.size)
        assertEquals("plain", lines[0].text)
        assertEquals("t2", lines[1].text)
        assertEquals("x", lines[2].speaker)
    }

    @Test
    fun `top-level array shape parses`() {
        val lines = TakeoverApiClient.parseTranscript(
            """[{"role":"user","content":["a"]},{"role":"assistant","content":["b"]}]""",
        )
        assertEquals(2, lines.size)
        assertEquals("assistant", lines[1].speaker)
    }

    @Test
    fun `items with no readable text are skipped not crashed on`() {
        val lines = TakeoverApiClient.parseTranscript(
            """{"items":[{"type":"message"},{"role":"user","content":[]},{"role":"user","content":["ok"]}]}""",
        )
        assertEquals(1, lines.size)
        assertEquals("ok", lines[0].text)
    }

    @Test
    fun `an empty or non-object transcript yields an empty list`() {
        assertTrue(TakeoverApiClient.parseTranscript("""{"status":"success"}""").isEmpty())
        assertTrue(TakeoverApiClient.parseTranscript("""{"transcript":{"items":[]}}""").isEmpty())
    }

    // --- header builders: the one-place auth rule ---

    @Test
    fun `engine headers carry bearer only when a token is set`() {
        val none = TakeoverApiClient.engineHeaders(null)
        assertFalse("Accept" !in none)
        assertNull(none["Authorization"])
        val blank = TakeoverApiClient.engineHeaders("   ")
        assertNull(blank["Authorization"])
        val withToken = TakeoverApiClient.engineHeaders("sekret")
        assertEquals("Bearer sekret", withToken["Authorization"])
    }

    @Test
    fun `node headers carry the x server token header only when set`() {
        assertNull(TakeoverApiClient.nodeHeaders(null)["X-Server-Token"])
        assertNull(TakeoverApiClient.nodeHeaders("")["X-Server-Token"])
        assertEquals("sekret", TakeoverApiClient.nodeHeaders("sekret")["X-Server-Token"])
    }

    // --- buildUpdateBody: the $set contract ---

    @Test
    fun `update body nests call_id and update_data at the top level`() {
        val body = JSONObject(TakeoverApiClient.buildUpdateBody("c9", JSONObject().put("status", "completed")))
        assertEquals("c9", body.getString("call_id"))
        assertTrue(body.has("update_data"))
        assertEquals("completed", body.getJSONObject("update_data").getString("status"))
    }
}
