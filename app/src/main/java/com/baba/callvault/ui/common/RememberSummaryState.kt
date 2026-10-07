/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.summary.SummaryBlocker
import com.baba.callvault.summary.SummaryQueue
import com.baba.callvault.summary.SummaryScheduler
import com.baba.callvault.transcription.model.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * What the summary card should show for one recording.
 *
 * Two sources have to agree: the database, which says whether a summary exists and whether the
 * transcript it would be read from is finished, and WorkManager, which says whether one is being
 * written right now. They are combined rather than read separately for the same reason the model
 * download's two halves are — read apart, they contradict each other at exactly the moment they
 * matter, which is when a run finishes.
 */
/**
 * Which of a recording's jobs the card should reflect.
 *
 * **A live job always wins.** Every run for one recording carries the same tag, so after the first
 * rewrite there are several — and taking the last of them picked an arbitrary one. On a rewrite that
 * meant the finished job was chosen while the new one was actually running, so the card went on
 * showing the old summary and "Write it again" looked like it had done nothing. It had not: the
 * phone was at 641% CPU writing the replacement.
 *
 * With nothing live, the most recent finished job is the one that describes the state.
 */
private fun pickInteresting(mine: List<WorkInfo>): WorkInfo? =
    indexOfInteresting(mine.map { it.state })?.let(mine::get)

/**
 * Is there work outstanding for this recording?
 *
 * **Any state that is not finished counts.** This used to test for RUNNING or ENQUEUED, and missed
 * the one that actually occurs on a rewrite: work appended to an existing unique-work chain starts
 * **BLOCKED**, waiting on its predecessors. So a second summary was correctly queued, correctly
 * tagged, correctly found — and then read as "nothing is happening", leaving the card showing the
 * old summary while the phone worked.
 */
internal fun isPending(state: WorkInfo.State?): Boolean = state != null && !state.isFinished

/**
 * The index of the job that describes the current state, or null when there are none.
 *
 * Split out from [pickInteresting] so the rule itself is under test: `WorkInfo` cannot be
 * constructed outside WorkManager, and this rule is the part that was wrong.
 */
internal fun indexOfInteresting(states: List<WorkInfo.State>): Int? {
    if (states.isEmpty()) return null
    return states.indexOfFirst { !it.isFinished }.takeIf { it >= 0 } ?: states.lastIndex
}

@Composable
fun rememberSummaryState(displayName: String): State<SummaryCardState> {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val workManager = remember(context) { WorkManager.getInstance(context) }

    return produceState<SummaryCardState>(
        initialValue = SummaryCardState.Blocked(SummaryBlocker.NO_TRANSCRIPT),
        workManager, displayName
    ) {
        val db = TranscriptDatabase.get(appContext)

        // The transcript is in here too, and it is the fix for a real complaint: with a recording open,
        // finishing its transcription left the summary card still saying there was nothing to summarise
        // until you went back to the list and came in again.
        //
        // The card's "no transcript yet" answer comes from a one-shot read inside the collector, so it
        // is only ever recomputed when this flow emits — and the flow watched the SUMMARY row and the
        // summary's work info, neither of which changes when a TRANSCRIPT is written. Observing the
        // transcript as well means the card re-evaluates the moment one lands.
        combine(
            db.summaryDao().observe(displayName),
            db.transcriptDao().observe(displayName),
            workManager.getWorkInfosForUniqueWorkFlow(SummaryScheduler.WORK_NAME)
        ) { stored, _, infos ->
            // Only the job for *this* recording counts — the queue is shared, so a summary running
            // for a different call must not make this one look busy.
            //
            // Matched by tag, not by progress. Progress is cleared the moment a worker finishes, so
            // matching on it meant a job stopped being recognised exactly when its result mattered:
            // the card reverted to offering a summary it had just spent two minutes writing.
            val tag = SummaryScheduler.tagFor(displayName)
            val mine = pickInteresting(infos.filter { tag in it.tags })
            stored to mine
        }.collect { (stored, info) ->
            val running = isPending(info?.state)

            value = when {
                running -> SummaryCardState.Running(
                    SummaryScheduler.percentOf(info!!.progress) ?: 0
                )

                stored != null -> {
                    val parsed = withContext(Dispatchers.Default) { CallSummary.parse(stored.document) }
                    // A row that will not parse is not a summary. Offering to write another is more
                    // use than rendering a card with nothing in it.
                    parsed?.let { SummaryCardState.Ready(it) } ?: SummaryCardState.Offered
                }

                info?.state == WorkInfo.State.FAILED -> SummaryCardState.Failed

                else -> {
                    val installed = withContext(Dispatchers.IO) {
                        ModelRepository.isInstalled(appContext, SummaryModel.DEFAULT)
                    }
                    val blocker = SummaryQueue.blockerFor(appContext, displayName, installed)
                    if (blocker == null) SummaryCardState.Offered else SummaryCardState.Blocked(blocker)
                }
            }
        }
    }
}
