/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Marks collected during the call in progress, before there is a name to store them against.
 *
 * They cannot be written as they are pressed. The recording's final name is not known until the call
 * ends — an anonymous number is renamed from the call log afterwards — so a row written mid-call
 * would be keyed to a name that no longer exists by the time anyone looked for it.
 *
 * One buffer for both capture paths rather than two, because only one recorder may run at a time;
 * two buffers would be two chances to drain the wrong one.
 */
object PendingFlags {

    private val offsetsMs = mutableListOf<Long>()

    /** Starts a fresh call. Any marks left by a call that ended badly are dropped here, not carried. */
    @Synchronized
    fun beginCall() {
        offsetsMs.clear()
    }

    /** Records a mark at [offsetMs] into the saved audio. */
    @Synchronized
    fun add(offsetMs: Long) {
        offsetsMs += offsetMs
    }

    /** How many marks the current call has, for the toast that confirms the button did something. */
    @Synchronized
    fun count(): Int = offsetsMs.size

    /**
     * Takes the marks and empties the buffer.
     *
     * Draining rather than reading is deliberate: it is called once at the end of a call, and a
     * buffer that kept its contents would spill the previous call's marks onto the next recording
     * if [beginCall] were ever missed.
     */
    @Synchronized
    fun drain(): List<Long> {
        val taken = offsetsMs.toList()
        offsetsMs.clear()
        return taken
    }
}
