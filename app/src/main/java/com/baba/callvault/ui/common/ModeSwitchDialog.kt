/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.server.ModeSwitchResult

/**
 * Shown while a privileged-mode switch is actually happening, and until it is known to have worked.
 *
 * Switching used to be silent — the toggle flipped and background work stopped one backend and started
 * another with nothing on screen to say whether it had succeeded. On a phone where both backends ended
 * up alive at once, that silence hid the problem for hours.
 *
 * So: **no dismiss while working** (no back, no outside tap, no button), because leaving mid-switch is
 * how you end up with neither backend running. The button appears only once there is an answer, and the
 * answer is decided by [ModeSwitchResult] — "ready" means a live recorder binder, nothing less.
 */
@Composable
fun ModeSwitchDialog(
    target: PrivilegedMode,
    result: ModeSwitchResult?,
    onDismiss: () -> Unit,
) {
    val working = result == null

    AlertDialog(
        // Uninterruptible while the work is in flight; freely dismissible once it is done.
        onDismissRequest = { if (!working) onDismiss() },
        // No icon slot: it centres its content above the title, and a success mark floating over the
        // headline reads as a separate thing rather than as part of it. The mark goes inline below.
        icon = if (working) {
            { CircularProgressIndicator(Modifier.size(24.dp)) }
        } else {
            null
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                stringResource(
                    when {
                        working && target.needsShizuku -> R.string.mode_switch_to_shizuku
                        working -> R.string.mode_switch_to_standalone
                        // Named, not generic. "Ready to record" alone does not say WHICH recorder is
                        // now serving the phone, which is the entire question the switch just answered.
                        result.isReady && target.needsShizuku -> R.string.mode_switch_done_shizuku
                        result.isReady -> R.string.mode_switch_done_standalone
                        else -> R.string.mode_switch_problem_title
                    }
                ),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!working) {
                    Spacer(Modifier.width(6.dp))
                    // Tinted explicitly. Left to the M3 default this comes out coral, so a "ready"
                    // dialog announced success in the colour the app uses for failure.
                    Icon(
                        imageVector = if (result.isReady) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = if (result.isReady) LocalCvBrand.current.success else LocalCvBrand.current.warning,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                Text(
                    text = when {
                        result == null -> stringResource(R.string.mode_switch_working)
                        // The success line names the backend too, so the modal reads as a statement
                        // about this phone rather than a generic reassurance.
                        result.isReady && target.needsShizuku ->
                            stringResource(R.string.mode_switch_ready_shizuku)
                        result.isReady -> stringResource(R.string.mode_switch_ready_standalone)
                        else -> stringResource(result.messageRes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(4.dp))
            }
        },
        confirmButton = {
            // Only once there is something to confirm. While working there is nothing to say yes to.
            if (!working) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_ok)) }
            }
        },
    )
}
