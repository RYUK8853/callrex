/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression these cover is the 2026-07-30 field wedge: one relaunch attempt that never returned
 * latched the gate closed for the life of the process, so the watchdog stopped relaunching the daemon
 * and recording died silently for ~21 hours. See `2026-07-30-keepalive-rewarm-latch-wedge.md`.
 */
class RewarmGateTest {

    private val stuckAfterMs = 90_000L
    private val throttleMs = 20_000L

    private fun gate() = RewarmGate(stuckAfterMs = stuckAfterMs, throttleMs = throttleMs)

    @Test
    fun `allows the first attempt`() {
        val gate = gate()

        assertTrue(gate.tryEnter(now = 0L, force = false))
    }

    @Test
    fun `blocks a second attempt while the first is still in flight`() {
        val gate = gate()
        gate.tryEnter(now = 0L, force = false)

        assertFalse(gate.tryEnter(now = 1_000L, force = false))
    }

    @Test
    fun `blocks an in-flight attempt even when forced`() {
        // force bypasses the throttle, never the in-flight guard — two concurrent launches thrash
        // the ADB connection, which is what the guard exists to prevent.
        val gate = gate()
        gate.tryEnter(now = 0L, force = false)

        assertFalse(gate.tryEnter(now = 1_000L, force = true))
    }

    @Test
    fun `allows a new attempt once an in-flight attempt is presumed stuck`() {
        // THE BUG: without this, a relaunch that never returns vetoes every future relaunch forever.
        val gate = gate()
        gate.tryEnter(now = 0L, force = false)

        assertFalse(gate.tryEnter(now = stuckAfterMs - 1, force = false))
        assertTrue(gate.tryEnter(now = stuckAfterMs, force = false))
    }

    @Test
    fun `a superseded stuck attempt does not leave the gate permanently open`() {
        val gate = gate()
        gate.tryEnter(now = 0L, force = false)
        gate.tryEnter(now = stuckAfterMs, force = false) // supersedes the stuck one

        // the replacement is itself in flight, so it gets the same protection
        assertFalse(gate.tryEnter(now = stuckAfterMs + 1, force = false))
    }

    @Test
    fun `throttles an un-forced attempt that arrives too soon after one completed`() {
        val gate = gate()
        gate.tryEnter(now = 0L, force = false)
        gate.leave()

        assertFalse(gate.tryEnter(now = throttleMs - 1, force = false))
        assertTrue(gate.tryEnter(now = throttleMs, force = false))
    }

    @Test
    fun `a forced attempt ignores the throttle`() {
        // a confirmed binder death must recover immediately, not wait out the throttle
        val gate = gate()
        gate.tryEnter(now = 0L, force = false)
        gate.leave()

        assertTrue(gate.tryEnter(now = 1L, force = true))
    }

    @Test
    fun `leaving lets the next attempt in once the throttle has passed`() {
        val gate = gate()
        gate.tryEnter(now = 0L, force = false)
        gate.leave()
        assertTrue(gate.tryEnter(now = throttleMs, force = false))
        gate.leave()

        assertTrue(gate.tryEnter(now = throttleMs * 2, force = false))
    }

    @Test
    fun `leave is safe when no attempt is in flight`() {
        val gate = gate()

        gate.leave()

        assertTrue(gate.tryEnter(now = 0L, force = false))
    }
}
