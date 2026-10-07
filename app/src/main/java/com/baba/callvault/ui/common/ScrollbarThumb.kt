/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a scrollbar's thumb sits along its track.
 *
 * Compose's scrolling has no visible scrollbar, and the log viewer scrolled with no sign that it could, so nothing
 * said there was more to read (seen on the OP9, 2026-09-11). Pure so the geometry is tested on its own.
 */
object ScrollbarThumb {

    data class Thumb(val start: Float, val length: Float)

    /**
     * @param scroll how far the content is scrolled, in pixels.
     * @param maxScroll how far it can scroll; 0 when it all fits, and then there is no thumb.
     * @param viewport the visible length of the track, in pixels.
     * @param minLength the shortest thumb worth drawing — a long log would otherwise get a sliver nobody can see.
     */
    fun of(scroll: Int, maxScroll: Int, viewport: Float, minLength: Float): Thumb? {
        if (maxScroll <= 0 || maxScroll == Int.MAX_VALUE || viewport <= 0f) return null
        val content = viewport + maxScroll
        val length = (viewport * viewport / content).coerceAtLeast(minLength).coerceAtMost(viewport)
        val fraction = (scroll.toFloat() / maxScroll).coerceIn(0f, 1f)
        return Thumb(start = (viewport - length) * fraction, length = length)
    }
}

/**
 * Draws a vertical scrollbar along the right edge for [state].
 *
 * Put it **before** `verticalScroll` in the chain, so it is drawn at the size of the visible area rather than moving
 * with the content.
 */
fun Modifier.verticalScrollbar(state: ScrollState, color: Color, thickness: Dp = 4.dp, minLength: Dp = 32.dp): Modifier =
    drawWithContent {
        drawContent()
        val thumb = ScrollbarThumb.of(state.value, state.maxValue, size.height, minLength.toPx()) ?: return@drawWithContent
        val t = thickness.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - t, thumb.start),
            size = Size(t, thumb.length),
            cornerRadius = CornerRadius(t / 2),
        )
    }

/** The horizontal counterpart of [verticalScrollbar], along the bottom edge. Same ordering rule. */
fun Modifier.horizontalScrollbar(state: ScrollState, color: Color, thickness: Dp = 4.dp, minLength: Dp = 32.dp): Modifier =
    drawWithContent {
        drawContent()
        val thumb = ScrollbarThumb.of(state.value, state.maxValue, size.width, minLength.toPx()) ?: return@drawWithContent
        val t = thickness.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(thumb.start, size.height - t),
            size = Size(thumb.length, t),
            cornerRadius = CornerRadius(t / 2),
        )
    }
