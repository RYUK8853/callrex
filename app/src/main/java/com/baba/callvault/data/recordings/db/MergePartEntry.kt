/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import androidx.room.Entity
import androidx.room.Index

/**
 * One original call inside a merged recording — the manifest that makes un-merging possible.
 *
 * A merge concatenates the parts' **encoded frames** without re-encoding them, so the merged file
 * physically contains each original. Un-merge is therefore a frame-exact cut rather than a lossy
 * reconstruction, and merging is free to delete the originals. That is only true while these rows
 * survive: they are the sole record of where one call ends and the next begins.
 *
 * Measured on 2026-09-02 (see `docs/dev-notes/2026-09-02-merge-recordings-plan.md`): merged AAC
 * frames are byte-identical to the originals either side of the join, and the decoded audio matched
 * in 0 of 240,640 samples — but only after removing a constant 1024-sample offset. That offset is
 * the encoder's priming delay, which the container expresses in an edit list and which plain
 * concatenation drops. [encoderDelayUs] exists so the split can put it back; without it every
 * un-merged part returns 21 ms early, which no test of durations would ever catch.
 *
 * Merging an already-merged recording **flattens**: its rows are copied into the new manifest with
 * [frameStart] re-based, never nested. So [partName] always names an original call, un-merge is
 * always one level, and nothing can appear twice — an original is deleted once merged, so it can
 * only ever live inside one merged recording.
 *
 * @param mergedName           The merged recording's display name.
 * @param position             1 for the recording the merge was started from, then selection order.
 * @param partName             The original's display name, which also restores its date, direction
 *                             and number — the filename template encodes all three.
 * @param frameStart           Index of this part's first encoded frame within the merged stream.
 *                             An integer, not a rounded millisecond, so the cut is exact.
 * @param frameCount           How many frames it owns.
 * @param startTimeUs          Where it begins on the merged timeline.
 * @param durationUs           How long it runs.
 * @param originalLastModified The original's timestamp, restored on un-merge.
 * @param encoderDelayUs       AAC priming / Opus pre-skip, re-applied on the split. See above.
 * @param encoderPaddingUs     End trim, same reason.
 */
@Entity(
    tableName = "merge_parts",
    primaryKeys = ["mergedName", "position"],
    indices = [Index("partName")]
)
data class MergePartEntry(
    val mergedName: String,
    val position: Int,
    val partName: String,
    val frameStart: Int,
    val frameCount: Int,
    val startTimeUs: Long,
    val durationUs: Long,
    val originalLastModified: Long,
    val encoderDelayUs: Long,
    val encoderPaddingUs: Long,
)
