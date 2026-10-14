/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.server.speakers.SpeakerChannel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner-pickup write-back coordinator's pure logic — the parts where a wrong answer silently
 * corrupts the Node record (a mis-mapped role, a match to the wrong recording, a malformed
 * transcript item). The DB / clock / network edges stay on the device; everything below is
 * deterministic and was the shape verified against the running stack (Node `update-call`
 * `$set`, the engine's `session.history.to_dict()` items shape, the VoIP detector's file name).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnerWritebackCoordinatorTest {

    // --- roleForChannel: far party = the caller = "user"; the owner = "assistant" ---

    @Test
    fun `a is far maps a to user and b to assistant`() {
        assertEquals("user", OwnerWritebackCoordinator.roleForChannel(ChannelMap.A_IS_FAR, SpeakerChannel.A))
        assertEquals("assistant", OwnerWritebackCoordinator.roleForChannel(ChannelMap.A_IS_FAR, SpeakerChannel.B))
    }

    @Test
    fun `b is far maps b to user and a to assistant`() {
        assertEquals("assistant", OwnerWritebackCoordinator.roleForChannel(ChannelMap.B_IS_FAR, SpeakerChannel.A))
        assertEquals("user", OwnerWritebackCoordinator.roleForChannel(ChannelMap.B_IS_FAR, SpeakerChannel.B))
    }

    @Test
    fun `unknown map falls back to the a-is-caller convention`() {
        assertEquals("user", OwnerWritebackCoordinator.roleForChannel(ChannelMap.UNKNOWN, SpeakerChannel.A))
        assertEquals("assistant", OwnerWritebackCoordinator.roleForChannel(ChannelMap.UNKNOWN, SpeakerChannel.B))
    }

    // --- matchRecording: the VoIP file whose name-timestamp is closest to the join time ---

    /** 20261014_120001.500+0530 — the exact epoch of the fixture file-name prefix. */
    private val joinMs = 1_791_959_401_500L

    private fun entry(name: String) = RecordingEntry(displayName = name)

    @Test
    fun `matches the voip recording inside the window`() {
        val got = OwnerWritebackCoordinator.matchRecording(
            listOf(entry("20261014_120001.500+0530_voip.mk4a")),
            joinMs,
        )
        assertEquals("20261014_120001.500+0530_voip.mk4a", got?.displayName)
    }

    @Test
    fun `closest voip wins when two are in the window`() {
        val got = OwnerWritebackCoordinator.matchRecording(
            listOf(
                entry("20261014_120001.500+0530_voip.mk4a"),
                entry("20261014_120005.500+0530_voip.mk4a"),
            ),
            joinMs, // first entry exact, second is 4 s off
        )
        assertEquals("20261014_120001.500+0530_voip.mk4a", got?.displayName)
    }

    @Test
    fun `non-voip recordings are never matched`() {
        val got = OwnerWritebackCoordinator.matchRecording(
            listOf(
                entry("20261014_120001.500+0530_+919876543210.mk4a"), // carrier call
                entry("20261014_120001.500+0530_voip.mk4a"),
            ),
            joinMs,
        )
        assertEquals("20261014_120001.500+0530_voip.mk4a", got?.displayName)
    }

    @Test
    fun `a voip recording outside the five-minute window is not matched`() {
        val got = OwnerWritebackCoordinator.matchRecording(
            listOf(entry("20261014_121000.000+0530_voip.mk4a")), // 10 minutes later
            joinMs,
        )
        assertNull(got)
    }

    @Test
    fun `no recordings at all yields null not an exception`() {
        assertNull(OwnerWritebackCoordinator.matchRecording(emptyList(), joinMs))
    }

    @Test
    fun `a corrupt name prefix is skipped without failing the whole match`() {
        val got = OwnerWritebackCoordinator.matchRecording(
            listOf(
                entry("garbage-not-a-date_voip.mk4a"),
                entry("20261014_120001.500+0530_voip.mk4a"),
            ),
            joinMs,
        )
        assertEquals("20261014_120001.500+0530_voip.mk4a", got?.displayName)
    }

    // --- buildItems: engine-shaped {items:[{type,role,content:[str],created_at}]} ---

    private fun seg(start: Long, text: String, speaker: String?) =
        TranscriptSegmentEntry(displayName = "d", startMs = start, endMs = start + 1000, text = text, speaker = speaker)

    @Test
    fun `segments become engine items with mapped roles`() {
        val items = OwnerWritebackCoordinator.buildItems(
            listOf(
                seg(1000L, "hello?", "A"),
                seg(4000L, "hi there", "B"),
            ),
            ChannelMap.A_IS_FAR,
        )
        assertEquals(2, items.length())
        val first = items.getJSONObject(0)
        assertEquals("message", first.getString("type"))
        assertEquals("user", first.getString("role")) // A is the far party
        assertEquals("hello?", first.getJSONArray("content").getString(0))
        assertEquals(1.0, first.getDouble("created_at"), 0.0)
        val second = items.getJSONObject(1)
        assertEquals("assistant", second.getString("role"))
        assertEquals(4.0, second.getDouble("created_at"), 0.0)
    }

    @Test
    fun `b is far flips the owner to user`() {
        val items = OwnerWritebackCoordinator.buildItems(
            listOf(seg(1000L, "who is this", "B"), seg(3000L, "this is me", "A")),
            ChannelMap.B_IS_FAR,
        )
        assertEquals("user", items.getJSONObject(0).getString("role"))
        assertEquals("assistant", items.getJSONObject(1).getString("role"))
    }

    @Test
    fun `both and silence and unlabeled turns are skipped`() {
        val items = OwnerWritebackCoordinator.buildItems(
            listOf(
                seg(1000L, "talking over", "+"),
                seg(2000L, "quiet", "-"),
                seg(3000L, "no label", null),
                seg(4000L, "real turn", "A"),
            ),
            ChannelMap.A_IS_FAR,
        )
        assertEquals(1, items.length())
        assertEquals("real turn", items.getJSONObject(0).getJSONArray("content").getString(0))
    }

    @Test
    fun `no segments yields an empty array not null`() {
        val items = OwnerWritebackCoordinator.buildItems(emptyList(), ChannelMap.UNKNOWN)
        assertEquals(0, items.length())
    }

    // --- updateCall body: the Node expects {call_id, update_data} ---

    @Test
    fun `the update body nests the payload under update_data`() {
        // Re-implemented assertion on the wire shape: anything else 404s/$sets the wrong field.
        val body = JSONObject(TakeoverApiClient.buildUpdateBody("call-1", JSONObject().put("status", "completed")))
        assertEquals("call-1", body.getString("call_id"))
        assertEquals("completed", body.getJSONObject("update_data").getString("status"))
    }
}
