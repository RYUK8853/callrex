/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.db.TranscriptSearchHit

/** One search result, ready to render and to act on. */
data class TranscriptSearchRow(
    val displayName: String,
    /** The recording to open — carried here so a tap never has to look the row up again. */
    val uri: Uri,
    /** Who the call was with, by the same precedence the recordings list uses. */
    val title: String,
    /** The spoken text that matched, on one line. */
    val snippet: String,
    /** Where in the call it was said. */
    val startMs: Long
)

/**
 * Joins full-text hits back onto the recordings the user can actually open.
 *
 * The search itself lives in `TranscriptRepository`; this is the half that decides what a hit *means*
 * on screen. It is separate, and pure, because the interesting failure is not a crash — it is a row
 * that renders perfectly and does nothing when tapped.
 */
object TranscriptSearch {

    /**
     * Rows for [hits], in the order [recordings] is already in.
     *
     * Hits are matched by display name, and a hit with no matching recording is **dropped**: transcripts
     * are deleted through a cascade that a failure could leave half-done, and a result pointing at
     * audio that no longer exists is worse than one result fewer.
     *
     * Ordering follows [recordings] rather than the database: FTS returns storage order, which would
     * scatter results through time, while the list the user is looking at is already sorted the way
     * they expect.
     */
    fun rowsFor(
        hits: List<TranscriptSearchHit>,
        recordings: List<RecordingItem>
    ): List<TranscriptSearchRow> {
        if (hits.isEmpty()) return emptyList()

        val hitsByName = hits.associateBy { it.displayName }

        return recordings.mapNotNull { item ->
            val hit = hitsByName[item.displayName] ?: return@mapNotNull null
            TranscriptSearchRow(
                displayName = item.displayName,
                uri = item.uri,
                title = RecordingLabel.of(item) ?: item.displayName,
                snippet = hit.snippet.collapseWhitespace(),
                startMs = hit.startMs
            )
        }
    }

    /** Segment text may carry newlines; a result row that grows to three lines breaks the list. */
    private fun String.collapseWhitespace(): String = trim().replace(Regex("\\s+"), " ")
}
