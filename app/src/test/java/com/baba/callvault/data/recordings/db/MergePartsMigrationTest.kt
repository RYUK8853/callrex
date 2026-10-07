/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v2 → v3 migration that adds `merge_parts`, and the DAO un-merge depends on.
 *
 * These rows are the only record of where one call ends and the next begins inside a merged
 * recording, and merging deletes the originals — so a fault here is not a cosmetic bug, it is a
 * conversation that can never be taken apart again. Room also validates the on-disk schema against
 * the entity on every open, so a migration that produces the wrong shape throws on launch for every
 * user rather than failing quietly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class MergePartsMigrationTest {

    private val name = "merge-migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    /** The database exactly as schema version 2 had it: recordings only, with the duration column. */
    private fun seedVersion2() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recordings` (" +
                "`displayName` TEXT NOT NULL, `localUri` TEXT, `driveUri` TEXT, " +
                "`localSizeBytes` INTEGER, `driveSizeBytes` INTEGER, " +
                "`lastModified` INTEGER NOT NULL, `durationSeconds` INTEGER, " +
                "PRIMARY KEY(`displayName`))"
        )
        db.execSQL(
            "INSERT INTO recordings VALUES ('20260901_143200.000+0300_out_Dan.m4a', " +
                "'content://local/a', NULL, 4096, NULL, 1700000000000, 494)"
        )
        db.version = 2
        db.close()
    }

    private fun openV3(): RecordingDatabase =
        Room.databaseBuilder(context, RecordingDatabase::class.java, name)
            .addMigrations(RecordingDatabase.MIGRATION_1_2, RecordingDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

    private fun part(
        merged: String,
        position: Int,
        partName: String,
        frameStart: Int,
        frameCount: Int,
    ) = MergePartEntry(
        mergedName = merged,
        position = position,
        partName = partName,
        frameStart = frameStart,
        frameCount = frameCount,
        startTimeUs = frameStart * 21_333L,
        durationUs = frameCount * 21_333L,
        originalLastModified = 1_700_000_000_000L + position,
        encoderDelayUs = 21_333L,
        encoderPaddingUs = 0L,
    )

    @Test
    fun migrating_keeps_the_recordings_and_adds_an_empty_merge_parts_table() = runBlocking {
        // Arrange
        seedVersion2()

        // Act — Room validates the migrated schema against the entities as it opens.
        val db = openV3()

        // Assert
        assertEquals(1, db.recordingDao().getAll().size)
        assertEquals(494L, db.recordingDao().getAll().single().durationSeconds)
        assertTrue(db.mergePartDao().partsOf("20260901_143200.000+0300_out_Dan.m4a").isEmpty())
        db.close()
    }

    @Test
    fun parts_come_back_in_position_order_however_they_were_inserted() = runBlocking {
        // Arrange
        seedVersion2()
        val db = openV3()
        val merged = "20260901_143200.000+0300_out_Dan.m4a"

        // Act — deliberately out of order, because selection order is not insertion order
        db.mergePartDao().insertAll(
            listOf(
                part(merged, 3, "c.m4a", frameStart = 378, frameCount = 100),
                part(merged, 1, "a.m4a", frameStart = 0, frameCount = 236),
                part(merged, 2, "b.m4a", frameStart = 236, frameCount = 142),
            )
        )

        // Assert
        val parts = db.mergePartDao().partsOf(merged)
        assertEquals(listOf(1, 2, 3), parts.map { it.position })
        assertEquals(listOf("a.m4a", "b.m4a", "c.m4a"), parts.map { it.partName })
        assertEquals(listOf(0, 236, 378), parts.map { it.frameStart })
        db.close()
    }

    @Test
    fun the_codec_delay_survives_the_round_trip_unrounded() = runBlocking {
        // The whole point of storing it: 1024 samples at 48 kHz is 21333.33 us, and an un-merged
        // part that loses this comes back 21 ms early.
        seedVersion2()
        val db = openV3()
        val merged = "20260901_143200.000+0300_out_Dan.m4a"

        db.mergePartDao().insertAll(listOf(part(merged, 1, "a.m4a", 0, 236)))

        assertEquals(21_333L, db.mergePartDao().partsOf(merged).single().encoderDelayUs)
        db.close()
    }

    @Test
    fun a_recording_knows_which_merge_it_is_a_part_of() = runBlocking {
        seedVersion2()
        val db = openV3()
        val merged = "20260901_143200.000+0300_out_Dan.m4a"
        db.mergePartDao().insertAll(listOf(part(merged, 2, "b.m4a", 236, 142)))

        assertEquals(merged, db.mergePartDao().mergedNameContaining("b.m4a"))
        assertNull(db.mergePartDao().mergedNameContaining("never-merged.m4a"))
        db.close()
    }

    @Test
    fun un_merging_clears_every_part_of_that_recording_and_no_others() = runBlocking {
        // Arrange
        seedVersion2()
        val db = openV3()
        val mergedA = "20260901_143200.000+0300_out_Dan.m4a"
        val mergedB = "20260830_101500.000+0300_in_Mum.m4a"
        db.mergePartDao().insertAll(
            listOf(
                part(mergedA, 1, "a.m4a", 0, 236),
                part(mergedA, 2, "b.m4a", 236, 142),
                part(mergedB, 1, "x.m4a", 0, 500),
            )
        )

        // Act
        db.mergePartDao().deleteParts(mergedA)

        // Assert
        assertTrue(db.mergePartDao().partsOf(mergedA).isEmpty())
        assertEquals(1, db.mergePartDao().partsOf(mergedB).size)
        db.close()
    }
}
