/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import java.util.Locale

/** "02:00" — the run time as one value, in the 24-hour form the picker and the setting both use. */
fun formatRunTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour, minute)

/**
 * Picks the daily run time as a single value.
 *
 * Replaces a pair of Hour and Minute dropdowns. Two dropdowns made the user assemble a time out of
 * two half-answers, and the minute one only offered a few fixed values; a clock picker is one
 * decision, allows any minute, and follows the device's 12/24-hour setting for free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionTimeDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcription_time_label)) },
        text = {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(R.string.general_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}
