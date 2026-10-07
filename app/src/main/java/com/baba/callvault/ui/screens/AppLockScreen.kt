/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.ui.theme.CallVaultTheme

/**
 * What stands in for the app while it is locked.
 *
 * **It shows nothing about any call** — no contact, no count, not the most recent recording. A lock
 * screen that says "12 recordings, most recently Dana" has already given away most of what the lock
 * was for, to exactly the person it was meant to stop.
 *
 * The button matters more than it looks. The biometric prompt can be dismissed with a tap outside it,
 * and without a way to ask again the only route back in would be to force-stop the app.
 *
 * @param showDoor whether to draw the wording and the button at all. False while the system prompt is
 *   on its way or on screen: there is nothing for the user to do then, and drawing it anyway flashed
 *   an "Unlock" card on every single open. See [appLockUi], which decides this.
 */
@Composable
fun AppLockScreen(onUnlock: () -> Unit, showDoor: Boolean = true) {
    CallVaultTheme {
        // The Surface is drawn in both states, and that is the point: the background is identical
        // whether or not the door is on it, so a dismissed prompt reveals the button without the
        // screen appearing to change underneath it.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!showDoor) return@Surface
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.app_lock_locked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )
                Button(onClick = onUnlock) {
                    Text(stringResource(R.string.app_lock_unlock))
                }
            }
        }
    }
}
