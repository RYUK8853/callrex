/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations, run for real against real SQLite.
 *
 * This is the test that protects an existing phone. [TranscriptDatabase] has no destructive
 * fallback on purpose — a transcript costs minutes of the user's CPU and a summary ninety seconds
 * more — and the price of that decision is that a migration which does not produce exactly what
 * Room expects is not a lost cache but **a crash on first launch, for every user who has ever
 * transcribed a call**, with no way back.
 *
 * `TranscriptSchemaMigrationTest` already compares the migration SQL against Room's exported
 * `createSql` as a unit test. That catches drift and cannot catch anything else: comparing strings
 * says nothing about whether the statement executes, whether Room accepts the result on open, or
 * whether rows written before the upgrade are still there afterwards. All three are checked here.
 *
 * Runs on a device or emulator, and reads the schemas from the test APK's assets — see the
 * `androidTest` assets wiring in `build.gradle.kts`.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptMigrationInstrumentedTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TranscriptDatabase::class.java
    )

    @Test
    fun migrates_v1_to_v8_without_losing_a_transcript() {
        // A user who transcribed a call two versions ago and has not opened the app since.
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO transcripts (displayName, state, modelId, language, updatedAt, errorMessage) " +
                    "VALUES ('old-call.ogg', 'DONE', 'large-v3-turbo-q5_0', 'he', 100, NULL)"
            )
            db.execSQL(
                "INSERT INTO transcript_segments (displayName, startMs, endMs, text, speaker) " +
                    "VALUES ('old-call.ogg', 0, 1500, 'the words that were said', NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        // Room validated the schema on open, which is most of the point. The rest is the data.
        db.query("SELECT state, language FROM transcripts WHERE displayName = 'old-call.ogg'").use {
            assertTrue("the transcript row did not survive the upgrade", it.moveToFirst())
            assertEquals("DONE", it.getString(0))
            assertEquals("he", it.getString(1))
        }
        db.query("SELECT text FROM transcript_segments WHERE displayName = 'old-call.ogg'").use {
            assertTrue("the transcribed words did not survive the upgrade", it.moveToFirst())
            assertEquals("the words that were said", it.getString(0))
        }
    }

    @Test
    fun migrates_v2_to_v4_without_losing_a_note() {
        // The upgrade an actual user takes: v2 is what is installed on the maintainer's phone.
        // A note is the user's own words about a private call and cannot be regenerated from
        // anything, so it is the row whose loss would be permanent.
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO recording_notes (displayName, text, updatedAt) " +
                    "VALUES ('call.ogg', 'ring back about the invoice', 42)"
            )
            db.execSQL(
                "INSERT INTO recording_waveforms (displayName, peaks, updatedAt) " +
                    "VALUES ('call.ogg', '10,20,30', 42)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.query("SELECT text FROM recording_notes WHERE displayName = 'call.ogg'").use {
            assertTrue("the user's note did not survive the upgrade", it.moveToFirst())
            assertEquals("ring back about the invoice", it.getString(0))
        }
        db.query("SELECT peaks FROM recording_waveforms WHERE displayName = 'call.ogg'").use {
            assertTrue(it.moveToFirst())
            assertEquals("10,20,30", it.getString(0))
        }
    }

    @Test
    fun the_summaries_table_is_usable_immediately_after_the_upgrade() {
        // Creating a table Room accepts is not the same as creating one that works. A summary is
        // written the first time the user asks for one, which on an upgraded phone is the first
        // time this table is ever touched.
        helper.createDatabase(DB_NAME, 2).close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.execSQL(
            "INSERT INTO call_summaries (displayName, document, model, createdAt) " +
                "VALUES ('call.ogg', '{\"intent\":\"a\"}', 'gemma-4-e2b-it-q4_k_m', 7)"
        )
        db.query("SELECT model, createdAt FROM call_summaries WHERE displayName = 'call.ogg'").use {
            assertTrue(it.moveToFirst())
            assertEquals("gemma-4-e2b-it-q4_k_m", it.getString(0))
            assertEquals(7L, it.getLong(1))
        }
    }

    @Test
    fun migrates_v3_to_v4_without_losing_a_summary() {
        // The upgrade every current user takes. A summary costs about ninety seconds of their
        // CPU, so losing one to a schema bump spends their battery twice.
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO call_summaries (displayName, document, model, createdAt) " +
                    "VALUES ('call.ogg', '{\"intent\":\"chasing an invoice\"}', 'gemma', 9)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.query("SELECT document FROM call_summaries WHERE displayName = 'call.ogg'").use {
            assertTrue("the summary did not survive the upgrade", it.moveToFirst())
            assertEquals("{\"intent\":\"chasing an invoice\"}", it.getString(0))
        }
    }

    @Test
    fun the_speaker_turns_table_is_usable_immediately_after_the_upgrade() {
        // These turns cannot be recovered from the finished file — it is mono by then — so the
        // table has to work the first time it is written to, which is the end of a real call.
        helper.createDatabase(DB_NAME, 3).close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.execSQL(
            "INSERT INTO speaker_turns (displayName, turns, outgoing, observedMap, updatedAt) " +
                "VALUES ('call.ogg', '0:A;1500:B', 1, 'b_far', 7)"
        )
        db.query(
            "SELECT turns, outgoing, observedMap FROM speaker_turns WHERE displayName = 'call.ogg'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("0:A;1500:B", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertEquals("b_far", it.getString(2))
        }
    }

    @Test
    fun a_second_upgrade_is_harmless() {
        // Migrations use CREATE TABLE IF NOT EXISTS, and this is what says so. An upgrade that ran
        // half-way and was interrupted comes back through the same path.
        helper.createDatabase(DB_NAME, 2).close()
        helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS).close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.query("SELECT count(*) FROM call_summaries").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun notes_written_before_v5_become_searchable() {
        // The half of the search feature the migration is responsible for. FTS sync triggers only
        // fire on writes made *after* they exist, so without the explicit backfill every note already
        // on the phone would stay invisible to search for ever — silently, since the index would be
        // present, correct, and simply empty.
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO recording_notes (displayName, text, updatedAt) " +
                    "VALUES ('call.ogg', 'remember to send the contract', 100)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.query(
            "SELECT n.displayName FROM recording_notes AS n " +
                "JOIN recording_notes_fts AS f ON f.docid = n.rowid " +
                "WHERE recording_notes_fts MATCH 'contract'"
        ).use {
            assertTrue("a note written before the upgrade is not searchable after it", it.moveToFirst())
            assertEquals("call.ogg", it.getString(0))
        }
    }

    @Test
    fun a_note_written_after_v5_is_indexed_by_the_triggers() {
        // The other half: the triggers themselves. A backfill that worked while the triggers were
        // missing would pass the test above and then never index anything again.
        helper.createDatabase(DB_NAME, 4).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.execSQL(
            "INSERT INTO recording_notes (displayName, text, updatedAt) " +
                "VALUES ('later.ogg', 'he mentioned a refund', 200)"
        )

        db.query(
            "SELECT n.displayName FROM recording_notes AS n " +
                "JOIN recording_notes_fts AS f ON f.docid = n.rowid " +
                "WHERE recording_notes_fts MATCH 'refund'"
        ).use {
            assertTrue("the FTS sync triggers are not indexing new notes", it.moveToFirst())
            assertEquals("later.ogg", it.getString(0))
        }
    }

    @Test
    fun a_summary_written_before_v5_keeps_its_document_and_gains_an_empty_search_column() {
        // The summary text cannot be backfilled here — it lives inside a JSON document whose only
        // parser is in Kotlin — so the migration's job is narrower: keep the document, add the column
        // empty. Empty is what the repository later looks for to know what still needs indexing.
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO call_summaries (displayName, document, model, createdAt) " +
                    "VALUES ('call.ogg', '{\"intent\":\"chasing an invoice\"}', 'gemma', 100)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.query("SELECT document, searchText FROM call_summaries WHERE displayName = 'call.ogg'").use {
            assertTrue("the summary did not survive the upgrade", it.moveToFirst())
            assertTrue("the stored document was altered", "chasing an invoice" in it.getString(0))
            assertEquals("", it.getString(1))
        }
    }

    @Test
    fun tags_survive_the_upgrade_and_are_deduplicated_by_the_key() {
        // The composite primary key is the whole deduplication story: applying a tag twice has to be
        // a no-op, not a second row that shows the same chip twice on the same recording.
        helper.createDatabase(DB_NAME, 5).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.execSQL("INSERT OR REPLACE INTO recording_tags (displayName, tag) VALUES ('a.ogg', 'the flat')")
        db.execSQL("INSERT OR REPLACE INTO recording_tags (displayName, tag) VALUES ('a.ogg', 'the flat')")

        db.query("SELECT COUNT(*) FROM recording_tags WHERE displayName = 'a.ogg'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun renaming_a_tag_onto_an_existing_one_merges_rather_than_aborting() {
        // The reason the rename uses UPDATE OR REPLACE. A plain UPDATE would collide with the
        // composite primary key on any recording already carrying the destination tag and abort the
        // whole statement — leaving the rename half-applied across the library, which is worse than
        // not offering it at all.
        helper.createDatabase(DB_NAME, 5).close()
        val db = helper.runMigrationsAndValidate(DB_NAME, 8, true, *TranscriptDatabase.MIGRATIONS)

        db.execSQL("INSERT INTO recording_tags (displayName, tag) VALUES ('a.ogg', 'work')")
        db.execSQL("INSERT INTO recording_tags (displayName, tag) VALUES ('a.ogg', 'admin')")
        db.execSQL("INSERT INTO recording_tags (displayName, tag) VALUES ('b.ogg', 'work')")

        db.execSQL("UPDATE OR REPLACE recording_tags SET tag = 'admin' WHERE tag = 'work'")

        // a.ogg had both and keeps one; b.ogg's is renamed. Nothing is lost and nothing is doubled.
        db.query("SELECT COUNT(*) FROM recording_tags WHERE displayName = 'a.ogg'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT tag FROM recording_tags WHERE displayName = 'b.ogg'").use {
            assertTrue(it.moveToFirst())
            assertEquals("admin", it.getString(0))
        }
        db.query("SELECT COUNT(*) FROM recording_tags WHERE tag = 'work'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    private companion object {
        /** Deliberately not `transcripts.db` — the helper deletes it between runs. */
        const val DB_NAME = "migration-test-transcripts.db"
    }
}
