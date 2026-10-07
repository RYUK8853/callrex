/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The hand-off point between the app-side encoder and the post-call collection.
 *
 * Its whole job is to be single-call scoped. A value that outlived its recording would attach one
 * call's speaker turns to the next one's transcript — labelling the wrong conversation, and doing it
 * silently, which is worse than not labelling at all.
 */
class CapturedSpeakerTurnsTest {

    @Before
    fun reset() = CapturedSpeakerTurns.clear()

    @Test
    fun `hands over what the capture published`() {
        CapturedSpeakerTurns.publish("0:A;1200:B")

        assertEquals("0:A;1200:B", CapturedSpeakerTurns.takeIfPresent())
    }

    @Test
    fun `forgets the turns once they are taken`() {
        // The next recording must not inherit them.
        CapturedSpeakerTurns.publish("0:A")
        CapturedSpeakerTurns.takeIfPresent()

        assertEquals("", CapturedSpeakerTurns.takeIfPresent())
    }

    @Test
    fun `reports nothing when no capture published anything`() {
        assertEquals("", CapturedSpeakerTurns.takeIfPresent())
    }

    @Test
    fun `a new capture clears what the last one left behind`() {
        // The path that matters: a call whose encoder never reached its publish — a crash, a mono
        // capture, a daemon death — must not be given the previous call's turns.
        CapturedSpeakerTurns.publish("0:A;900:B")
        CapturedSpeakerTurns.clear()

        assertEquals("", CapturedSpeakerTurns.takeIfPresent())
    }
}
