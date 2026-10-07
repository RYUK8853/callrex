/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.system.updates.UpdateScheduler
import com.baba.callvault.integrations.scrcpy.AUDIO_BIT_RATE_OPTIONS
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.transcription.model.DownloadableModel
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.ui.common.SyncScheduleLabels
import com.baba.callvault.ui.common.TranscriptionLabels
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.UsbDefaultMode
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.baba.callvault.system.PersistentFolderPickerContract
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.takePersistableFolderPermission
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvHero
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSecondaryButton
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.WizardViewModel
import com.baba.callvault.utils.FILE_NAME_TEMPLATE_PRESETS
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.presetForTemplateOrFirst

/** The audio bit-rate options offered in the wizard (bps), shared with Settings. */

/** Minute granularity offered in the schedule step. */

/** java.util.Calendar day-of-week constants (SUNDAY=1..SATURDAY=7). */

/**
 * The one-time post-onboarding setup wizard, redesigned on the "Signal" design system.
 *
 * A guided, premium stepper: a teal segmented progress bar + "Step N of M" header sit above a
 * [CvHero] step title, branded [CvCard] option rows form each step body, and a persistent bottom
 * bar carries Back / Next (Finish on the last step). The [WizardViewModel] persists every choice
 * live; [onFinished] fires after the final step so the router refreshes and advances to Home.
 *
 * Behavior, persistence, the dynamic step list (the schedule step appears only for Drive/Both),
 * the clamp logic, and the Next-gating on required folders are all preserved from the original.
 *
 * @param onFinished Called after the final "Finish" step completes; the router triggers a nav refresh.
 * @param modifier   Optional layout modifier for the root [CvScaffold].
 */
