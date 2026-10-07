/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import com.baba.callvault.data.recordings.db.MergePartEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manifest arithmetic, especially flattening a merge of a merge.
 *
 * Worth its own tests because the failure mode is invisible: an offset out by one part's worth still
 * produces a file that plays perfectly, and only shows itself when somebody un-merges months later
 * and gets the wrong audio back.
 */
class MergeManifestTest {

    private fun boundary(frameStart: Int, frameCount: Int, delayUs: Long = 21_333L) =
        AudioConcat.PartBoundary(
            frameStart = frameStart,
            frameCount = frameCount,
            startTimeUs = frameStart * 21_333L,
            durationUs = frameCount * 21_333L,
            encoderDelayUs = delayUs,
            encoderPaddingUs = 0L,
        )

    private fun input(name: String, parts: List<MergePartEntry> = emptyList()) =
        MergeManifest.Input(displayName = name, lastModified = 1_700_000_000_000L, existingParts = parts)

    private fun storedPart(
        position: Int,
        partName: String,
        frameStart: Int,
        frameCount: Int,
        delayUs: Long = 21_333L,
    ) = MergePartEntry(
        mergedName = "m1.m4a",
        position = position,
        partName = partName,
        frameStart = frameStart,
        frameCount = frameCount,
        startTimeUs = frameStart * 21_333L,
        durationUs = frameCount * 21_333L,
        originalLastModified = 1_600_000_000_000L + position,
        encoderDelayUs = delayUs,
        encoderPaddingUs = 0L,
    )

    @Test
    fun two_ordinary_calls_become_two_parts_numbered_in_the_order_given() {
        // Arrange — b is the primary here, deliberately not the chronological first
        val inputs = listOf(input("b.m4a"), input("a.m4a"))
        val boundaries = listOf(boundary(0, 142), boundary(142, 236))

        // Act
        val manifest = MergeManifest.flatten(inputs, boundaries)

        // Assert
        assertEquals(listOf(1, 2), manifest.map { it.position })
        assertEquals(listOf("b.m4a", "a.m4a"), manifest.map { it.partName })
        assertEquals(listOf(0, 142), manifest.map { it.frameStart })
        assertEquals(listOf(142, 236), manifest.map { it.frameCount })
    }

    @Test
    fun merging_a_merged_call_contributes_its_parts_and_never_itself() {
        // Arrange — m1 was [a, b]; now m1 is the primary and c follows it
        val m1 = listOf(
            storedPart(1, "a.m4a", frameStart = 0, frameCount = 236),
            storedPart(2, "b.m4a", frameStart = 236, frameCount = 142),
        )
        val inputs = listOf(input("m1.m4a", m1), input("c.m4a"))
        val boundaries = listOf(boundary(0, 378), boundary(378, 100))

        // Act
        val manifest = MergeManifest.flatten(inputs, boundaries)

        // Assert — three parts, all originals, m1 itself nowhere in sight
        assertEquals(listOf("a.m4a", "b.m4a", "c.m4a"), manifest.map { it.partName })
        assertEquals(listOf(1, 2, 3), manifest.map { it.position })
        assertEquals(listOf(0, 236, 378), manifest.map { it.frameStart })
    }

    @Test
    fun a_merged_call_that_is_not_the_primary_has_its_parts_shifted() {
        // Arrange — c is primary this time, so a and b move along by c's length
        val m1 = listOf(
            storedPart(1, "a.m4a", frameStart = 0, frameCount = 236),
            storedPart(2, "b.m4a", frameStart = 236, frameCount = 142),
        )
        val inputs = listOf(input("c.m4a"), input("m1.m4a", m1))
        val boundaries = listOf(boundary(0, 100), boundary(100, 378))

        // Act
        val manifest = MergeManifest.flatten(inputs, boundaries)

        // Assert
        assertEquals(listOf("c.m4a", "a.m4a", "b.m4a"), manifest.map { it.partName })
        assertEquals(listOf(0, 100, 336), manifest.map { it.frameStart })
        // and the frames still tile the stream exactly, with no gap and no overlap
        manifest.zipWithNext().forEach { (x, y) ->
            assertEquals(x.frameStart + x.frameCount, y.frameStart)
        }
    }

    @Test
    fun re_merging_does_not_disturb_a_parts_codec_delay() {
        // The delay describes the part's ORIGINAL encode. Shifting it by the new offset — an easy
        // mistake, since every other field shifts — would make that part come back wrong.
        val m1 = listOf(storedPart(1, "a.m4a", 0, 236, delayUs = 21_333L))
        val inputs = listOf(input("c.m4a"), input("m1.m4a", m1))
        val boundaries = listOf(boundary(0, 100, delayUs = 12_345L), boundary(100, 236, delayUs = 999L))

        val manifest = MergeManifest.flatten(inputs, boundaries)

        assertEquals(12_345L, manifest[0].encoderDelayUs) // c's own, from the join
        assertEquals(21_333L, manifest[1].encoderDelayUs) // a's original, untouched by re-merging
    }

    @Test
    fun two_merged_calls_flatten_into_one_flat_list() {
        val m1 = listOf(
            storedPart(1, "a.m4a", 0, 100),
            storedPart(2, "b.m4a", 100, 100),
        )
        val m2 = listOf(
            storedPart(1, "c.m4a", 0, 50),
            storedPart(2, "d.m4a", 50, 50),
        )
        val inputs = listOf(input("m1.m4a", m1), input("m2.m4a", m2))
        val boundaries = listOf(boundary(0, 200), boundary(200, 100))

        val manifest = MergeManifest.flatten(inputs, boundaries)

        assertEquals(listOf("a.m4a", "b.m4a", "c.m4a", "d.m4a"), manifest.map { it.partName })
        assertEquals(listOf(1, 2, 3, 4), manifest.map { it.position })
        assertEquals(listOf(0, 100, 200, 250), manifest.map { it.frameStart })
    }
}
