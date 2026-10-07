/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers which steps the wizard shows, because one of them had a side effect: entering the reliability
 * step probed the USB default over the embedded shell, which turned **Wireless debugging on** in Shizuku
 * mode — a mode that never pairs and could never have used the answer.
 *
 * And which of them may withhold Next, because the transcription step now starts model downloads and
 * must not be allowed to hold setup hostage to one.
 */
class WizardStepsTest {

    @Test
    fun `omits the reliability step in Shizuku mode`() {
        val steps = wizardSteps(usesDrive = false, usesEmbeddedAdb = false)

        assertFalse(
            "The reliability step is embedded-ADB machinery end to end; showing it in Shizuku mode is " +
                "what switched Wireless debugging on.",
            steps.contains(WizardStep.RELIABILITY),
        )
    }

    @Test
    fun `keeps the reliability step when CallVault owns the ADB connection`() {
        val steps = wizardSteps(usesDrive = false, usesEmbeddedAdb = true)

        assertTrue(steps.contains(WizardStep.RELIABILITY))
    }

    @Test
    fun `offers the schedule step only when Drive is a target`() {
        assertTrue(wizardSteps(usesDrive = true, usesEmbeddedAdb = true).contains(WizardStep.SCHEDULE))
        assertFalse(wizardSteps(usesDrive = false, usesEmbeddedAdb = true).contains(WizardStep.SCHEDULE))
    }

    @Test
    fun `always offers the transcription step`() {
        // The one route a fresh install has to transcripts and summaries: What's New only fires after
        // an update, and the wizard cannot be re-run. It also needs nothing from ADB, so no mode or
        // storage choice may drop it.
        for (usesDrive in listOf(false, true)) {
            for (usesEmbeddedAdb in listOf(false, true)) {
                assertTrue(
                    "usesDrive=$usesDrive usesEmbeddedAdb=$usesEmbeddedAdb lost the transcription step",
                    wizardSteps(usesDrive, usesEmbeddedAdb).contains(WizardStep.TRANSCRIPTION),
                )
            }
        }
    }

    @Test
    fun `asks about transcription after the recording is made and named`() {
        val steps = wizardSteps(usesDrive = false, usesEmbeddedAdb = true)

        assertTrue(
            "Transcription reads a finished recording, so it follows capture, encoding and naming.",
            steps.indexOf(WizardStep.TRANSCRIPTION) > steps.indexOf(WizardStep.FILE_NAME),
        )
    }

    @Test
    fun `never withholds Next on the transcription step`() {
        // The step now starts the downloads: 190-574 MB for the speech model, 3.46 GB for the
        // summariser, both over Wi-Fi only. Gating on any of that would hold a user on mobile data
        // at step seven of nine until they got home — for work that finishes on its own afterwards.
        assertTrue(
            "A download in progress must never gate the wizard.",
            canAdvanceFrom(WizardStep.TRANSCRIPTION, hasRecordingFolder = false, hasDriveFolder = false),
        )
    }

    @Test
    fun `only the storage step can withhold Next`() {
        // Stated over the whole enum rather than the one step, so a future step cannot quietly add a
        // second gate: everything after storage is a preference or an opt-in, and none of them is a
        // reason to refuse to finish setting the app up.
        for (step in WizardStep.entries - WizardStep.STORAGE) {
            assertTrue(
                "$step must not gate the wizard",
                canAdvanceFrom(step, hasRecordingFolder = false, hasDriveFolder = false),
            )
        }
    }

    @Test
    fun `withholds Next until storage has every folder it needs`() {
        // A recording with nowhere to go is not a setup that works, which is the one thing worth
        // stopping for. The Drive folder is already reported as satisfied when Drive is not a target.
        assertFalse(canAdvanceFrom(WizardStep.STORAGE, hasRecordingFolder = false, hasDriveFolder = true))
        assertFalse(canAdvanceFrom(WizardStep.STORAGE, hasRecordingFolder = true, hasDriveFolder = false))
        assertTrue(canAdvanceFrom(WizardStep.STORAGE, hasRecordingFolder = true, hasDriveFolder = true))
    }

    @Test
    fun `keeps every other step in both modes`() {
        // The recording decisions, the audio settings and the update opt-in are mode-independent, so a
        // Shizuku user must still be asked about all of them — the exclusion is meant to be surgical.
        val shizuku = wizardSteps(usesDrive = true, usesEmbeddedAdb = false)

        assertEquals(
            wizardSteps(usesDrive = true, usesEmbeddedAdb = true) - WizardStep.RELIABILITY,
            shizuku,
        )
    }

    @Test
    fun `starts on storage and ends on updates whatever is excluded`() {
        // "Step N of M" is derived from this list, so order matters as much as membership.
        for (usesDrive in listOf(false, true)) {
            for (usesEmbeddedAdb in listOf(false, true)) {
                val steps = wizardSteps(usesDrive, usesEmbeddedAdb)
                assertEquals(WizardStep.STORAGE, steps.first())
                assertEquals(WizardStep.UPDATES, steps.last())
                assertEquals("No step is ever listed twice", steps.size, steps.toSet().size)
            }
        }
    }
}
