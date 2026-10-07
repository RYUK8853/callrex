/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.StringRes
import com.baba.callvault.R
import com.baba.callvault.data.transcripts.TranscriptStatus

/**
 * What the transcript slot in a recording row is currently offering.
 *
 * There is **one** slot, not a row of buttons: it starts a transcription, shows that one is running,
 * and then becomes the way to read the result. Which of those it is at any moment is decided here
 * rather than inside the composable, because this is the part that can be wrong and the composable is
 * the part that cannot be unit tested in this codebase.
 */
enum class TranscriptRowAction(
    @param:StringRes val contentDescriptionRes: Int,
    /** Whether a tap does anything. Work in flight is shown, not re-triggerable. */
    val isTappable: Boolean
) {
    /** No transcript yet — tapping starts one. */
    TRANSCRIBE(R.string.transcript_action_transcribe, isTappable = true),

    /** Queued or running. The two are indistinguishable to the user and deliberately look the same. */
    BUSY(R.string.transcript_action_busy, isTappable = false),

    /** Done — tapping opens the transcript. */
    OPEN(R.string.transcript_action_open, isTappable = true),

    /**
     * The last attempt failed.
     *
     * This is the only route back: the queue never re-offers a FAILED recording on its own, so that a
     * file which cannot be decoded is not retried every night forever.
     */
    RETRY(R.string.transcript_action_retry, isTappable = true);

    companion object {
        fun forStatus(status: TranscriptStatus): TranscriptRowAction = when (status) {
            TranscriptStatus.NONE -> TRANSCRIBE
            TranscriptStatus.QUEUED, TranscriptStatus.RUNNING -> BUSY
            TranscriptStatus.DONE -> OPEN
            TranscriptStatus.FAILED -> RETRY
        }
    }
}
