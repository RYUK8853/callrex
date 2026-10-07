/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.transcripts.export.ExportLabels

/**
 * The export headings in the current locale.
 *
 * Resolved in composition and captured, because the export itself runs from a click handler where
 * [stringResource] cannot be called.
 *
 * Every heading reuses the string the same section already shows on screen, so an exported file and
 * the app can never disagree about what a section is called — including in English, where the export
 * used to say "Action items" and "Key facts" while the app said "To do" and "Worth keeping".
 */
@Composable
fun rememberExportLabels(): ExportLabels = ExportLabels(
    summary = stringResource(R.string.summary_card_title),
    notes = stringResource(R.string.playback_note_title),
    transcript = stringResource(R.string.export_heading_transcript),
    keyPoints = stringResource(R.string.summary_card_key_points),
    decisions = stringResource(R.string.summary_card_decisions),
    actionItems = stringResource(R.string.summary_card_actions),
    keyFacts = stringResource(R.string.summary_card_facts),
    language = stringResource(R.string.transcription_language_label),
    model = stringResource(R.string.transcription_model_label),
)
