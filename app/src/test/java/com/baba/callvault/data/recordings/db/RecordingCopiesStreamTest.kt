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
import kotlinx.coroutines.flow.first
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
 * The change signal Home uses to notice a Drive copy landing.
 *
 * Worth testing rather than trusting: the whole point is that it fires on an UPDATE to an existing
 * row, not just on inserts and deletes. A stream that only noticed rows appearing would look correct
 * in every other respect and still leave the Drive badge missing until the app was restarted, which
 * is the bug this exists to fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingCopiesStreamTest {

    private lateinit var db: RecordingDatabase
    private val name = "20260902_120205.887+0300_in_Dan.m4a"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RecordingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun stamping_a_drive_copy_emits_a_new_value() = runBlocking {
        // Arrange — one device-only recording, as it is the moment a merge finishes
        db.recordingDao().upsert(RecordingEntry(name, localUri = "content://local/a", lastModified = 1L))
        assertNull(db.recordingDao().observeCopies().first().single().driveUri)

        // Act — what RecordingCopyWorker does once the upload lands
        db.recordingDao().setDrive(name, "content://drive/a", 4096L)

        // Assert
        val row = db.recordingDao().observeCopies().first().single()
        assertEquals("content://drive/a", row.driveUri)
        assertEquals("content://local/a", row.localUri)
        assertEquals(name, row.displayName)
    }

    @Test
    fun clearing_the_local_copy_is_visible_too() = runBlocking {
        // DRIVE-only mode deletes the device copy after syncing; the row has to stop claiming one.
        db.recordingDao().upsert(
            RecordingEntry(name, localUri = "content://local/a", driveUri = "content://drive/a", lastModified = 1L)
        )
        db.recordingDao().clearLocal(name)

        val row = db.recordingDao().observeCopies().first().single()
        assertNull(row.localUri)
        assertEquals("content://drive/a", row.driveUri)
    }

    @Test
    fun the_signal_carries_only_what_it_needs_to_compare() = runBlocking {
        // Three columns, not the whole row: this is compared on every table write, so it must stay
        // cheap. If it ever grows a field, that is a decision, not an accident.
        db.recordingDao().upsert(
            RecordingEntry(name, localUri = "content://local/a", lastModified = 1L, durationSeconds = 312L)
        )
        val row = db.recordingDao().observeCopies().first().single()
        assertEquals(
            RecordingCopies(name, "content://local/a", null),
            row
        )
    }
}
