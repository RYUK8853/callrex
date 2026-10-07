/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning a duration into the number of minutes we quote.
 *
 * Rounded **up**, and that is the whole point of this file. Truncating lost up to 59 seconds on every
 * quote — a 6.9-minute run was announced as "6 minutes" — and it stacked on an estimate that already
 * aims at the middle, so the dialog was biased low twice over. Low is the bad direction: a run that
 * overshoots its own estimate reads as a hang, which is the complaint behind issue #26.
 *
 * It also cost us a visible improvement. After a slow run pulled the stored figure from 6.0 to 6.4
 * minutes, truncation still printed "6", so the maintainer reasonably reported that the estimate had
 * not moved at all.
 */
class EstimateRoundingTest {

    @Test
    fun `a part minute is rounded up, not away`() {
        // The reported case: 6.4 minutes used to print as "6".
        assertEquals(7, quotedMinutes(6 * 60_000L + 24_000L))
        assertEquals(7, quotedMinutes(6 * 60_000L + 1_000L))
    }

    @Test
    fun `an exact number of minutes is left alone`() {
        // Rounding up must not mean always adding one.
        assertEquals(6, quotedMinutes(6 * 60_000L))
        assertEquals(1, quotedMinutes(60_000L))
    }

    @Test
    fun `anything above zero is at least one minute`() {
        assertEquals(1, quotedMinutes(1L))
        assertEquals(0, quotedMinutes(0L))
    }

    @Test
    fun `just under an hour rolls into the hour rather than printing sixty minutes`() {
        // 59 min 59 s rounds to 60, which must read as "1 h 0 min" and never "60 minutes".
        val minutes = quotedMinutes(59 * 60_000L + 59_000L)
        assertEquals(60, minutes)
        assertEquals(1, minutes / 60)
        assertEquals(0, minutes % 60)
    }

    @Test
    fun `hours and minutes split from the rounded total`() {
        // 1 h 30 min 30 s -> 1 h 31 min, not 1 h 30 min.
        val minutes = quotedMinutes(90 * 60_000L + 30_000L)
        assertEquals(91, minutes)
        assertEquals(1, minutes / 60)
        assertEquals(31, minutes % 60)
    }

    @Test
    fun `a negative or nonsense duration does not produce a negative quote`() {
        assertEquals(0, quotedMinutes(-1L))
    }
}
