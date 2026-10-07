/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R

/**
 * A duration a person would say out loud: "under a minute", "16 minutes", "1 h 30 min".
 *
 * Deliberately coarse. The estimate is an estimate, and "about 16 minutes" is both more honest and
 * more useful than "15 minutes 47 seconds".
 */
@Composable
fun formatEstimate(ms: Long): String {
    val minutes = quotedMinutes(ms)
    return when {
        ms < 60_000L -> stringResource(R.string.transcribe_estimate_under_minute)
        // Decided on the ROUNDED total, so 59 min 59 s reads as "1 h 0 min" rather than "60 minutes".
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.transcribe_estimate_minutes, minutes)
        else -> stringResource(
            R.string.transcribe_estimate_hours,
            minutes / MINUTES_PER_HOUR,
            minutes % MINUTES_PER_HOUR,
        )
    }
}

/**
 * Whole minutes to quote for [ms], **rounded up**.
 *
 * Rounding up rather than truncating, for two reasons found together in issue #26.
 *
 * Truncating lost up to 59 seconds on every quote — a 6.9-minute run was announced as "6 minutes" —
 * and that stacked on an estimate which already aims at the middle of its own spread. The dialog was
 * therefore biased low twice, and low is the direction that hurts: a run overshooting the time it
 * promised reads as a hang, which is the complaint this whole area exists to answer.
 *
 * It also hid the estimate learning. After a slow run moved the stored figure from 6.0 to 6.4
 * minutes, truncation still printed "6", so the correction was invisible and looked like nothing had
 * happened at all.
 *
 * The spare seconds this adds are not padding for its own sake — they roughly cover the run-to-run
 * spread from a phone that has warmed up, which is what made a measured 6-minute estimate take 7.4.
 */
internal fun quotedMinutes(ms: Long): Int {
    if (ms <= 0L) return 0
    return ((ms + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

/**
 * Asks before starting a transcription, saying how long it is expected to take.
 *
 * The estimate is arithmetic and instant — there is nothing to compute, so there is no progress bar
 * here pretending otherwise. Its accuracy comes from
 * [com.baba.callvault.transcription.TranscriptionEstimate], which learns this phone's real speed from
 * every finished run.
 *
 * @param estimate human-readable duration, already formatted; null when the recording's length could
 *   not be read, in which case the dialog asks without promising a number it does not have.
 * @param isFirstRun true until this phone has timed a run of the chosen model. The estimate then
 *   rests on figures published for other hardware, which are right to within a factor of two or
 *   three — so the dialog says the run may take a while rather than naming a number it cannot yet
 *   stand behind. Issue #26's reporter asked for exactly this, having been quoted hours.
 * @param onConfirm receives whether the user asked not to be shown this again.
 */
@Composable
fun TranscribeConfirmDialog(
    title: String,
    estimate: String?,
    isFirstRun: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (dontAskAgain: Boolean) -> Unit
) {
    var dontAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Schedule, contentDescription = null) },
        title = {
            Text(
                text = when {
                    isFirstRun -> stringResource(R.string.transcribe_confirm_title_first_run)
                    estimate != null -> stringResource(R.string.transcribe_confirm_title, estimate)
                    else -> stringResource(R.string.transcribe_confirm_title_unknown)
                }
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.transcribe_confirm_message, title))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .toggleable(
                            value = dontAskAgain,
                            onValueChange = { dontAskAgain = it },
                            role = androidx.compose.ui.semantics.Role.Checkbox
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.transcribe_confirm_dont_ask),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskAgain) }) {
                Text(stringResource(R.string.transcript_action_transcribe_short))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}