@Composable
fun WizardScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WizardViewModel = viewModel()
) {
    val context = LocalContext.current
    val updateTrigger by viewModel.updateTrigger.collectAsState()

    // Device/recording folder picker — persists access across reboots.
    val recordingFolderPicker = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            if (SafHelper.isCloudFolder(uri)) {
                Toast.makeText(context, context.getString(R.string.folder_cloud_rejected), Toast.LENGTH_LONG).show()
            } else {
                context.takePersistableFolderPermission(uri)
                viewModel.setRecordingFolderUri(uri)
            }
        }
    }

    // Drive folder picker — same contract.
    val driveFolderPicker = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            context.takePersistableFolderPermission(uri)
            viewModel.setDriveFolderUri(uri)
        }
    }

    // Current values (re-read whenever a write bumps updateTrigger).
    val storageTarget = remember(updateTrigger) { viewModel.preferences.getStorageTarget() }
    val recordingFolderLabel =
        remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, viewModel.preferences.getRecordingFolderUri()) }
    val driveFolderLabel =
        remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, viewModel.preferences.getDriveFolderUri()) }
    val scheduleMode = remember(updateTrigger) { viewModel.preferences.getSyncScheduleMode() }
    val syncHour = remember(updateTrigger) { viewModel.preferences.getSyncTimeHour() }
    val syncMinute = remember(updateTrigger) { viewModel.preferences.getSyncTimeMinute() }
    val syncDayOfWeek = remember(updateTrigger) { viewModel.preferences.getSyncDayOfWeek() }
    val carrierRecording = remember(updateTrigger) { viewModel.preferences.isCarrierRecordingEnabled() }
    val autoRecordIncoming = remember(updateTrigger) { viewModel.preferences.isAutoRecordIncomingEnabled() }
    val autoRecordOutgoing = remember(updateTrigger) { viewModel.preferences.isAutoRecordOutgoingEnabled() }
    val audioCodec = remember(updateTrigger) { viewModel.preferences.getAudioCodec() }
    val audioBitRate = remember(updateTrigger) { viewModel.preferences.getAudioBitRate() }
    val fileNameTemplate = remember(updateTrigger) { viewModel.preferences.getFileNameTemplate() }

    val usesDrive = storageTarget == StorageTarget.DRIVE || storageTarget == StorageTarget.BOTH

    // Fixed for the run of the wizard: the mode is chosen during onboarding, before this screen opens.
    val usesEmbeddedAdb = remember { !viewModel.preferences.getPrivilegedMode().needsShizuku }

    val steps = remember(usesDrive, usesEmbeddedAdb) { wizardSteps(usesDrive, usesEmbeddedAdb) }

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    // Clamp in case the step list shrank (e.g. user switched away from Drive on step 1).
    val safeIndex = stepIndex.coerceIn(0, steps.lastIndex)
    val currentStep = steps[safeIndex]

    // Whether the current step's requirements are satisfied (gates the Next/Finish button).
    val canAdvance = canAdvanceFrom(
        step = currentStep,
        hasRecordingFolder = recordingFolderLabel != null,
        hasDriveFolder = !usesDrive || driveFolderLabel != null
    )

    val isLastStep = safeIndex == steps.lastIndex

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.app_name),
        bottomBar = {
            WizardBottomBar(
                isFirstStep = safeIndex == 0,
                isLastStep = isLastStep,
                canAdvance = canAdvance,
                onBack = { if (safeIndex > 0) stepIndex = safeIndex - 1 },
                onNext = {
                    if (isLastStep) {
                        viewModel.finish()
                        onFinished()
                    } else {
                        stepIndex = safeIndex + 1
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                WizardHeader(
                    stepNumber = safeIndex + 1,
                    stepCount = steps.size,
                    title = stringResource(stepTitleRes(currentStep)),
                    subtitle = stringResource(stepSubtitleRes(currentStep))
                )
            }

            item {
                when (currentStep) {
                    WizardStep.STORAGE -> StorageStep(
                        storageTarget = storageTarget,
                        recordingFolderLabel = recordingFolderLabel,
                        driveFolderLabel = driveFolderLabel,
                        usesDrive = usesDrive,
                        onSelectStorageTarget = viewModel::setStorageTarget,
                        // Seed each picker with its OWN current folder so it opens there, instead of
                        // letting Android's DocumentsUI reopen at the last-browsed location (which, after
                        // setting Drive, made re-picking the local folder open at the Drive path).
                        onPickRecordingFolder = { recordingFolderPicker.launch(viewModel.preferences.getRecordingFolderUri()) },
                        onPickDriveFolder = { driveFolderPicker.launch(viewModel.preferences.getDriveFolderUri()) }
                    )
                    WizardStep.SCHEDULE -> ScheduleStep(
                        scheduleMode = scheduleMode,
                        hour = syncHour,
                        minute = syncMinute,
                        dayOfWeek = syncDayOfWeek,
                        onSelectMode = viewModel::setSyncScheduleMode,
                        onSelectHour = viewModel::setSyncTimeHour,
                        onSelectMinute = viewModel::setSyncTimeMinute,
                        onSelectDayOfWeek = viewModel::setSyncDayOfWeek
                    )
                    WizardStep.AUTO_RECORD -> AutoRecordStep(
                        carrier = carrierRecording,
                        incoming = autoRecordIncoming,
                        outgoing = autoRecordOutgoing,
                        onCarrierChange = viewModel::setCarrierRecording,
                        onIncomingChange = viewModel::setAutoRecordIncoming,
                        onOutgoingChange = viewModel::setAutoRecordOutgoing
                    )
                    WizardStep.RELIABILITY -> ReliabilityStep()
                    WizardStep.EXPERIMENTAL -> ExperimentalStep()
                    WizardStep.UPDATES -> UpdatesStep()
                    WizardStep.AUDIO -> AudioStep(
                        audioCodec = audioCodec,
                        audioBitRate = audioBitRate,
                        onSelectCodec = viewModel::setAudioCodec,
                        onSelectBitRate = viewModel::setAudioBitRate
                    )
                    WizardStep.FILE_NAME -> FileNameStep(
                        template = fileNameTemplate,
                        onSelectTemplate = viewModel::setFileNameTemplate
                    )
                    WizardStep.TRANSCRIPTION -> TranscriptionStep(
                        updateTrigger = updateTrigger,
                        onDownloadModel = viewModel::downloadModel,
                        onCancelModelDownload = viewModel::cancelModelDownload,
                        onDeleteModel = viewModel::deleteModel
                    )
                }
            }
        }
    }
}

/** The logical steps of the wizard (two of them are conditionally included — see [wizardSteps]). */
internal enum class WizardStep { STORAGE, SCHEDULE, AUTO_RECORD, RELIABILITY, EXPERIMENTAL, AUDIO, FILE_NAME, TRANSCRIPTION, UPDATES }

