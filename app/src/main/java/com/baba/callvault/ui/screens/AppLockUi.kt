/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

/** What the window shows while the app lock has not yet been satisfied. */
enum class AppLockUi {
    /** The app itself: either unlocked, or the lock is turned off. */
    APP,

    /** Locked, with the biometric prompt on its way or already on screen. Background only. */
    WAITING,

    /** Locked, and the prompt was dismissed — offer the way back in. */
    DOOR,
}

/**
 * Which of the three the window should draw.
 *
 * **Why this is a function of state rather than of lifecycle order.** The lock card used to be shown
 * for every locked frame, and `onCreate` composes before `onStart` asks for the prompt — so opening
 * the app drew "CallVault / locked / Unlock" for a frame, the system prompt covered it, and it
 * flashed again as the prompt tore down. The card was never something the user had to act on; it
 * exists only for the case where they dismissed the prompt and would otherwise have to force-stop
 * the app to get back in. [WAITING] is that distinction made explicit.
 *
 * [promptDismissed] is deliberately the trigger rather than "is a prompt showing". A prompt that has
 * not appeared yet and a prompt currently up want the same quiet background, and only an
 * unauthenticated *return* means the user is stuck and needs the button.
 */
fun appLockUi(lockEnabled: Boolean, isUnlocked: Boolean, promptDismissed: Boolean): AppLockUi = when {
    !lockEnabled || isUnlocked -> AppLockUi.APP
    promptDismissed -> AppLockUi.DOOR
    else -> AppLockUi.WAITING
}
