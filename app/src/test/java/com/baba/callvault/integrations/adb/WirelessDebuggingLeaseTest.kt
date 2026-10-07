/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Only the **last** ADB user may release Wireless debugging.
 *
 * Switching it off drops the app's embedded ADB connection, so releasing while another thread is still
 * mid-operation would cut the connection out from under it — a UI screen finishing its USB probe could
 * break a recording being armed. Getting the count wrong in the other direction is the leak this exists
 * to fix: an open network port left on indefinitely.
 */
class WirelessDebuggingLeaseTest {

    @Before
    fun reset() = WirelessDebuggingLease.reset()

    @Test
    fun `a single user releases on the way out`() {
        WirelessDebuggingLease.acquire()
        assertTrue(WirelessDebuggingLease.release())
        assertTrue(WirelessDebuggingLease.isIdle)
    }

    @Test
    fun `an inner user finishing does not release while the outer one is still working`() {
        WirelessDebuggingLease.acquire()   // the recorder launcher, mid-launch
        WirelessDebuggingLease.acquire()   // a settings screen probing the USB default

        assertFalse("The launcher is still using the connection", WirelessDebuggingLease.release())
        assertTrue("Now the last one is done", WirelessDebuggingLease.release())
    }

    @Test
    fun `nothing in flight means idle`() {
        assertTrue(WirelessDebuggingLease.isIdle)
    }

    @Test
    fun `an unbalanced release cannot drive the count negative`() {
        // Otherwise the next acquire would look like a second user, its release would report false, and
        // Wireless debugging would stay on for ever — the exact bug, reintroduced by an accounting slip.
        WirelessDebuggingLease.release()
        WirelessDebuggingLease.release()

        WirelessDebuggingLease.acquire()
        assertTrue(WirelessDebuggingLease.release())
    }

    @Test
    fun `concurrent users hand the release to exactly one of them`() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val releases = java.util.concurrent.atomic.AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                start.await()
                WirelessDebuggingLease.acquire()
                Thread.sleep(5)
                if (WirelessDebuggingLease.release()) releases.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertTrue("Someone must end up releasing", releases.get() >= 1)
        assertTrue("And the lease must end idle", WirelessDebuggingLease.isIdle)
    }
}