/**
 * The visible step list, in order. Kept a pure function so the two exclusions are testable and stay
 * coherent with the "Step N of M" counter, which is derived from this list.
 *
 * @param usesDrive       Drive is a storage target, so there is an upload schedule worth asking about.
 * @param usesEmbeddedAdb CallVault's own ADB provides the privilege (i.e. not Shizuku mode).
 *
 * The reliability step is dropped without it. Nothing on that step exists in Shizuku mode — the USB
 * default is applied over the embedded shell and offline recording is the loopback opt-in — and merely
 * *showing* it did harm: entering the step probed the USB default, which ensured an ADB connection,
 * which switched **Wireless debugging on** for a user who had never paired anything and could never
 * have benefited. [UsbDefaultConfig.isShellUsable] now refuses that from below as well; this keeps the
 * user from being asked a question with no answer in the first place.
 */
internal fun wizardSteps(usesDrive: Boolean, usesEmbeddedAdb: Boolean): List<WizardStep> = buildList {
    add(WizardStep.STORAGE)
    if (usesDrive) add(WizardStep.SCHEDULE)
    add(WizardStep.AUTO_RECORD)
    if (usesEmbeddedAdb) add(WizardStep.RELIABILITY)
    add(WizardStep.EXPERIMENTAL)
    add(WizardStep.AUDIO)
    add(WizardStep.FILE_NAME)
    // Follows the recording's own life: captured, encoded, named, and then read. It is unconditional
    // because transcription needs nothing from ADB — it is the one flagship feature a fresh install
    // would otherwise never hear about, since the What's New dialog only fires after an *update*.
    add(WizardStep.TRANSCRIPTION)
    add(WizardStep.UPDATES)
}

/**
 * Whether Next (or Finish, on the last step) is enabled on [step].
 *
 * Storage is the only step that can withhold it, and only because a recording with nowhere to go is
 * not a setup that works. Everything else is a preference, an opt-in or — since the transcription
 * step started offering them — a download, and **a download must never gate setup**: the summariser
 * is 3.46 GB over an unmetered network, so a user on mobile data would be held on step seven of nine
 * until they got home. The work continues on its own; the wizard has no reason to wait for it.
 *
 * A pure function for the same reason [wizardSteps] is one — it is a rule about the wizard rather
 * than a piece of its drawing, and this is the shape a test can hold on to.
 */
internal fun canAdvanceFrom(
    step: WizardStep,
    hasRecordingFolder: Boolean,
    hasDriveFolder: Boolean
): Boolean = when (step) {
    WizardStep.STORAGE -> hasRecordingFolder && hasDriveFolder
    else -> true
}

// ── Shell: header + progress + bottom bar ─────────────────────────────────────────────────────

/**
 * The guided header: a small "Setup" eyebrow + "Step N of M", a teal segmented progress bar, then
 * the current step's title + subtitle via [CvHero]. This anchors the wizard as a confident, modern
 * onboarding flow rather than a stock Material stepper.
 */
@Composable
private fun WizardHeader(stepNumber: Int, stepCount: Int, title: String, subtitle: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wizard_ui_eyebrow).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.wizard_step_of, stepNumber, stepCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        SegmentedProgress(current = stepNumber, total = stepCount)
        Spacer(Modifier.height(20.dp))
        CvHero(title = title, subtitle = subtitle)
    }
}

