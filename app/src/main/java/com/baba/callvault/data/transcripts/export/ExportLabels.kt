/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.export

/**
 * The section headings a Markdown export writes, in the reader's language.
 *
 * These used to be English literals inside the renderer, so someone reading "Points clés" on screen
 * exported the same transcript and got "Key points". The words were already translated — the export
 * simply never asked for them.
 *
 * Passed in rather than looked up here so this package stays free of Android resources and remains
 * unit-testable, and **deliberately has no default**: a default would be English, and a future caller
 * that forgot to pass it would silently reintroduce exactly the bug this type exists to fix. There is
 * one production caller, and [com.baba.callvault.ui.common.rememberExportLabels] is what it uses.
 *
 * Only Markdown needs these. TXT and the subtitle formats carry no headings, and JSON's keys are a
 * machine contract that must stay English whatever the reader speaks.
 */
data class ExportLabels(
    val summary: String,
    val notes: String,
    val transcript: String,
    val keyPoints: String,
    val decisions: String,
    val actionItems: String,
    val keyFacts: String,
    val language: String,
    val model: String,
)
