/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingDirection


/**
 * The corner badge that says where a recording came from: which direction a phone call went, or which
 * app a VoIP call was made in.
 *
 * **One badge, two answers, because a recording only ever has one of them.** A phone call has a
 * direction and no app; a VoIP call has an app and no direction — there is no call-log entry behind it
 * to take a direction from. So the same corner carries whichever applies, and they can never collide.
 *
 * Shared between the recordings list (pinned to the play disc) and the playback screen (pinned to the
 * contact avatar), so the same recording is marked the same way wherever it appears. It previously
 * existed only in the list, which left the playback screen unable to say a call was VoIP at all.
 */
@Composable
fun BoxScope.CallOriginBadge(direction: RecordingDirection?, voipApp: String?) {
    when {
        direction == null && voipApp != null -> VoipAppBadge(voipApp)
        direction != null -> DirectionBadge(direction)
    }
}

/**
 * The shared shell every origin badge sits in.
 *
 * The outline is not decoration. The badge is pinned to the corner of a circle and overhangs it by
 * about a third, and out there the `surface` fill is exactly the colour of the card behind it —
 * measured on a OnePlus 9 Pro, where the play disc spans [180,2240]-[276,2336] and the badge
 * [258,2318]-[310,2370]. Without an edge, the overhanging part is invisible and the badge reads as a
 * bite taken out of the disc rather than as a badge.
 */
@Composable
private fun BoxScope.BadgeShell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(2.dp)
            .align(Alignment.BottomEnd),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Which app a VoIP recording came from, drawn with that app's OWN installed icon — so no messenger's
 * trademarked logo is bundled with CallVault. Falls back to a neutral glyph when the app cannot be
 * resolved (uninstalled since, or a label we cannot match).
 */
@Composable
private fun BoxScope.VoipAppBadge(appLabel: String) {
    val context = LocalContext.current
    val icon = remember(appLabel) { VoipAppIcons.iconFor(context, appLabel) }
    BadgeShell {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = appLabel,
                modifier = Modifier.size(13.dp).clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = appLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** Which way a phone call went. */
@Composable
private fun BoxScope.DirectionBadge(direction: RecordingDirection) {
    val icon = when (direction) {
        RecordingDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
        RecordingDirection.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
    }
    val description = when (direction) {
        RecordingDirection.INCOMING -> stringResource(R.string.general_incoming)
        RecordingDirection.OUTGOING -> stringResource(R.string.general_outgoing)
    }
    BadgeShell {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(11.dp),
        )
    }
}
