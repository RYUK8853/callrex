/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class UntrackedCascadeTest {

    @Test
    fun a_name_whose_only_copy_the_sweep_deleted_loses_its_transcript() {
        val orphaned = UntrackedCascade.orphanedNames(
            deleted = listOf("call.m4a"),
            survivingUntracked = emptyList(),
            cataloguedWithCopies = emptyList(),
        )

        assertEquals(setOf("call.m4a"), orphaned)
    }

    @Test
    fun a_name_with_another_untracked_copy_left_keeps_its_transcript() {
        // The Drive copy expired and went; the device copy is younger and stayed.
        val orphaned = UntrackedCascade.orphanedNames(
            deleted = listOf("call.m4a"),
            survivingUntracked = listOf("call.m4a"),
            cataloguedWithCopies = emptyList(),
        )

        assertEquals(emptySet<String>(), orphaned)
    }

    @Test
    fun a_name_the_catalog_still_has_a_copy_of_keeps_its_transcript() {
        // Untracked on the device, catalogued on Drive: the recording still exists.
        val orphaned = UntrackedCascade.orphanedNames(
            deleted = listOf("call.m4a"),
            survivingUntracked = emptyList(),
            cataloguedWithCopies = listOf("call.m4a"),
        )

        assertEquals(emptySet<String>(), orphaned)
    }

    @Test
    fun deleting_nothing_orphans_nothing() {
        val orphaned = UntrackedCascade.orphanedNames(
            deleted = emptyList(),
            survivingUntracked = listOf("call.m4a"),
            cataloguedWithCopies = listOf("other.m4a"),
        )

        assertEquals(emptySet<String>(), orphaned)
    }

    @Test
    fun each_deleted_name_is_judged_on_its_own() {
        val orphaned = UntrackedCascade.orphanedNames(
            deleted = listOf("gone.m4a", "kept.m4a", "also-gone.m4a"),
            survivingUntracked = listOf("kept.m4a"),
            cataloguedWithCopies = emptyList(),
        )

        assertEquals(setOf("gone.m4a", "also-gone.m4a"), orphaned)
    }
}
