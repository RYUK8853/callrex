/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a mode switch is allowed to call "ready".
 *
 * Switching used to be silent: the toggle flipped, work happened on a background thread, and nothing on
 * screen said whether a recorder was actually running at the end of it. The dialog that replaced it can
 * only be honest if "ready" means a live binder — so this decides that, over facts, without a device.
 */
class ModeSwitchResultTest {

    @Test
    fun a_connected_recorder_is_ready_in_either_mode() {
        assertEquals(
            ModeSwitchResult.Ready,
            ModeSwitchResult.of(PrivilegedMode.STANDALONE, connected = true, voipArmed = true, shizuku = ShizukuStatus.NOT_INSTALLED)
        )
        assertEquals(
            ModeSwitchResult.Ready,
            ModeSwitchResult.of(PrivilegedMode.SHIZUKU, connected = true, voipArmed = true, shizuku = ShizukuStatus.READY)
        )
    }

    @Test
    fun shizuku_failures_name_the_step_the_user_can_fix() {
        assertEquals(
            ModeSwitchResult.ShizukuNotInstalled,
            ModeSwitchResult.of(PrivilegedMode.SHIZUKU, connected = false, voipArmed = true, shizuku = ShizukuStatus.NOT_INSTALLED)
        )
        assertEquals(
            ModeSwitchResult.ShizukuNotRunning,
            ModeSwitchResult.of(PrivilegedMode.SHIZUKU, connected = false, voipArmed = true, shizuku = ShizukuStatus.NOT_RUNNING)
        )
        assertEquals(
            ModeSwitchResult.ShizukuNotPermitted,
            ModeSwitchResult.of(PrivilegedMode.SHIZUKU, connected = false, voipArmed = true, shizuku = ShizukuStatus.NO_PERMISSION)
        )
    }

    @Test
    fun shizuku_ready_but_no_binder_is_its_own_failure() {
        // Everything the user could do is done, and it still did not come up — that is ours, not theirs,
        // and saying "start Shizuku" here would send them to fix something that is not broken.
        assertEquals(
            ModeSwitchResult.RecorderDidNotStart,
            ModeSwitchResult.of(PrivilegedMode.SHIZUKU, connected = false, voipArmed = true, shizuku = ShizukuStatus.READY)
        )
    }

    @Test
    fun standalone_without_a_binder_is_always_ours_to_explain() {
        // Shizuku's state is irrelevant in standalone; reporting it would be a red herring.
        assertEquals(
            ModeSwitchResult.RecorderDidNotStart,
            ModeSwitchResult.of(PrivilegedMode.STANDALONE, connected = false, voipArmed = true, shizuku = ShizukuStatus.READY)
        )
    }

    @Test
    fun only_ready_counts_as_success() {
        assertEquals(true, ModeSwitchResult.Ready.isReady)
        listOf(
            ModeSwitchResult.ShizukuNotInstalled,
            ModeSwitchResult.ShizukuNotRunning,
            ModeSwitchResult.ShizukuNotPermitted,
            ModeSwitchResult.RecorderDidNotStart,
        ).forEach { assertEquals("$it must not read as ready", false, it.isReady) }
    }

    @Test
    fun `a switch is not ready until VoIP capture is armed`() {
        // Arming is a blocking IPC the app runs on its own thread, so ensureRunning returns — and the
        // dialog used to say "Ready" — while it was still in flight. A VoIP call landing in that window
        // is lost for good: routing is fixed when the capture track is created, so there is no arming it
        // late and no retry. The dialog must not call that a finished switch.
        assertEquals(
            ModeSwitchResult.VoipNotArmed,
            ModeSwitchResult.of(
                PrivilegedMode.STANDALONE, connected = true, voipArmed = false,
                shizuku = ShizukuStatus.NOT_INSTALLED
            )
        )
    }

    @Test
    fun `arming is irrelevant when no recorder came up at all`() {
        // Reporting "VoIP is not armed" to someone whose recorder never started sends them to fix the
        // wrong thing entirely.
        assertEquals(
            ModeSwitchResult.RecorderDidNotStart,
            ModeSwitchResult.of(
                PrivilegedMode.STANDALONE, connected = false, voipArmed = false,
                shizuku = ShizukuStatus.NOT_INSTALLED
            )
        )
    }
}
