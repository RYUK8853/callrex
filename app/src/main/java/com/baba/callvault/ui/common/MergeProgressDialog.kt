/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baba.callvault.R
import kotlinx.coroutines.delay

/** How long the "done" state stays up before closing itself. */
private const val AUTO_CLOSE_SECONDS = 5

/**
 * The height the progress body always occupies.
 *
 * Fixed so that finishing does not resize the card. Sized to a ring plus two lines and no more —
 * the card it sits on is now 260dp wide rather than the platform's full dialog width, so the body
 * no longer has to fill space that was never meant for it.
 */
private val PROGRESS_BODY_HEIGHT = 164.dp

private val RING_SIZE = 84.dp
private val RING_STROKE = 6.dp

/** What the merge/un-merge card is doing, or null while the user is still choosing. */
data class MergeProgressState(
    val isUnMerge: Boolean,
    val current: Int,
    val total: Int,
    val finished: Boolean = false,
    /** A short line under the label — "4 calls · 23:27". */
    val detail: String = "",
)

/**
 * The working half of the merge and un-merge cards.
 *
 * Built around a ring rather than a bar. A linear indicator with one line of text left most of a
 * dialog empty and read as unfinished; the count sits inside the ring, which gives the card
 * something to be about and shows the same information in less width.
 *
 * Deliberately a body rather than a dialog of its own: opening a second dialog meant one card
 * closing and another opening over it, which flashed. The card stays; only what is inside it changes.
 */
@Composable
fun MergeProgressBody(state: MergeProgressState) {
    // Animated so the ring sweeps between calls instead of stepping, and closes to full on the last
    // one rather than snapping there.
    val target = when {
        state.finished -> 1f
        state.total <= 0 -> 0f
        else -> (state.current - 1).coerceAtLeast(0) / state.total.toFloat()
    }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 450),
        label = "mergeProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(PROGRESS_BODY_HEIGHT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The track, always drawn, so the ring reads as a dial with a remainder rather than a
            // lone arc floating on the surface.
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.size(RING_SIZE),
                strokeWidth = RING_STROKE,
                color = MaterialTheme.colorScheme.surfaceVariant,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(RING_SIZE),
                strokeWidth = RING_STROKE,
                color = MaterialTheme.colorScheme.primary,
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
            )
            if (state.finished) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            } else {
                // The count lives inside the ring: it is the one number worth reading, and putting it
                // here means the ring is not decoration wrapped around empty space.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = state.current.toString(),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "/${state.total}",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(
                when {
                    state.finished && state.isUnMerge -> R.string.unmerge_progress_done
                    state.finished -> R.string.merge_progress_done
                    state.isUnMerge -> R.string.unmerge_progress_working
                    else -> R.string.merge_progress_working
                }
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        if (state.detail.isNotBlank()) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = state.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The card's footer while working: the countdown and a way out, or nothing at all.
 *
 * The row keeps its height in both states, so the card does not grow a button row when it finishes.
 */
@Composable
fun MergeProgressFooter(state: MergeProgressState, onClose: () -> Unit) {
    var remaining by remember { mutableIntStateOf(AUTO_CLOSE_SECONDS) }

    // Closes itself, but never silently: the countdown is visible, and Close is there for anyone who
    // would rather not wait it out.
    LaunchedEffect(state.finished) {
        if (!state.finished) return@LaunchedEffect
        remaining = AUTO_CLOSE_SECONDS
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        onClose()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.finished) {
            Text(
                text = stringResource(R.string.progress_closing_in, remaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onClose) { Text(stringResource(R.string.general_close)) }
        } else {
            // Same height as a TextButton, so the footer does not appear from nowhere at the end.
            Spacer(Modifier.height(40.dp))
        }
    }
}
