/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.PrivilegedMode

/**
 * Whether a recording may take the resilient (handoff) path.
 *
 * **Why this is not simply the user's preference.** The handoff path exists so a recording survives the
 * daemon dying mid-call: the *daemon* creates the privileged `AudioRecord` and hands the live capture to
 * the app, which reads the ring buffer itself. That requires the daemon's process to be able to record —
 * and a **Shizuku-hosted process cannot**. Measured on a OnePlus 9 Pro on 2026-08-24:
 *
 * ```
 * Direct capture unavailable, falling back to scrcpy: AudioRecord failed to enter RECORDING state
 * ```
 *
 * The daemon's own capture survives that by falling back to scrcpy, which spawns a separate process. The
 * handoff cannot fall back: the app has already been handed a record that will never produce a frame, so
 * it encodes nothing and writes a **0-byte file while reporting success**. That is exactly what a real
 * call produced on that phone before this existed.
 *
 * ShizuCallRecorder — which has run on Shizuku far longer than CallVault has — captures **only** through
 * scrcpy. Same conclusion, reached from the other direction.
 */
object HandoffPolicy {

    /**
     * @param enabled the user's "resilient recording" opt-in.
     * @param sourceSupported whether this audio source can be captured by a Java `AudioRecord` at all.
     * @param mode where privileges come from; [PrivilegedMode.SHIZUKU] rules the path out entirely.
     */
    fun isUsable(enabled: Boolean, sourceSupported: Boolean, mode: PrivilegedMode): Boolean =
        enabled && sourceSupported && !mode.needsShizuku
}