/** A row of rounded teal segments showing progress; completed/current segments are filled teal. */
@Composable
private fun SegmentedProgress(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        val fill = MaterialTheme.colorScheme.primary
        for (i in 1..total) {
            val color by animateColorAsState(
                targetValue = if (i <= current) fill else track,
                label = "wizardSegment$i"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** Persistent bottom bar: tonal Back (hidden on step 1) + filled teal Next / Finish. */
@Composable
private fun WizardBottomBar(
    isFirstStep: Boolean,
    isLastStep: Boolean,
    canAdvance: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isFirstStep) {
            CvSecondaryButton(
                text = stringResource(R.string.wizard_back),
                onClick = onBack,
                modifier = Modifier.weight(1f)
            )
        }
        CvPrimaryButton(
            text = stringResource(if (isLastStep) R.string.wizard_finish else R.string.wizard_next),
            onClick = onNext,
            enabled = canAdvance,
            leadingIcon = if (isLastStep) Icons.Filled.Done else Icons.AutoMirrored.Filled.ArrowForward,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Reusable branded option / folder / toggle rows ────────────────────────────────────────────

/**
 * A branded radio-style option card: title + one-line description, a teal check that appears when
 * selected, and a teal border + faint teal tint in the selected state. Replaces stock RadioButtons.
 */
@Composable
private fun OptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val container =
        if (selected) primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
    val borderColor by animateColorAsState(
        targetValue = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
        label = "optionBorder"
    )

    CvCard(
        onClick = onClick,
        color = container,
        border = false,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.border(
            width = if (selected) 1.5.dp else 1.dp,
            color = borderColor,
            shape = MaterialTheme.shapes.large
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            SelectionDot(selected = selected)
        }
    }
}

/** A circular selection indicator: a hollow ring when unselected, a filled teal check when selected. */
@Composable
private fun SelectionDot(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    if (selected) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
    }
}

/**
 * A tappable folder picker row: a leading icon, the label + chosen folder name (or a "Required"
 * pill prompting selection), and a trailing chevron. Required-but-unset rows read with a coral tint.
 */
@Composable
private fun FolderPickerCard(
    icon: ImageVector,
    label: String,
    chosenName: String?,
    onClick: () -> Unit
) {
    val hasFolder = chosenName != null
    CvCard(onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chosenName ?: stringResource(R.string.wizard_ui_folder_choose),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasFolder) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** A clear toggle row: title + one-line description on the left, a teal [Switch] on the right. */
@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    CvCard(onClick = { onCheckedChange(!checked) }, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
    }
}

/**
 * Like [NoteCard], but coral — for a choice that leaves CallVault doing nothing.
 *
 * Every colour is stated: the scheme's M3 defaults resolve to coral here, so a card that leant on them
 * would be indistinguishable from an ordinary note.
 */
@Composable
private fun WarningCard(text: String) {
    CvCard(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
        border = false,
        contentPadding = PaddingValues(14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** A muted helper/note line set inside a faint surface card — for contextual guidance. */
@Composable
private fun NoteCard(text: String) {
    CvCard(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = false,
        contentPadding = PaddingValues(14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Steps ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StorageStep(
    storageTarget: StorageTarget,
    recordingFolderLabel: String?,
    driveFolderLabel: String?,
    usesDrive: Boolean,
    onSelectStorageTarget: (StorageTarget) -> Unit,
    onPickRecordingFolder: () -> Unit,
    onPickDriveFolder: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StorageTarget.entries.forEach { target ->
            OptionCard(
                title = stringResource(storageTargetTitleRes(target)),
                description = stringResource(storageTargetDescRes(target)),
                selected = storageTarget == target,
                onClick = { onSelectStorageTarget(target) }
            )
        }

        if (usesDrive) {
            NoteCard(stringResource(R.string.wizard_storage_drive_note))
        }

        Spacer(Modifier.height(2.dp))

        FolderPickerCard(
            icon = Icons.Filled.Folder,
            label = stringResource(R.string.settings_recording_folder_label),
            chosenName = recordingFolderLabel,
            onClick = onPickRecordingFolder
        )

        if (usesDrive) {
            FolderPickerCard(
                icon = Icons.Filled.CloudUpload,
                label = stringResource(R.string.settings_drive_folder_label),
                chosenName = driveFolderLabel,
                onClick = onPickDriveFolder
            )
        }
    }
}

@Composable
private fun ScheduleStep(
    scheduleMode: SyncScheduleMode,
    hour: Int,
    minute: Int,
    dayOfWeek: Int,
    onSelectMode: (SyncScheduleMode) -> Unit,
    onSelectHour: (Int) -> Unit,
    onSelectMinute: (Int) -> Unit,
    onSelectDayOfWeek: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SyncScheduleMode.entries.forEach { mode ->
            OptionCard(
                title = stringResource(SyncScheduleLabels.titleOf(mode)),
                description = stringResource(SyncScheduleLabels.descriptionOf(mode)),
                selected = scheduleMode == mode,
                onClick = { onSelectMode(mode) }
            )
        }

        if (scheduleMode == SyncScheduleMode.IMMEDIATE) {
            NoteCard(stringResource(R.string.wizard_schedule_immediate_note))
        }

        if (scheduleMode == SyncScheduleMode.DAILY || scheduleMode == SyncScheduleMode.WEEKLY) {
            CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (scheduleMode == SyncScheduleMode.WEEKLY) {
                    val dayOptions = SyncScheduleLabels.DAY_OF_WEEK_OPTIONS.map { day ->
                        OptionItem(day.toString(), stringResource(SyncScheduleLabels.dayOfWeekOf(day)))
                    }
                    M3DropdownField(
                        label = stringResource(R.string.wizard_schedule_day_label),
                        selected = dayOptions.find { it.key == dayOfWeek.toString() } ?: dayOptions.first(),
                        options = dayOptions,
                        onOptionSelected = { onSelectDayOfWeek(it.key.toInt()) }
                    )
                }
                val hourOptions = (0..23).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
                M3DropdownField(
                    label = stringResource(R.string.wizard_schedule_hour_label),
                    selected = hourOptions.find { it.key == hour.toString() } ?: hourOptions.first(),
                    options = hourOptions,
                    onOptionSelected = { onSelectHour(it.key.toInt()) }
                )
                val minuteOptions = SyncScheduleLabels.MINUTE_OPTIONS.map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
                M3DropdownField(
                    label = stringResource(R.string.wizard_schedule_minute_label),
                    selected = minuteOptions.find { it.key == minute.toString() } ?: minuteOptions.first(),
                    options = minuteOptions,
                    onOptionSelected = { onSelectMinute(it.key.toInt()) }
                )
            }
        }
    }
}

@Composable
private fun AutoRecordStep(
    carrier: Boolean,
    incoming: Boolean,
    outgoing: Boolean,
    onCarrierChange: (Boolean) -> Unit,
    onIncomingChange: (Boolean) -> Unit,
    onOutgoingChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    // The VoIP switch owns its own persistence (and its consent dialog), so this mirrors it only to
    // answer the one question this step now has to answer: will anything be recorded at all?
    var voip by remember { mutableStateOf(AppPreferences(context).isVoipRecordingEnabled()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Offered here and not only in Settings because the wizard cannot be re-run, and someone
        // installing CallVault for app calls alone wants this on day one — otherwise their first
        // week is a Record prompt on every phone call they take.
        ToggleCard(
            title = stringResource(R.string.settings_carrier_recording),
            description = stringResource(R.string.settings_carrier_recording_description),
            checked = carrier,
            onCheckedChange = onCarrierChange
        )
        if (carrier) {
            ToggleCard(
                title = stringResource(R.string.settings_auto_record_incoming),
                description = stringResource(R.string.wizard_ui_auto_incoming_desc),
                checked = incoming,
                onCheckedChange = onIncomingChange
            )
            ToggleCard(
                title = stringResource(R.string.settings_auto_record_outgoing),
                description = stringResource(R.string.wizard_ui_auto_outgoing_desc),
                checked = outgoing,
                onCheckedChange = onOutgoingChange
            )
        }

        // App calls belong beside phone calls: they are the same decision — what gets recorded — and
        // splitting them across two steps is exactly how someone reached Finish with both off. The very
        // switch Settings renders, so the consent confirmation and the Shizuku greying come with it.
        CvCard { VoipRecordingToggle(onEnabledChange = { voip = it }) }

        // Said, not enforced. Finish stays enabled because in Shizuku mode the app-call switch cannot be
        // turned on at all, so gating here would strand that user on a step they cannot satisfy. With
        // both off nothing is ever recorded — a legitimate choice to make, but not one to make silently.
        if (!carrier && !voip) {
            WarningCard(stringResource(R.string.wizard_auto_record_nothing_warning))
        }
    }
}

/**
 * The experimental opt-ins, mirroring Settings ▸ General ▸ Experimental so the two group them alike.
 *
 * The toggle is the very same composable Settings renders — shared rather than reimplemented, so a
 * change to it reaches both screens at once. The wizard cannot be re-run, so a feature it never
 * mentions is one most users never discover.
 *
 * VoIP recording used to sit here too. It moved to the auto-record step: it is not an extra, it is one
 * of the two answers to "what gets recorded", and being two steps away from the other one is what let
 * people finish with neither.
 */
@Composable
private fun ExperimentalStep() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CvCard { HandoffPersistToggle() }
    }
}

/** Whether CallVault checks GitHub for new releases. Off-by-default settings need asking about once. */
@Composable
private fun UpdatesStep() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isUpdateCheckEnabled()) }
    CvCard {
        SettingsToggleRow(
            icon = Icons.Filled.SystemUpdate,
            label = stringResource(R.string.settings_update_check_label),
            description = stringResource(R.string.settings_update_check_description),
            checked = enabled,
            onCheckedChange = { turnOn ->
                enabled = turnOn
                prefs.setUpdateCheckEnabled(turnOn)
                // Reconcile the periodic worker immediately, exactly as Settings does — persisting the
                // flag alone would leave the schedule disagreeing with it.
                UpdateScheduler.apply(context)
            },
        )
    }
}

/**
 * Onboarding "Reliability" step — two OPTIONAL opt-ins that make recording robust:
 *  1. **USB "Charging only"** — on many phones, locking the screen mid-call restarts adbd and kills the
 *     recorder; setting the Default USB Configuration to "Charging only" prevents that. Applied over the
 *     embedded shell in one tap.
 *  2. **Offline recording (no Wi-Fi)** — the warned loopback opt-in, rendered by Settings' own toggle.
 * Both are skippable (Next always advances) — nothing here gates setup.
 *
 * Both are also embedded-ADB machinery, which is why [wizardSteps] drops this step whole in Shizuku
 * mode rather than showing two controls that cannot act.
 */
@Composable
private fun ReliabilityStep() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var usbMode by remember { mutableStateOf(UsbDefaultConfig.cached(context)) }
    var usbApplying by remember { mutableStateOf(false) }
    // Read the live value once (connects+retries internally); falls back to the cached value.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UsbDefaultConfig.readViaShell(context) }?.let { usbMode = it }
    }
    val chargingOnly = usbMode == UsbDefaultMode.CHARGING

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── USB "Charging only" ──
        CvCard {
            Text(
                text = stringResource(R.string.wizard_reliability_usb_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.wizard_reliability_usb_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            when {
                chargingOnly -> WizardDoneRow(stringResource(R.string.wizard_reliability_usb_done))
                usbApplying -> WizardApplyingRow(stringResource(R.string.settings_usb_default_applying))
                else -> CvPrimaryButton(
                    text = stringResource(R.string.wizard_reliability_usb_button),
                    onClick = {
                        usbApplying = true
                        scope.launch {
                            withContext(Dispatchers.IO) { UsbDefaultConfig.setViaShell(context, UsbDefaultMode.CHARGING) }
                            usbMode = UsbDefaultConfig.cached(context)
                            usbApplying = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Offline recording (no Wi-Fi) ──
        // The switch Settings renders, not a copy of it. The hand-rolled card that used to sit here
        // skipped the ModeCapability gate and offered "Enable" in Shizuku mode, where the opt-in has no
        // embedded ADB to arm and the next mode switch turns it straight back off. Sharing the
        // composable is what stops that drift from happening again — as the experimental step already does.
        CvCard { OfflineRecordingToggle() }
    }
}

/** A small "done" row: a teal check + label, shown once a reliability opt-in has been applied. */
@Composable
private fun WizardDoneRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** A small "applying" row: a spinner + label, shown while a shell change is in flight. */
@Composable
private fun WizardApplyingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AudioStep(
    audioCodec: String,
    audioBitRate: Int,
    onSelectCodec: (String) -> Unit,
    onSelectBitRate: (Int) -> Unit
) {
    CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
        val codecOptions = ScrcpyAudioCodec.entries.map { OptionItem(it.cliKey, stringResource(it.titleResId)) }
        M3DropdownField(
            label = stringResource(R.string.settings_audio_codec),
            selected = codecOptions.find { it.key == audioCodec } ?: codecOptions.first(),
            options = codecOptions,
            onOptionSelected = { onSelectCodec(it.key) }
        )

        val bitrateOptions = AUDIO_BIT_RATE_OPTIONS.map {
            OptionItem(it.toString(), stringResource(R.string.audio_bitrate_kbps, it / 1000))
        }
        M3DropdownField(
            label = stringResource(R.string.settings_audio_bitrate),
            selected = bitrateOptions.find { it.key == audioBitRate.toString() } ?: bitrateOptions.first(),
            options = bitrateOptions,
            onOptionSelected = { onSelectBitRate(it.key.toInt()) }
        )
    }
}

@Composable
private fun FileNameStep(
    template: String,
    onSelectTemplate: (String) -> Unit
) {
    // Friendly preset label as primary text + a resolved example as the per-item preview line —
    // never the raw "{token}" template.
    val options = FILE_NAME_TEMPLATE_PRESETS.map {
        OptionItem(
            key = it.template,
            label = stringResource(it.labelRes),
            description = stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(it.template)
            )
        )
    }
    val selectedTemplate = presetForTemplateOrFirst(template).template

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
            M3DropdownField(
                label = stringResource(R.string.settings_file_name_template_preset),
                selected = options.find { it.key == selectedTemplate } ?: options.first(),
                options = options,
                onOptionSelected = { onSelectTemplate(it.key) }
            )
        }
        NoteCard(
            stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(selectedTemplate)
            )
        )
    }
}

