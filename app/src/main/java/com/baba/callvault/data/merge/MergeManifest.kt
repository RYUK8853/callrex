/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import com.baba.callvault.data.recordings.db.MergePartEntry

/**
 * Builds a merged recording's manifest from what went into it — pure arithmetic, no database.
 *
 * Separated from [MergeService] because this is the part that is easy to get subtly wrong and
 * impossible to notice: an offset that is out by one part's worth still produces a file that plays,
 * and only reveals itself when someone un-merges months later.
 *
 * **Flattening is the whole job.** Merging a recording that was itself merged must contribute that
 * recording's *parts*, never the recording, so a manifest only ever names original calls and
 * un-merge is always one level deep.
 */
object MergeManifest {

    /** One thing being merged: the call, and its own parts if it was already a merge. */
    data class Input(
        val displayName: String,
        val lastModified: Long,
        /** Empty for an ordinary call; its manifest for one that was merged before. */
        val existingParts: List<MergePartEntry>,
    )

    /**
     * Combines [inputs] with where each landed in the joined stream.
     *
     * @param boundaries one per input, in the same order, from [AudioConcat.concat].
     * @return the flattened manifest, numbered from 1 in playback order. [MergePartEntry.mergedName]
     *         is left blank for the caller to stamp once the merged file has a name.
     */
    fun flatten(inputs: List<Input>, boundaries: List<AudioConcat.PartBoundary>): List<MergePartEntry> {
        require(inputs.size == boundaries.size) { "Every input needs to have landed somewhere" }

        val out = mutableListOf<MergePartEntry>()
        inputs.forEachIndexed { i, input ->
            val landed = boundaries[i]
            if (input.existingParts.isEmpty()) {
                out += MergePartEntry(
                    mergedName = "",
                    position = out.size + 1,
                    partName = input.displayName,
                    frameStart = landed.frameStart,
                    frameCount = landed.frameCount,
                    startTimeUs = landed.startTimeUs,
                    durationUs = landed.durationUs,
                    originalLastModified = input.lastModified,
                    encoderDelayUs = landed.encoderDelayUs,
                    encoderPaddingUs = landed.encoderPaddingUs,
                )
            } else {
                // Its parts' offsets are relative to its own stream, so each shifts by wherever that
                // stream landed. The codec delays are NOT touched — they describe each part's
                // original encode, which re-merging does not change.
                input.existingParts.sortedBy { it.position }.forEach { part ->
                    out += part.copy(
                        mergedName = "",
                        position = out.size + 1,
                        frameStart = landed.frameStart + part.frameStart,
                        startTimeUs = landed.startTimeUs + part.startTimeUs,
                    )
                }
            }
        }
        return out
    }
}
