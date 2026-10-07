/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which recordings a scheduled run should pick up.
 *
 * Getting this wrong is expensive in a way the user feels: each recording costs roughly its own
 * duration in CPU, so a queue that re-offers finished or hopeless work burns a night of battery for
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class TranscriptionQueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearCatalog() = runBlocking {
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
    }

    @Test
    fun offers_a_recording_that_has_never_been_transcribed() = runBlocking {
        // Arrange
        catalogued("fresh.ogg", lastModified = 100L)

        // Act
        val pending = TranscriptionQueue.pending(context)

        // Assert
        assertEquals(listOf("fresh.ogg"), pending)
    }

    @Test
    fun excludes_a_recording_that_already_has_a_transcript() = runBlocking {
        // Arrange
        catalogued("done.ogg", lastModified = 100L)
        state("done.ogg", TranscriptState.DONE)

        // Act / Assert
        assertTrue(TranscriptionQueue.pending(context).isEmpty())
    }

    @Test
    fun excludes_a_recording_whose_last_attempt_failed() = runBlocking {
        // A permanently undecodable file would otherwise be retried on every scheduled run forever,
        // and the battery would pay for it nightly. Only an explicit tap retries a failure.
        catalogued("broken.ogg", lastModified = 100L)
        state("broken.ogg", TranscriptState.FAILED)

        assertTrue(TranscriptionQueue.pending(context).isEmpty())
    }

    @Test
    fun excludes_a_recording_that_is_already_running() = runBlocking {
        catalogued("busy.ogg", lastModified = 100L)
        state("busy.ogg", TranscriptState.RUNNING)

        assertTrue(TranscriptionQueue.pending(context).isEmpty())
    }

    @Test
    fun still_offers_a_recording_left_queued_by_an_interrupted_run() = runBlocking {
        // A job killed after marking work QUEUED must not strand it forever.
        catalogued("queued.ogg", lastModified = 100L)
        state("queued.ogg", TranscriptState.QUEUED)

        assertEquals(listOf("queued.ogg"), TranscriptionQueue.pending(context))
    }

    @Test
    fun skips_a_recording_with_no_local_copy() = runBlocking {
        // A Drive-only recording has no local file to decode, so queuing it guarantees a failure and
        // would then mark it FAILED, hiding it from a later run made after it syncs back down.
        val name = "drive-only.ogg"
        val local = "content://local/only".toUri()
        RecordingCatalog.recordLocal(context, name, local, 10L, 100L)
        RecordingCatalog.markDrive(context, name, "content://drive/only".toUri(), 10L, deleteLocalAfter = false)
        RecordingCatalog.removeCopyByUri(context, local)

        assertFalse(TranscriptionQueue.pending(context).contains(name))
    }

    @Test
    fun returns_oldest_first_so_a_backlog_drains_in_call_order() = runBlocking {
        // Arrange — catalogued out of order on purpose.
        catalogued("newest.ogg", lastModified = 300L)
        catalogued("oldest.ogg", lastModified = 100L)
        catalogued("middle.ogg", lastModified = 200L)

        // Act
        val pending = TranscriptionQueue.pending(context)

        // Assert
        assertEquals(listOf("oldest.ogg", "middle.ogg", "newest.ogg"), pending)
    }

    @Test
    fun honours_the_limit() = runBlocking {
        catalogued("a.ogg", lastModified = 100L)
        catalogued("b.ogg", lastModified = 200L)

        assertEquals(listOf("a.ogg"), TranscriptionQueue.pending(context, limit = 1))
    }

    @Test
    fun a_limit_of_zero_takes_the_whole_backlog() = runBlocking {
        // The user-facing "No limit" option. Zero must mean everything, not nothing — the opposite
        // reading would silently transcribe none of the backlog every night.
        catalogued("a.ogg", lastModified = 100L)
        catalogued("b.ogg", lastModified = 200L)

        assertEquals(
            listOf("a.ogg", "b.ogg"),
            TranscriptionQueue.pending(context, limit = TranscriptionQueue.NO_LIMIT)
        )
    }

    @Test
    fun excludes_a_recording_too_long_to_decode() = runBlocking {
        // The nightly path. Offering it would spend the run on a decode that exhausts the heap, and
        // since nothing ever marks it done or failed it would hold the same slot every night after.
        catalogued("marathon.ogg", lastModified = 100L)

        val pending = TranscriptionQueue.pending(context, audioDurationMs = { OVER_THE_LIMIT_MS })

        assertTrue(pending.isEmpty())
    }

    @Test
    fun a_too_long_recording_does_not_hold_the_slot_of_a_shorter_one_behind_it() = runBlocking {
        // The reason exclusion beats "attempt and give up": the queue takes the oldest `limit` and a
        // recording that can never finish stays eligible for ever, so the calls after it would never
        // be reached at all.
        catalogued("marathon.ogg", lastModified = 100L)
        catalogued("short.ogg", lastModified = 200L)

        val pending = TranscriptionQueue.pending(
            context,
            limit = 1,
            audioDurationMs = { uri -> if (uri.toString().contains("marathon")) OVER_THE_LIMIT_MS else 60_000L }
        )

        assertEquals(listOf("short.ogg"), pending)
    }

    @Test
    fun still_offers_a_recording_whose_length_is_unknown() = runBlocking {
        // Deliberate: a container that declares no duration is far more common than a call over the
        // limit, and refusing on "unknown" would keep ordinary short recordings out of every run.
        catalogued("undated.ogg", lastModified = 100L)

        assertEquals(
            listOf("undated.ogg"),
            TranscriptionQueue.pending(context, audioDurationMs = { UNKNOWN_LENGTH_MS })
        )
    }

    private suspend fun catalogued(name: String, lastModified: Long) {
        RecordingCatalog.recordLocal(context, name, "content://local/$name".toUri(), 10L, lastModified)
    }

    private suspend fun state(name: String, state: TranscriptState) {
        TranscriptDatabase.get(context).transcriptDao()
            .upsertTranscript(TranscriptEntry(displayName = name, state = state))
    }

    private companion object {
        /**
         * Comfortably past [TranscriptionLengthLimit.MAX_MINUTES], **derived from it**.
         *
         * This was a literal 50 minutes and broke the day the limit moved past it — the second time a
         * test pinned this number instead of the behaviour. The limit is documented as a stopgap meant
         * to change; a test that fails when it changes fails for the one reason that is not a bug.
         */
        val OVER_THE_LIMIT_MS = (TranscriptionLengthLimit.MAX_MINUTES + 5L) * 60 * 1000

        /** What a container that declares no duration reports. */
        const val UNKNOWN_LENGTH_MS = 0L
    }
}
