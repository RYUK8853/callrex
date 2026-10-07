/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of a recording's summary jobs the card reflects.
 *
 * Every run for one recording carries the same tag, so after the first rewrite there are several of
 * them. Taking the last picked an arbitrary one — and on a rewrite that meant the *finished* job was
 * chosen while the new one was actually running. The card went on showing the old summary and
 * "Write it again" looked like it had done nothing, while the phone sat at 641% CPU writing the
 * replacement.
 */
class SummaryWorkSelectionTest {

    @Test
    fun `no jobs means nothing to show`() {
        assertNull(indexOfInteresting(emptyList()))
    }

    @Test
    fun `a running job wins over a finished one`() {
        // The reported bug, exactly: one completed run and one in flight.
        val states = listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING)

        assertEquals(1, indexOfInteresting(states))
    }

    @Test
    fun `a queued job also wins over a finished one`() {
        // The first seconds after a tap, before the worker starts. This is the window the user was
        // looking at when nothing appeared to happen.
        val states = listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.ENQUEUED)

        assertEquals(1, indexOfInteresting(states))
    }

    @Test
    fun `a live job wins wherever it sits in the list`() {
        // WorkManager gives no ordering guarantee, which is what made "the last one" arbitrary.
        val states = listOf(WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED)

        assertEquals(0, indexOfInteresting(states))
    }

    @Test
    fun `with nothing live the most recent finished job describes the state`() {
        val states = listOf(WorkInfo.State.FAILED, WorkInfo.State.SUCCEEDED)

        assertEquals(1, indexOfInteresting(states))
    }

    @Test
    fun `a cancelled job is finished and does not win`() {
        val states = listOf(WorkInfo.State.CANCELLED, WorkInfo.State.RUNNING)

        assertEquals(1, indexOfInteresting(states))
    }

    @Test
    fun `a blocked job is outstanding work`() {
        // The state a rewrite actually produces. Work appended to an existing unique-work chain
        // waits on its predecessors as BLOCKED — testing only for RUNNING or ENQUEUED missed it,
        // so a queued summary read as "nothing is happening" and the card never changed.
        assertTrue(isPending(WorkInfo.State.BLOCKED))
    }

    @Test
    fun `running and queued are outstanding work`() {
        assertTrue(isPending(WorkInfo.State.RUNNING))
        assertTrue(isPending(WorkInfo.State.ENQUEUED))
    }

    @Test
    fun `finished states are not outstanding work`() {
        listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED).forEach {
            assertFalse("state $it", isPending(it))
        }
        assertFalse(isPending(null))
    }

    @Test
    fun `a single finished job is the answer`() {
        assertEquals(0, indexOfInteresting(listOf(WorkInfo.State.SUCCEEDED)))
    }
}