/**
 * Transcription and summaries: when they run, in which language, and — now — their models.
 *
 * Here at all because a fresh install has no other route to either feature — the What's New dialog
 * needs an *update* to fire, and the wizard cannot be re-run, so a step omitted here leaves the
 * flagship of this release buried in a Settings accordion nobody was told to open.
 *
 * Every control is Settings' own composable, not a wizard copy of it: the mode dropdown carries the
 * "this is slow and heavy" confirmation, the language dropdown carries the auto-detect warning, and
 * the summariser's rows carry the requirements dialog that guards 3.46 GB. A copy that lost any of
 * those would be a copy that lies — the same reason [ExperimentalStep] shares its toggle and
 * [ReliabilityStep] shares the offline switch, both after a hand-rolled version had already dropped
 * the half that mattered.
 *
 * **The downloads start here and nothing waits for them.** The step used to say the button was in
 * Settings, which asked someone who has just been told about the feature to go and find it later.
 * They are WorkManager jobs on an unmetered network, so Next and Finish stay enabled throughout
 * ([canAdvanceFrom] never asks about this step) and the download carries on while the user finishes
 * setup and lands on Home.
 *
 * @param updateTrigger Bumped by the view model after each write, which re-reads the filesystem
 *   under the model rows — deleting a model touches no WorkManager state, so nothing else would.
 */
