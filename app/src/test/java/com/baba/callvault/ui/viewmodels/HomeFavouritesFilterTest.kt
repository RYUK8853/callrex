/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The starred facet of [HomeViewModel.HomeUiState.filteredRecordings].
 *
 * Robolectric only because [RecordingItem] carries a [Uri]; the filter itself is a pure function of
 * state, which is the whole reason the starred set is held in the state rather than queried.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeFavouritesFilterTest {

    @Test
    fun `shows every recording when the starred filter is off`() {
        val state = stateWith(favouritesOnly = false, favourites = setOf("b.m4a"))

        assertEquals(listOf("a.m4a", "b.m4a", "c.m4a"), state.filteredRecordings.map { it.displayName })
    }

    @Test
    fun `shows only starred recordings when the filter is on`() {
        val state = stateWith(favouritesOnly = true, favourites = setOf("a.m4a", "c.m4a"))

        assertEquals(listOf("a.m4a", "c.m4a"), state.filteredRecordings.map { it.displayName })
    }

    @Test
    fun `combines with the tag facet rather than replacing it`() {
        // Both narrow, so only the recording carrying the tag AND the star survives. A star must not
        // widen a list the user had already narrowed.
        val state = stateWith(
            favouritesOnly = true,
            favourites = setOf("a.m4a", "b.m4a"),
            tagFilter = "work",
            tagsByRecording = mapOf("b.m4a" to setOf("work"), "c.m4a" to setOf("work"))
        )

        assertEquals(listOf("b.m4a"), state.filteredRecordings.map { it.displayName })
    }

    @Test
    fun `yields nothing when the filter is on and nothing is starred`() {
        // The empty result is correct; the ViewModel's job is to make sure this state is unreachable
        // by clearing the filter when the last star goes, which is tested where that logic lives.
        val state = stateWith(favouritesOnly = true, favourites = emptySet())

        assertEquals(emptyList<String>(), state.filteredRecordings.map { it.displayName })
    }

    private fun stateWith(
        favouritesOnly: Boolean,
        favourites: Set<String>,
        tagFilter: String? = null,
        tagsByRecording: Map<String, Set<String>> = emptyMap()
    ) = HomeViewModel.HomeUiState(
        recordings = listOf("a.m4a", "b.m4a", "c.m4a").map(::item),
        favouritesOnly = favouritesOnly,
        favourites = favourites,
        tagFilter = tagFilter,
        tagsByRecording = tagsByRecording
    )

    private fun item(name: String) = RecordingItem(
        uri = Uri.parse("content://test/$name"),
        displayName = name,
        sizeBytes = 1_000L,
        lastModified = 0L,
        direction = null,
        displayDate = null,
        startedAtMillis = null,
        number = null
    )
}
