/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.ui.viewmodels.PlaybackControls

/**
 * Transport controls for the transcript sheet: scrub, skip, play, and how far in you are.
 *
 * The sheet needs these because reading a transcript and listening to the call are the same task.
 * Tapping a line already jumps playback there, but that is a one-way trip — without a pause button
 * the only way to stop what a tap started was to close the sheet, and without skip the only way back
 * over a mis-heard sentence was to hunt for the line that contains it.
 *
 * Deliberately smaller than the playback screen's player, and deliberately without its speed
 * control. This is a strip pinned under a list someone is reading; the screen is where the call gets
 * dealt with properly, and duplicating all of it here would leave two players to keep in step.
 *
 * @param positionMs how far playback has reached, or **-1 when this recording is not the loaded
 *                   track** — the sheet can be opened on a call that is not playing, and everything
 *                   except the play button is meaningless until it is.
 */
@Composable
fun TranscriptPlayerBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = positionMs >= 0
    val position = if (isActive) positionMs else 0L
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        SeekBar(
            positionMs = position.toInt(),
            durationMs = durationMs.toInt(),
            onSeek = onSeek,
            enabled = isActive,
            modifier = Modifier.fillMaxWidth()
        )

        // A Box rather than one Row: the three buttons stay centred on the sheet no matter how wide
        // the clock gets, which a SpaceBetween row would not do — an hour-long call would shove them
        // off-centre the moment the reading grew an hours field.
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkipButton(
                    icon = Icons.Filled.Replay10,
                    label = stringResource(R.string.playback_skip_back),
                    accent = accent,
                    enabled = isActive,
                    onClick = { onSkip(-PlaybackControls.SKIP_MS) }
                )

                FilledIconButton(
                    onClick = {
                        when {
                            !isActive -> onPlay()
                            isPlaying -> onPause()
                            else -> onResume()
                        }
                    },
                    modifier = Modifier.size(PLAY_SIZE),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.general_pause else R.string.home_player_play
                            ),
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                SkipButton(
                    icon = Icons.Filled.Forward10,
                    label = stringResource(R.string.playback_skip_forward),
                    accent = accent,
                    enabled = isActive,
                    onClick = { onSkip(PlaybackControls.SKIP_MS) }
                )
            }

            // Digits only, so it never needs translating — but it is still a clock reading, and
            // clocks do not mirror. Forcing LTR keeps "1:12 / 13:40" the right way round in Hebrew.
            Text(
                text = "${TranscriptTimestamp.format(position)} / ${TranscriptTimestamp.format(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * One of the two skip buttons.
 *
 * NOT a default tonal button: secondaryContainer in this theme is CoralDeep, so the default comes
 * out maroon and reads as an error — the same trap the playback screen's controls record.
 */
@Composable
private fun SkipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(SKIP_SIZE),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = accent.copy(alpha = 0.14f),
            contentColor = accent
        )
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(22.dp))
    }
}

/**
 * Sizes chosen against the clock, not by taste.
 *
 * 44 + 56 + 44 with two 12dp gaps is 168dp; centred on a 360dp sheet the block ends about 14dp
 * before "1:12 / 13:40" begins. Bigger and the two would collide on a narrow phone in an hour-long
 * call, which is exactly when both are being read.
 */
private val PLAY_SIZE = 56.dp
private val SKIP_SIZE = 44.dp
