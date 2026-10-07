/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.common.ConfirmDialog
import com.baba.callvault.ui.common.SummaryRequirementsDialog
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.transcription.model.ModelDownloadWorker
import com.baba.callvault.ui.common.ModelDownloadState
import com.baba.callvault.ui.common.rememberModelDownloadState

/**
 * The summariser's own section in Settings.
 *
 * In its own file rather than in `SettingsScreen`, which is already past two thousand lines. It
 * reuses that file's `SettingsSection` and `NavigationRow`, widened to `internal` for the purpose.
 *
 * **The costs are stated before the button, not after it.** This is a 3.46 GB download that then
 * wants about 3.5 GB of memory to run, which genuinely rules the feature out on smaller phones.
 * Measured on the OP12; the numbers come from [SummaryModel] so the copy cannot drift from what was
 * actually measured.
 *
 * @param extraRows Rendered between the model's rows and the licence line. Settings puts its
 *   "ask me first" switch here; the setup wizard, which has no business offering a preference about
 *   a dialog it has just shown, passes nothing. A slot rather than a flag so the wizard cannot end
 *   up with a switch it must then explain — the same shape [TranscriptionSection] already uses to
 *   host these rows inside itself.
 */
@Composable
internal fun SummaryRows(
    updateTrigger: Int,
    onDownload: (SummaryModel) -> Unit,
    onCancel: (SummaryModel) -> Unit,
    onDelete: (SummaryModel) -> Unit,
    extraRows: @Composable ColumnScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val model = SummaryModel.DEFAULT
    // What is on disk is read inside the observer, on every work emission, rather than remembered
    // here — a download that finishes on its own bumps no trigger, and the row was left inviting
    // the user to fetch 3.46 GB they already had.
    val state by rememberModelDownloadState(model, refreshKey = updateTrigger)

    val preferences = remember(context) { AppPreferences(context) }
    val askFirst = remember(updateTrigger) { preferences.getSummaryConfirmRequirements() }
    var showRequirements by remember { mutableStateOf(false) }

    // The costs are stated before the download starts, not after. Skipped once the user has said
    // they do not want asking again — a choice they can undo from the switch below.
    val startDownload = {
        if (askFirst) showRequirements = true else onDownload(model)
    }

    if (showRequirements) {
        SummaryRequirementsDialog(
            model = model,
            onDismiss = { showRequirements = false },
            onContinue = { dontAskAgain ->
                showRequirements = false
                if (dontAskAgain) preferences.setSummaryConfirmRequirements(false)
                onDownload(model)
            }
        )
    }

    // Asked for, because it was not and that cost a real 3.5 GB. The installed row said "Delete the
    // summariser" and did so on a single tap, with nothing between the tap and a download that takes
    // twenty minutes to replace. A destructive action this expensive gets a question first.
    var pendingDiscard by remember { mutableStateOf<Long?>(null) }
    pendingDiscard?.let { bytes ->
        val partial = bytes < model.sizeBytes
        ConfirmDialog(
            title = stringResource(
                if (partial) R.string.summary_discard_confirm_title else R.string.summary_delete_confirm_title
            ),
            body = stringResource(
                if (partial) R.string.summary_discard_confirm_body else R.string.summary_delete_confirm_body,
                bytes.toGigabytes()
            ),
            confirmLabel = stringResource(
                if (partial) R.string.summary_discard_confirm_confirm else R.string.summary_delete_confirm_confirm
            ),
            onDismiss = { pendingDiscard = null },
            onConfirm = {
                pendingDiscard = null
                onDelete(model)
            }
        )
    }

    Column {
        Note(
            stringResource(
                R.string.summary_requirements_body,
                model.sizeBytes.toGigabytes(),
                model.peakMemoryBytes.toGigabytes()
            )
        )

        when (val current = state) {
            ModelDownloadState.Installed -> NavigationRow(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.summary_model_delete),
                value = stringResource(R.string.summary_model_installed),
                onClick = { pendingDiscard = model.sizeBytes }
            )

            ModelDownloadState.Absent -> NavigationRow(
                icon = Icons.Filled.Download,
                label = stringResource(R.string.summary_model_download),
                value = stringResource(
                    R.string.summary_model_download_subtitle,
                    model.sizeBytes.toGigabytes()
                ),
                onClick = startDownload
            )

            // Stopped part-way. Saying so is the point: those bytes are banked, the server resumes
            // from exactly that offset, and a row reading "Download, 3.5 GB" would hide a gigabyte
            // the user has already paid for. The discard row exists because otherwise that
            // gigabyte can only be reclaimed by clearing the app's data.
            is ModelDownloadState.Paused -> {
                NavigationRow(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.summary_model_resume),
                    value = stringResource(
                        R.string.summary_model_resume_subtitle,
                        current.downloadedBytes.toGigabytes(),
                        model.sizeBytes.toGigabytes()
                    ),
                    onClick = startDownload
                )
                NavigationRow(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.summary_model_discard),
                    value = stringResource(
                        R.string.summary_model_discard_subtitle,
                        current.downloadedBytes.toGigabytes()
                    ),
                    onClick = { pendingDiscard = current.downloadedBytes }
                )
            }

            // Queued and 0% look identical on a bar and mean different things, so waiting says so
            // in words and shows an indeterminate bar rather than an empty one.
            ModelDownloadState.Waiting -> ModelDownloadingRow(
                label = stringResource(R.string.summary_model_waiting),
                percent = null,
                onCancel = { onCancel(model) }
            )

            is ModelDownloadState.Downloading -> ModelDownloadingRow(
                label = stringResource(R.string.summary_model_downloading, current.percent),
                percent = current.percent,
                onCancel = { onCancel(model) }
            )

            // A failure says which one it was, because the two the user can act on need different
            // actions: free up space, or simply try again.
            is ModelDownloadState.Failed -> {
                NavigationRow(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.summary_model_download),
                    value = stringResource(
                        when (current.reason) {
                            ModelDownloadWorker.ERROR_NO_SPACE -> R.string.summary_model_failed_space
                            ModelDownloadWorker.ERROR_VERIFICATION_FAILED -> R.string.summary_model_failed_verify
                            else -> R.string.summary_model_failed
                        }
                    ),
                    onClick = startDownload
                )
            }
        }

        extraRows()

        // Named where it can be read before the download starts. CallVault never ships the weights,
        // so its own licensing is unaffected — but the person accepting Google's terms is entitled
        // to know that is what they are doing.
        Note(stringResource(R.string.summary_model_licence))
    }
}

@Composable
private fun ColumnScope.Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Bytes as gigabytes, for copy that talks in the units a person would. */
private fun Long.toGigabytes(): Float = this / 1_000_000_000f
