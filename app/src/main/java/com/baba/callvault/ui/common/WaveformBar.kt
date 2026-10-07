/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The recording, drawn from its own audio, with the played part filled in.
 *
 * Deliberately not an animation. A bar that moves regardless of what is playing tells the user
 * nothing and implies a measurement nobody took; this one shows where the talking actually is, so a
 * long pause looks like a long pause and a shouting match looks like one.
 *
 * Scrubbing works on it directly — a waveform you can see but not aim at is decoration.
 *
 * @param peaks    one value per bar in 0..1, or empty while it is still being computed.
 * @param progress how far through, 0..1.
 * @param onSeekTo fraction of the recording to jump to.
 */
@Composable
fun WaveformBar(
    peaks: FloatArray,
    progress: Float,
    onSeekTo: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val played = MaterialTheme.colorScheme.primary
    val remaining = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        if (peaks.isEmpty()) {
            // A resting line, never a spinner. The shape has to be read off the audio, which takes a
            // moment on a long call — but a spinner turns that moment into something the user is made
            // to wait for, and it replaces the control rather than filling it in. The line is the same
            // width and in the same place as the bars that arrive, so nothing jumps when they do.
            Canvas(modifier = Modifier.fillMaxWidth().height(HEIGHT)) {
                val centre = size.height / 2f
                drawRoundedBar(
                    left = 0f,
                    top = centre - MIN_BAR_PX / 2f,
                    width = size.width,
                    height = MIN_BAR_PX,
                    color = remaining
                )
            }
            return@Box
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(HEIGHT)
                .pointerInput(peaks.size) {
                    detectTapGestures { offset -> onSeekTo((offset.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(peaks.size) {
                    detectHorizontalDragGestures { change, _ ->
                        onSeekTo((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
        ) {
            val slot = size.width / peaks.size
            val barWidth = max(1f, slot * BAR_FILL)
            val playedBars = (peaks.size * progress.coerceIn(0f, 1f)).roundToInt()
            val centre = size.height / 2f

            peaks.forEachIndexed { index, peak ->
                // A floor, so silence still draws a line: gaps in the bar would read as damage to the
                // recording rather than as quiet.
                val barHeight = max(MIN_BAR_PX, peak * size.height)
                val left = index * slot + (slot - barWidth) / 2f
                drawRoundedBar(
                    left = left,
                    top = centre - barHeight / 2f,
                    width = barWidth,
                    height = barHeight,
                    color = if (index < playedBars) played else remaining
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedBar(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color
) {
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(left, top),
        size = androidx.compose.ui.geometry.Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
    )
}

private val HEIGHT = 64.dp

/** How much of each bar's slot the bar fills; the rest is the gap that makes them read as bars. */
private const val BAR_FILL = 0.6f

/** Silence still draws something, so a quiet stretch reads as quiet rather than as missing. */
private const val MIN_BAR_PX = 2f
