/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.recordings.DeleteScope
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource

/**
 * Confirmation shown before permanently deleting a recording, one copy of it, or its transcript.
 *
 * @param message The pre-resolved confirmation message. Defaults to the delete-all-copies wording;
 *                callers deleting a single Device/Drive copy pass a copy-specific message instead.
 * @param body    Optional extra content below the message — used by [DeleteCopiesDialog] for the
 *                scope question, so that a dialog asking one more thing is still this same dialog.
 */
@Composable
fun DeleteRecordingDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String = stringResource(R.string.home_delete_confirm_message, name),
    title: String = stringResource(R.string.home_delete_confirm_title),
    body: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
        title = { Text(text = title) },
        text = {
            Column {
                Text(text = message)
                body?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.home_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.general_cancel))
            }
        }
    )
}

/**
 * Deleting one recording — asking which copies when it is kept in two places.
 *
 * A row is not a file. A recording saved both on the device and in a Drive folder is shown as one
 * card carrying two URIs, so "delete this" is ambiguous, and guessing destroys a copy the user meant
 * to keep. The question is asked *only* when it is genuinely ambiguous: a recording that exists in
 * one place gets the plain confirmation, because a three-way choice with one real answer is a
 * puzzle rather than a safeguard.
 *
 * The choice is one dropdown rather than three buttons — the same shape the bulk delete uses, and
 * for the same reason recorded there: three destructive actions laid out side by side turn a single
 * decision into a wall of them, and put the dangerous option next to the safe one.
 *
 * @param onConfirm receives the chosen scope. For a recording that exists in only one place it is
 *                  always [DeleteScope.BOTH] — "everything there is" — so callers need no special
 *                  case for the unambiguous kind.
 */
@Composable
fun DeleteCopiesDialog(
    item: RecordingItem,
    name: String,
    onConfirm: (DeleteScope) -> Unit,
    onDismiss: () -> Unit
) {
    if (item.source != RecordingSource.BOTH) {
        DeleteRecordingDialog(
            name = name,
            onConfirm = { onConfirm(DeleteScope.BOTH) },
            onDismiss = onDismiss
        )
        return
    }

    // Both copies by default: it is what deleting one of these rows has always done, so the option
    // that changes nothing is the one already selected. Nothing is destroyed until Delete is pressed.
    var scope by remember { mutableStateOf(DeleteScope.BOTH) }

    val options = listOf(
        DeleteScope.BOTH to R.string.home_bulk_delete_both,
        DeleteScope.DEVICE to R.string.home_bulk_delete_device_only,
        DeleteScope.DRIVE to R.string.home_bulk_delete_drive_only
    ).map { (option, labelRes) ->
        val label = stringResource(labelRes)
        val bytes = item.bytesFor(option)
        OptionItem(
            key = option.name,
            // The size rides along so the consequence is visible while choosing. Omitted rather than
            // shown as "0 B" when a copy's size is unknown — a wrong number here reads as authority.
            label = if (bytes == null) label
            else stringResource(R.string.home_delete_scope_option_size, label, formatByteSize(bytes))
        )
    }

    // What survives, named. A copy the user believed they had deleted — or one they destroyed
    // without meaning to — is the outcome that must never be discovered later from the list.
    val kept = when (scope) {
        DeleteScope.DEVICE -> stringResource(R.string.home_bulk_delete_kept_drive, name)
        DeleteScope.DRIVE -> stringResource(R.string.home_bulk_delete_kept_device, name)
        DeleteScope.BOTH -> null
    }

    DeleteRecordingDialog(
        name = name,
        title = stringResource(R.string.home_delete_confirm_title),
        message = stringResource(R.string.home_delete_scope_message),
        onConfirm = { onConfirm(scope) },
        onDismiss = onDismiss,
        body = {
            // No spacer before it: M3DropdownField carries its own vertical padding, and the two
            // together leave an empty band that makes the dialog taller than its content.
            M3DropdownField(
                label = stringResource(R.string.home_bulk_delete_scope_label),
                selected = options.first { it.key == scope.name },
                options = options,
                onOptionSelected = { scope = DeleteScope.valueOf(it.key) }
            )
            if (kept != null) {
                Text(
                    text = kept,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * How many bytes [scope] would free for this recording, or null when that cannot be known.
 *
 * The per-copy sizes are only populated for a recording found in both places, which is the only
 * case this is ever asked about.
 */
private fun RecordingItem.bytesFor(scope: DeleteScope): Long? = when (scope) {
    DeleteScope.DEVICE -> localSizeBytes
    DeleteScope.DRIVE -> driveSizeBytes
    DeleteScope.BOTH -> {
        val local = localSizeBytes
        val drive = driveSizeBytes
        if (local != null && drive != null) local + drive else null
    }
}
