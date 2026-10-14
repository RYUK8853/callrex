/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.takeover.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database hosting the locally-cached SIP calls handled by the voice agent
 * ([TakeoverCallEntry]). A separate file from the recordings catalog: it has its own lifecycle
 * (it is a Node cache, not a recording artifact) and its own, simpler destructive-migration story —
 * the rows are re-fetchable from Node, so a schema bump just drops and refills on the next poll.
 */
@Database(entities = [TakeoverCallEntry::class], version = 1, exportSchema = false)
abstract class TakeoverDatabase : RoomDatabase() {

    abstract fun takeoverDao(): TakeoverDao

    companion object {
        @Volatile
        private var INSTANCE: TakeoverDatabase? = null

        fun get(context: Context): TakeoverDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TakeoverDatabase::class.java,
                    "takeover.db",
                ).fallbackToDestructiveMigration(dropAllTables = true)
                    .build().also { INSTANCE = it }
            }
    }
}
