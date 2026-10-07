/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerNamesTest {

    @Test
    fun `names the far channel after the contact and the other one after the user`() {
        // Arrange
        val names = names(ChannelMap.A_IS_FAR)

        // Act & Assert
        assertEquals("Dana", names.of("A"))
        assertEquals("You", names.of("B"))
    }

    @Test
    fun `follows the mapping the other way round without touching the stored labels`() {
        val names = names(ChannelMap.B_IS_FAR)

        assertEquals("You", names.of("A"))
        assertEquals("Dana", names.of("B"))
    }

    @Test
    fun `stays neutral while the mapping is unknown`() {
        // A confident wrong attribution — the user's words shown as the other person's — is far
        // worse than no attribution at all.
        val names = names(ChannelMap.UNKNOWN)

        assertEquals("Speaker A", names.of("A"))
        assertEquals("Speaker B", names.of("B"))
    }

    @Test
    fun `names nothing for a segment that was never attributed`() {
        assertNull(names(ChannelMap.A_IS_FAR).of(null))
    }

    @Test
    fun `names nothing for a label it does not recognise`() {
        // Plan 4 may write labels from a diarization model into the same column.
        assertNull(names(ChannelMap.A_IS_FAR).of("speaker_3"))
    }

    private fun names(map: ChannelMap) = SpeakerNames(
        map = map,
        you = "You",
        contact = "Dana",
        sideA = "Speaker A",
        sideB = "Speaker B"
    )
}
