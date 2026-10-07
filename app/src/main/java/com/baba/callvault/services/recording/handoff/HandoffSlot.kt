/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A one-place parking slot for a rebuilt capture, handed from the delivery thread to the supervisor.
 *
 * **Why this is not a `SynchronousQueue`.** It was, and that is why mid-call recovery never worked
 * once. A `SynchronousQueue` completes a hand-off only when the receiver is *already* waiting, and
 * here it never can be: the supervisor asks the daemon for a replacement capture, and the daemon
 * delivers it **during that request**, before the asking call has returned and before the supervisor
 * has reached its wait. The two sides therefore took turns instead of overlapping — each burning a
 * full four-second timeout — and a perfectly healthy capture was closed for want of a receiver.
 *
 * Confirmed on a tester's phone (report 30): the delivery gave up at 55.013 + 4000 ms and the
 * receiver only started waiting at 59.018, giving up in turn at 03.018. Never overlapping, so it
 * failed every time rather than occasionally. Raising the timeout cannot help — see
 * `docs/dev-notes/2026-09-03-shell-mic-open-first-evidence.md`.
 *
 * One place, not a queue of many: there is only ever one capture in flight, and a backlog here would
 * mean handing the drain a capture from an attempt that has already been abandoned.
 *
 * @param close how to dispose of a value this slot discards. Every path that drops a value calls it,
 *   because the value owns a file descriptor and dropping it silently leaks that fd for the life of
 *   the process.
 */
class HandoffSlot<T>(private val close: (T) -> Unit) {

    private val slot = ArrayBlockingQueue<T>(1)

    /**
     * Parks [value] for the supervisor to collect. Returns false if something is already parked, in
     * which case the caller still owns [value] and must close it.
     *
     * Never blocks, and that is deliberate: this runs on a binder thread serving the daemon, and the
     * old blocking version held that thread for four seconds on every failed hand-off — which is why
     * the delivery in report 30 appears to take four seconds. It was waiting on us.
     */
    fun put(value: T): Boolean = slot.offer(value)

    /** Collects a parked capture, waiting up to [timeoutMs] for one. Null if none arrives. */
    fun takeWithin(timeoutMs: Long): T? = slot.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Empties the slot, closing anything found.
     *
     * Called before each attempt and after a failed one. Without it, a capture delivered too late to
     * be used would sit here holding its fd open, and the *next* attempt would collect a capture
     * belonging to the attempt before it — pointing the drain at shared memory whose track is gone.
     */
    fun clear() {
        while (true) close(slot.poll() ?: return)
    }
}
