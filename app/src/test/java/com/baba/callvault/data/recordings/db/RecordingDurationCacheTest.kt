/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The remembered length of a recording.
 *
 * Reading it costs a SAF file-descriptor open plus a MediaExtractor, and Home used to pay that for
 * every recording the call log could not answer for, on every single list load — which is why a
 * library that kept growing kept taking longer to appear. These tests pin the two properties the
 * cache has to have: a value survives so the file is never opened twice, and an entry that has never
 * been read stays null so it still gets read once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class RecordingDurationCacheTest {

    private lateinit var db: RecordingDatabase
    private lateinit var dao: RecordingDao

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RecordingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.recordingDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun a_newly_catalogued_recording_has_no_remembered_duration() = runBlocking {
        // Arrange
        dao.upsert(RecordingEntry(displayName = "call-a.ogg", localUri = "content://x/a"))

        // Act
        val entry = dao.findByName("call-a.ogg")

        // Assert — null is what makes the first read happen at all.
        assertNull(entry?.durationSeconds)
    }

    @Test
    fun a_remembered_duration_is_returned_on_the_next_read() = runBlocking {
        // Arrange
        dao.upsert(RecordingEntry(displayName = "call-a.ogg", localUri = "content://x/a"))

        // Act
        dao.setDuration("call-a.ogg", 137L)

        // Assert
        assertEquals(137L, dao.findByName("call-a.ogg")?.durationSeconds)
        assertEquals(137L, dao.getAll().single().durationSeconds)
    }

    @Test
    fun remembering_a_duration_leaves_the_rest_of_the_row_alone() = runBlocking {
        // Arrange — the Drive copy and the timestamp are what the sweeps rely on.
        dao.upsert(
            RecordingEntry(
                displayName = "call-a.ogg",
                localUri = "content://x/a",
                driveUri = "content://drive/a",
                localSizeBytes = 4096L,
                lastModified = 1_700_000_000_000L,
            )
        )

        // Act
        dao.setDuration("call-a.ogg", 42L)

        // Assert
        val entry = dao.findByName("call-a.ogg")!!
        assertEquals(42L, entry.durationSeconds)
        assertEquals("content://drive/a", entry.driveUri)
        assertEquals(4096L, entry.localSizeBytes)
        assertEquals(1_700_000_000_000L, entry.lastModified)
    }

    @Test
    fun remembering_a_duration_for_an_unknown_recording_is_a_no_op() = runBlocking {
        // Act — a row can be deleted between the read and the write.
        dao.setDuration("never-existed.ogg", 99L)

        // Assert
        assertEquals(0, dao.getAll().size)
    }
}
