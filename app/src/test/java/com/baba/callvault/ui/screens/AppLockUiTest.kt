/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the window shows while the app lock has not been satisfied.
 *
 * A pure function because the bug it fixes is a *timing* bug, and timing bugs are the ones you
 * cannot see in a screenshot: the lock card was drawn for a frame before the biometric prompt
 * covered it, and again as the prompt tore down, so opening the app flashed an "Unlock" screen the
 * user never needed to act on. Deciding this from state rather than from lifecycle order is what
 * makes it testable at all.
 */
class AppLockUiTest {

    @Test
    fun `an unlocked app shows the app`() {
        assertEquals(AppLockUi.APP, appLockUi(lockEnabled = true, isUnlocked = true, promptDismissed = false))
    }

    @Test
    fun `with the lock turned off the app is shown without any gate`() {
        assertEquals(AppLockUi.APP, appLockUi(lockEnabled = false, isUnlocked = false, promptDismissed = false))
    }

    @Test
    fun `while the prompt is expected nothing is drawn over the background`() {
        // The regression this exists for: returning DOOR here is what flashed on every open.
        assertEquals(AppLockUi.WAITING, appLockUi(lockEnabled = true, isUnlocked = false, promptDismissed = false))
    }

    @Test
    fun `once the prompt has been dismissed the way back in is offered`() {
        // Without this the only route back into the app would be to force-stop it.
        assertEquals(AppLockUi.DOOR, appLockUi(lockEnabled = true, isUnlocked = false, promptDismissed = true))
    }

    @Test
    fun `unlocking after a dismissal shows the app, not the door`() {
        assertEquals(AppLockUi.APP, appLockUi(lockEnabled = true, isUnlocked = true, promptDismissed = true))
    }

    @Test
    fun `a dismissal recorded while the lock is off never gates the app`() {
        assertEquals(AppLockUi.APP, appLockUi(lockEnabled = false, isUnlocked = false, promptDismissed = true))
    }

    @Test
    fun `the locked states never reveal the app`() {
        // Belt and braces: no combination that leaves the lock unsatisfied may resolve to APP.
        listOf(false to false, false to true).forEach { (unlocked, dismissed) ->
            val shown = appLockUi(lockEnabled = true, isUnlocked = unlocked, promptDismissed = dismissed)
            assertEquals(false, shown == AppLockUi.APP)
        }
    }
}
