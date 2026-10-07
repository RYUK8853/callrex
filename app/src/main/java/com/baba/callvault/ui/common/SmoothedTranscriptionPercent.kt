/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The pill state to actually draw: the figure the run reports, held for a moment at 100 when it ends.
 *
 * The percentage itself is no longer worked out here. It used to be — the clock it is predicted from
 * lived in this composition — and that was the bug behind issue #34: rotating the phone destroys the
 * composition, so the clock restarted and the figure fell back to 1% and climbed the curve again,
 * which reads exactly like the transcription starting over. It never did; only the number moved.
 * The figure now belongs to the run that is producing it
 * ([com.baba.callvault.transcription.TranscriptionProgressTracker]), and this draws what it says.
 *
 * The hold at the end stays here, because it is about the screen rather than about the work: without
 * it the figure disappears at whatever it happened to read on the last tick — around seventy on a
 * short call — which looks like the run gave up rather than finished. A run that completed earned
 * its hundred, and showing it costs a second.
 *
 * A stop is not a finish and gets no hold: the work did not complete, and pretending otherwise would
 * be the one lie this whole feature cannot afford.
 */
@Composable
fun rememberTranscribingDisplay(state: TranscribingPillState): TranscribingPillState {
    val reported = state.percentFor(state.currentName().orEmpty())

    var holding by remember { mutableStateOf<TranscribingPillState?>(null) }

    // Both remembered while the run is going, because by the time they are needed the run is over:
    // `state` has gone Hidden and carries no percentage for the recording that just finished.
    var lastRunning by remember { mutableStateOf<TranscribingPillState?>(null) }
    var lastPercent by remember { mutableIntStateOf(0) }

    if (state.occupiesTitleSlot) {
        lastRunning = state
        if (reported > 0) lastPercent = reported
    }

    LaunchedEffect(state.occupiesTitleSlot) {
        if (state.occupiesTitleSlot) {
            holding = null
            return@LaunchedEffect
        }
        // Only a run that had actually got somewhere earns the hundred; one cancelled early, or
        // never really started, should simply vanish.
        val previous = lastRunning
        if (previous != null && lastPercent >= FINISHED_ENOUGH) {
            holding = previous.withPercent(100)
            delay(FINISH_HOLD_MS)
        }
        holding = null
        lastRunning = null
        lastPercent = 0
    }

    return holding ?: state
}

/** Near enough the end that finishing is what happened, rather than being stopped early. */
private const val FINISHED_ENOUGH = 50

/** Long enough to be read, short enough not to be in the way. */
private const val FINISH_HOLD_MS = 1_200L
