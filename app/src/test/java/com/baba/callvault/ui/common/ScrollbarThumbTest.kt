/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the log viewer's scrollbar thumb sits. The viewer used to scroll with no sign that it could (OP9, 2026-09-11).
 */
class ScrollbarThumbTest {

    @Test
    fun nothing_to_scroll_means_no_thumb() {
        assertNull(ScrollbarThumb.of(scroll = 0, maxScroll = 0, viewport = 1000f, minLength = 40f))
    }

    @Test
    fun the_thumb_is_as_long_as_the_visible_share_of_the_content() {
        // 1000 visible of 4000 total → a quarter of the track.
        val thumb = ScrollbarThumb.of(scroll = 0, maxScroll = 3000, viewport = 1000f, minLength = 10f)!!
        assertEquals(250f, thumb.length, 0.01f)
        assertEquals(0f, thumb.start, 0.01f)
    }

    @Test
    fun at_the_end_of_the_content_the_thumb_touches_the_end_of_the_track() {
        val thumb = ScrollbarThumb.of(scroll = 3000, maxScroll = 3000, viewport = 1000f, minLength = 10f)!!
        assertEquals(1000f, thumb.start + thumb.length, 0.01f)
    }

    /** A 500-line log would otherwise get a thumb a few pixels long — as good as no scrollbar at all. */
    @Test
    fun a_very_long_log_still_gets_a_thumb_you_can_see() {
        val thumb = ScrollbarThumb.of(scroll = 50_000, maxScroll = 100_000, viewport = 1000f, minLength = 48f)!!
        assertEquals(48f, thumb.length, 0.01f)
        assertTrue(thumb.start >= 0f && thumb.start + thumb.length <= 1000f)
    }
}
