/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Where a rebuilt capture waits to be collected.
 *
 * This exists because the thing it replaces did not work, and could not. The old code used a
 * `SynchronousQueue`, which hands a value over only if the receiver is *already waiting* — and the
 * receiver could never be, because the capture is delivered during the very call that requests it,
 * on a thread that has not reached its wait yet. Both sides then burned a four-second timeout in
 * sequence and the healthy capture was closed. Confirmed on a tester's phone: the two timeouts land
 * exactly 55.013+4000 and 59.018+4000 in the log, never overlapping.
 *
 * So the first test below is the whole point: a value left here before anyone waits must still be
 * collected. Everything else guards the fd, which leaks if a value is dropped without closing it.
 */
class HandoffSlotTest {

    private class Fake(val id: Int) {
        var closed = false
    }

    private fun slot() = HandoffSlot<Fake> { it.closed = true }

    @Test
    fun `a value left before anyone waits is still collected`() {
        // THE regression test. The old SynchronousQueue returned false here and closed the capture.
        val s = slot()
        val parked = Fake(1)

        assertTrue("parking must succeed with no receiver present", s.put(parked))

        assertEquals(parked, s.takeWithin(0L))
        assertFalse("a collected value must not be closed", parked.closed)
    }

    @Test
    fun `waiting when nothing ever arrives gives up and returns nothing`() {
        assertNull(slot().takeWithin(20L))
    }

    @Test
    fun `a value arriving while someone waits is collected`() {
        val s = slot()
        val late = Fake(2)
        thread {
            Thread.sleep(20L)
            s.put(late)
        }
        assertEquals(late, s.takeWithin(2_000L))
    }

    @Test
    fun `a second value while one is parked is refused rather than replacing it`() {
        // Refused, not swapped: the caller closes what it could not park. Swapping would strand the
        // first capture's fd with nobody holding a reference to close it.
        val s = slot()
        val first = Fake(1)
        val second = Fake(2)

        assertTrue(s.put(first))
        assertFalse("must refuse while occupied", s.put(second))
        assertEquals(first, s.takeWithin(0L))
    }

    @Test
    fun `clearing closes whatever was left behind`() {
        // A capture from an abandoned attempt must not sit here holding an fd open.
        val s = slot()
        val stale = Fake(1)
        s.put(stale)

        s.clear()

        assertTrue("a discarded value must be closed", stale.closed)
        assertNull("and must not be handed to the next attempt", s.takeWithin(0L))
    }

    @Test
    fun `clearing an empty slot is harmless`() {
        slot().clear()
        assertNull(slot().takeWithin(0L))
    }

    @Test
    fun `the slot is reusable across attempts`() {
        // rebuild() retries up to three times; attempt two must not inherit attempt one's leftovers.
        val s = slot()
        val first = Fake(1)
        s.put(first)
        s.clear()

        val second = Fake(2)
        assertTrue(s.put(second))
        assertEquals(second, s.takeWithin(0L))
        assertFalse(second.closed)
        assertTrue(first.closed)
    }
}
