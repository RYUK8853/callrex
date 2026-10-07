/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When one call's guess about the channels becomes the answer.
 *
 * A single call is not enough. Ringback can leak between channels, a carrier can send none at all,
 * and a far party who answers instantly leaves no window — so one observation is a hypothesis. The
 * cost of believing a wrong one is high and quiet: every transcript from then on shows the user's
 * own words as the other person's, and nothing on screen would suggest anything is amiss.
 *
 * So: two calls must agree, and a disagreement must be able to unseat a belief rather than being
 * outvoted by history.
 */
class ChannelMapCorroborationTest {

    @Test
    fun `one observation is not enough`() {
        assertEquals(
            ChannelMap.UNKNOWN,
            ChannelMapCorroboration.trusted(listOf(ChannelMap.A_IS_FAR))
        )
    }

    @Test
    fun `two agreeing calls settle it`() {
        assertEquals(
            ChannelMap.A_IS_FAR,
            ChannelMapCorroboration.trusted(listOf(ChannelMap.A_IS_FAR, ChannelMap.A_IS_FAR))
        )
    }

    @Test
    fun `two calls that disagree settle nothing`() {
        // One of them is wrong and there is no way to tell which. Refusing costs neutral labels;
        // guessing costs a permanently mislabelled transcript history.
        assertEquals(
            ChannelMap.UNKNOWN,
            ChannelMapCorroboration.trusted(listOf(ChannelMap.A_IS_FAR, ChannelMap.B_IS_FAR))
        )
    }

    @Test
    fun `a clear majority outweighs a single outlier`() {
        // Once three calls agree, one dissenting call is noise rather than a contradiction.
        assertEquals(
            ChannelMap.B_IS_FAR,
            ChannelMapCorroboration.trusted(
                listOf(ChannelMap.B_IS_FAR, ChannelMap.A_IS_FAR, ChannelMap.B_IS_FAR, ChannelMap.B_IS_FAR)
            )
        )
    }

    @Test
    fun `an even split settles nothing however many calls`() {
        assertEquals(
            ChannelMap.UNKNOWN,
            ChannelMapCorroboration.trusted(
                listOf(ChannelMap.A_IS_FAR, ChannelMap.B_IS_FAR, ChannelMap.A_IS_FAR, ChannelMap.B_IS_FAR)
            )
        )
    }

    @Test
    fun `calls that learned nothing do not count towards agreement`() {
        // Most calls will be UNKNOWN — incoming ones have no ringback at all. They are not votes.
        assertEquals(
            ChannelMap.UNKNOWN,
            ChannelMapCorroboration.trusted(
                listOf(ChannelMap.UNKNOWN, ChannelMap.A_IS_FAR, ChannelMap.UNKNOWN)
            )
        )
    }

    @Test
    fun `nothing observed at all is unknown`() {
        assertEquals(ChannelMap.UNKNOWN, ChannelMapCorroboration.trusted(emptyList()))
        assertEquals(
            ChannelMap.UNKNOWN,
            ChannelMapCorroboration.trusted(listOf(ChannelMap.UNKNOWN, ChannelMap.UNKNOWN))
        )
    }

    @Test
    fun `a device that starts disagreeing loses the belief`() {
        // The mapping is a property of the device, so it should not change — but if the evidence
        // stops agreeing, something assumed here is wrong, and neutral labels are the honest answer
        // until it settles again.
        val settled = listOf(ChannelMap.A_IS_FAR, ChannelMap.A_IS_FAR)
        assertEquals(ChannelMap.A_IS_FAR, ChannelMapCorroboration.trusted(settled))

        val nowDisputed = listOf(ChannelMap.B_IS_FAR, ChannelMap.A_IS_FAR, ChannelMap.A_IS_FAR, ChannelMap.B_IS_FAR)
        assertEquals(ChannelMap.UNKNOWN, ChannelMapCorroboration.trusted(nowDisputed))
    }
}