@Composable
private fun TranscriptionStep(
    updateTrigger: Int,
    onDownloadModel: (DownloadableModel) -> Unit,
    onCancelModelDownload: (DownloadableModel) -> Unit,
    onDeleteModel: (DownloadableModel) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var mode by remember { mutableStateOf(prefs.getTranscriptionMode()) }
    var language by remember { mutableStateOf(prefs.getTranscriptionLanguage()) }
    var modelId by remember { mutableStateOf(prefs.getTranscriptionModelId()) }
    val selectedModel = TranscriptionModel.fromId(modelId) ?: TranscriptionModel.DEFAULT

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
            TranscriptionModeField(
                mode = mode,
                onModeChange = { chosen ->
                    mode = chosen
                    prefs.setTranscriptionMode(chosen)
                    // Reconcile the sweep immediately, exactly as Settings does — persisting the mode
                    // alone would leave WorkManager disagreeing with it until something else ran.
                    TranscriptionScheduler.apply(context)
                }
            )
            TranscriptionLanguageField(
                language = language,
                onLanguageChange = { chosen ->
                    language = chosen
                    prefs.setTranscriptionLanguage(chosen)
                }
            )

            // Offered here because the download row below fetches whatever this says. Told to pick a
            // model without being told which one is coming, the difference between 190 MB and 874 MB
            // would arrive as a surprise on a metered month.
            TranscriptionModelField(
                modelId = modelId,
                onModelChange = { chosen ->
                    modelId = chosen
                    prefs.setTranscriptionModelId(chosen)
                }
            )

            TranscriptionModelRows(
                model = selectedModel,
                updateTrigger = updateTrigger,
                onDownload = onDownloadModel,
                onCancel = onCancelModelDownload,
                onDelete = onDeleteModel
            )
        }

        // Said even though the field already shows the language: the value arrived without being asked
        // for, and a pre-filled answer nobody explains is one people scroll past rather than check.
        // Only when there IS a pre-filled answer — a phone set to a language the app does not offer
        // lands on auto-detect, and the field's own warning is the right thing to read there.
        if (language != null) {
            NoteCard(stringResource(R.string.wizard_transcription_language_note))
        }

        // Built from the catalogue, not written out. This note used to name SMALL_Q5_1 and
        // LARGE_V3_TURBO_Q5_0 by hand into a four-argument format string, which meant adding a third
        // tier compiled cleanly and left the wizard quietly describing two of three models forever —
        // the one place in the transcription UI with no `when` to fail on. The dropdown right above
        // it already offered the model this note did not mention.
        //
        // `map` rather than `joinToString`, because only the inline one lets `stringResource` be
        // called per entry; the join then runs over plain strings.
        val modelSizes = TranscriptionModel.entries.map { model ->
            stringResource(
                R.string.wizard_transcription_model_size,
                stringResource(TranscriptionLabels.titleOf(model)),
                model.sizeBytes / BYTES_PER_MB
            )
        }
        // A new key rather than the old one narrowed to one argument: the ten values-* files still
        // hold a four-placeholder translation of `wizard_transcription_model_note`, and feeding a
        // one-argument call to a locale's %2$d throws rather than degrading.
        NoteCard(
            stringResource(
                R.string.wizard_transcription_model_sizes_note,
                modelSizes.joinToString(stringResource(R.string.list_separator))
            )
        )

        // A card of its own rather than the single sentence that used to be here. Summaries are half
        // of what this step is for, and a footnote pointing at Settings is how a feature ends up
        // never being found. The rows are the summariser's own, so the requirements dialog — 3.46 GB
        // to download, about 3.5 GB of memory to run — still stands between the tap and the fetch.
        CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.wizard_transcription_summary_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Text(
                text = stringResource(R.string.wizard_transcription_summary_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            SummaryRows(
                updateTrigger = updateTrigger,
                onDownload = onDownloadModel,
                onCancel = onCancelModelDownload,
                onDelete = onDeleteModel
            )
        }

        // Last, and about both downloads: whatever was started here does not have to finish here.
        NoteCard(stringResource(R.string.wizard_transcription_background_note))
    }
}

