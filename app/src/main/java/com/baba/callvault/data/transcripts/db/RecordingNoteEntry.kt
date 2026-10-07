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
import androidx.room.PrimaryKey

/**
 * What the user wrote about a call, in their own words.
 *
 * Not a transcript, which is why it exists alongside one: a transcript records what was said, a note
 * records what it meant — the price agreed, the thing to chase, the reason to ring back.
 *
 * It lives in this database and **not** in the recordings catalog for a reason that matters more here
 * than it does for transcripts: the catalog is a rebuildable cache with a destructive fallback, and a
 * transcript can at least be regenerated from the audio at the cost of CPU. A note cannot be
 * regenerated from anything. Losing one loses it for good.
 */
@Entity(tableName = "recording_notes")
data class RecordingNoteEntry(
    @PrimaryKey val displayName: String,
    val text: String,
    val updatedAt: Long = 0L
)

/**
 * A recording's drawn shape, cached so it is computed once rather than on every visit.
 *
 * Purely derived — it can be rebuilt from the audio — so losing it costs only the decode. It sits here
 * anyway because it is keyed by the same recording and dies in the same cascade; a separate cache
 * would be a second thing to remember to clean up when a call is deleted.
 */
@Entity(tableName = "recording_waveforms")
data class RecordingWaveformEntry(
    @PrimaryKey val displayName: String,
    /** Peaks encoded by [com.baba.callvault.data.waveform.Waveform.encode]. */
    val peaks: String,
    val updatedAt: Long = 0L
)

/**
 * Full-text mirror of [RecordingNoteEntry.text].
 *
 * A note is the only text in this database a *person* wrote, which makes it the most deliberate thing
 * in it: nobody types a note about a call they did not care about. Leaving it out of search while
 * indexing every automatically-produced word around it had the priority exactly backwards.
 *
 * Indexed directly, with no companion column — unlike a summary, a note is already plain text.
 */
@Fts4(contentEntity = RecordingNoteEntry::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "recording_notes_fts")
data class RecordingNoteFts(val text: String)
