/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recognised segment of a transcript.
 *
 * Timestamps are milliseconds (whisper reports centiseconds natively; the JNI converts). They are
 * stored rather than derived because they are what makes a transcript navigable — tapping a line
 * seeks the player to that moment.
 *
 * [speaker] is reserved for speaker recognition and is null today. The column exists now so adding
 * labels later is a data change rather than a migration. It stays a nullable free-form string instead
 * of an enum because which channel is the near party is an OEM detail, resolved at display time.
 */
@Entity(
    tableName = "transcript_segments",
    indices = [Index("displayName")]
)
data class TranscriptSegmentEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null
)

/**
 * Full-text mirror of [TranscriptSegmentEntry.text] — the real payoff of storing transcripts at all,
 * making years of calls searchable.
 *
 * The tokenizer is **unicode61, never porter**. Porter stems English; these calls are Hebrew, and
 * stemming them would mangle the index. unicode61 splits on non-alphanumerics, which is correct for
 * Hebrew script — with the known limitation, accepted in the spec, that there is no Hebrew
 * morphological stemming, so inflected forms will not match.
 *
 * Declared with `contentEntity` so the index is a view over the segments table rather than a second
 * copy of every transcript: Room keeps the two in sync and deleting a segment drops its search row.
 */
@Fts4(contentEntity = TranscriptSegmentEntry::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "transcript_segments_fts")
data class TranscriptSegmentFts(val text: String)
