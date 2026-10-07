/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Hosts Settings as a panel that slides in over the current screen, instead of replacing it.
 *
 * Settings used to be a destination: `AppNavigationViewModel.manualScreen` swapped Home out for it
 * entirely. As a panel, Home stays composed underneath, so closing is instant and nothing behind has
 * to be rebuilt — and the panel arrives from the same edge as the gear that opens it.
 *
 * **Why the layout-direction flip.** `ModalNavigationDrawer` always opens from the *start* edge and
 * offers no side parameter. Providing [LayoutDirection.Rtl] to the drawer makes its start edge the
 * physical right; the ambient direction is then restored inside both the sheet and the content, so
 * nothing within either is mirrored. The original value is captured before the flip rather than
 * hard-coded to Ltr, so this still lands on the right in a right-to-left locale.
 *
 * @param drawerState Open/closed state, hoisted so the caller can open it from its own UI.
 * @param onClose     Invoked when the user dismisses the panel (back, scrim tap, or swipe).
 * @param settings    The Settings UI to render inside the panel.
 * @param content     The screen that stays mounted underneath.
 */
@Composable
fun SettingsSidebar(
    drawerState: DrawerState,
    onClose: () -> Unit,
    settings: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val ambientDirection = LocalLayoutDirection.current

    // The drawer owns the scrim tap and the swipe; back is ours to handle, and only while open —
    // an always-registered handler would swallow the gesture that leaves Home.
    BackHandler(enabled = drawerState.isOpen) { onClose() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Only when open: with gestures always live, an edge swipe on Home would drag the panel in
            // by accident, and on Home that edge belongs to the system back gesture.
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides ambientDirection) {
                    // Settings is dense — dropdowns, toggle rows and their supporting text — so it needs
                    // most of the width. The sliver left over keeps the panel legible as a panel: it is
                    // clear that Home is still there behind it, waiting.
                    ModalDrawerSheet(
                        // Match the app background rather than the drawer's default surface tint, so the
                        // panel reads as the app and CvScaffold's own header sits on the colour it expects.
                        drawerContainerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxWidth(SHEET_WIDTH_FRACTION),
                    ) {
                        settings()
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides ambientDirection) {
                content()
            }
        }
    }
}

/** How much of the screen the panel covers. Enough for Settings' dropdowns, short of hiding Home. */
private const val SHEET_WIDTH_FRACTION = 0.92f
