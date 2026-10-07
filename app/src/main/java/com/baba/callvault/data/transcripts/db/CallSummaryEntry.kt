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
 * A model's summary of a call, stored beside the transcript it was read from.
 *
 * It belongs in this database rather than the recordings catalog for the same reason a transcript
 * does, only more so: the catalog is a rebuildable cache with a destructive fallback, and a summary
 * costs roughly ninety seconds of the user's CPU and battery on top of the transcription it depends
 * on. Losing one to an unrelated schema bump would be a real cost to the person holding the phone.
 *
 * **The summary is kept as its JSON document, not as six columns.**
 * [com.baba.callvault.summary.CallSummary] is the only thing that reads it, and nothing ever queries
 * or sorts on a field inside it. One column means a grammar that grows a field later is a prompt
 * change rather than a migration, and it keeps a single parser — the one every summary already
 * passes through on its way out of the model — rather than a second, quieter copy of the shape
 * living in the schema.
 *
 * That reasoning originally added "and search runs over the transcript's FTS rows instead", which
 * stopped being true when summaries became searchable in their own right. The JSON is still the
 * stored form; [searchText] carries the prose beside it precisely so the JSON never has to be
 * indexed.
 */
@Entity(tableName = "call_summaries")
data class CallSummaryEntry(
    /** The recording's `displayName`, this database's natural key throughout. */
    @PrimaryKey val displayName: String,
    /** [com.baba.callvault.summary.CallSummary.toJson]. Validated before it was written. */
    val document: String,
    /**
     * Which model wrote it.
     *
     * Recorded because summaries are not comparable across models — a card produced by a smaller
     * model, or by one that has since been replaced, is worth knowing about when the user says the
     * summary is wrong. It is also what lets a future version offer to redo the old ones.
     */
    val model: String,
    val createdAt: Long = 0L,
    /**
     * The same summary as plain prose, for [CallSummaryFts] to index.
     *
     * Stored rather than derived at query time because [document] is JSON: an index over it would
     * match on the key names and on `org.json`'s escapes. See
     * [com.baba.callvault.summary.CallSummary.searchableText].
     *
     * Empty means "written before this column existed" — a summary from an older install, which
     * [com.baba.callvault.data.transcripts.TranscriptRepository] backfills the first time a search
     * runs. It never means "this summary has no text".
     */
    val searchText: String = ""
)

/**
 * Full-text mirror of [CallSummaryEntry.searchText].
 *
 * A summary is where the *outcome* of a call is written down — "he agreed to send the invoice" — and
 * those words are often not spoken anywhere in the transcript. Without this, the one place a decision
 * is recorded in plain language is the one place search cannot reach.
 *
 * Same tokenizer choice as [TranscriptSegmentFts], for the same reason: unicode61, never porter,
 * because these calls are Hebrew and stemming would mangle the index.
 *
 * `contentEntity` makes this a view over `call_summaries` rather than a second copy of every
 * summary, so Room's triggers keep it in sync and deleting a summary drops its search row with it.
 */
@Fts4(contentEntity = CallSummaryEntry::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "call_summaries_fts")
data class CallSummaryFts(val searchText: String)