// ── String-resource mappers ───────────────────────────────────────────────────────────────────

private fun stepTitleRes(step: WizardStep): Int = when (step) {
    WizardStep.STORAGE -> R.string.wizard_storage_title
    WizardStep.SCHEDULE -> R.string.wizard_schedule_title
    WizardStep.AUTO_RECORD -> R.string.wizard_auto_record_title
    WizardStep.RELIABILITY -> R.string.wizard_reliability_title
    WizardStep.EXPERIMENTAL -> R.string.wizard_experimental_title
    WizardStep.UPDATES -> R.string.wizard_updates_title
    WizardStep.AUDIO -> R.string.wizard_audio_title
    WizardStep.FILE_NAME -> R.string.wizard_filename_title
    WizardStep.TRANSCRIPTION -> R.string.wizard_transcription_title
}

private fun stepSubtitleRes(step: WizardStep): Int = when (step) {
    WizardStep.STORAGE -> R.string.wizard_storage_subtitle
    WizardStep.SCHEDULE -> R.string.wizard_schedule_subtitle
    WizardStep.AUTO_RECORD -> R.string.wizard_auto_record_subtitle
    WizardStep.RELIABILITY -> R.string.wizard_reliability_subtitle
    WizardStep.EXPERIMENTAL -> R.string.wizard_experimental_subtitle
    WizardStep.UPDATES -> R.string.wizard_updates_subtitle
    WizardStep.AUDIO -> R.string.wizard_audio_subtitle
    WizardStep.FILE_NAME -> R.string.wizard_filename_subtitle
    WizardStep.TRANSCRIPTION -> R.string.wizard_transcription_subtitle
}

private fun storageTargetTitleRes(target: StorageTarget): Int = when (target) {
    StorageTarget.LOCAL -> R.string.storage_target_local
    StorageTarget.DRIVE -> R.string.storage_target_drive
    StorageTarget.BOTH -> R.string.storage_target_both
}

private fun storageTargetDescRes(target: StorageTarget): Int = when (target) {
    StorageTarget.LOCAL -> R.string.wizard_ui_storage_local_desc
    StorageTarget.DRIVE -> R.string.wizard_ui_storage_drive_desc
    StorageTarget.BOTH -> R.string.wizard_ui_storage_both_desc
}

