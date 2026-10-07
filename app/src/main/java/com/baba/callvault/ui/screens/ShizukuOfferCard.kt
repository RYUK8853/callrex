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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.ShizukuStatus

/**
 * What the Shizuku offer card may honestly say, and what it may offer.
 *
 * Kept out of the composable because the wrong branch here renders perfectly. The card used to greet a
 * *stopped* Shizuku server with "Shizuku is running on this phone" and let the user pick it anyway —
 * and picking it finishes onboarding with nothing that can record, because choosing Shizuku counts as a
 * set-up transport whether or not its server is alive (`AppPreferences.isPrivilegedTransportSetUp`).
 */
enum class ShizukuOffer {

    /** Shizuku can serve a recorder and has not been chosen yet — the fork is a real choice. */
    Fork,

    /** Shizuku cannot serve anything right now. Choosing it would finish setup with no recorder. */
    NotRunning,

    /** Chosen, and only Shizuku's own permission is still missing. */
    NeedsPermission,

    /** Chosen and working. */
    Active,

    ;

    /**
     * Whether picking Shizuku may be offered at all.
     *
     * Never while its server is down: that is the tap that completes onboarding into a dead setup.
     */
    val canChooseShizuku: Boolean get() = this == Fork

    companion object {
        /**
         * @param status Shizuku's live state. [ShizukuStatus.NOT_INSTALLED] does not normally reach
         *   here — `PermissionsContent` renders no card at all then, because CallVault must never read
         *   as an instruction to install a second app — and folds into [NotRunning] if it ever does,
         *   which stays true either way: an app that is not there is not running.
         */
        fun from(status: ShizukuStatus, mode: PrivilegedMode): ShizukuOffer = when {
            // Liveness first, in BOTH modes. A stopped server is the truth whether or not it was the
            // choice, and the branch that reported "setup is done" to a Shizuku user whose server had
            // stopped was reached simply by never asking.
            status == ShizukuStatus.NOT_INSTALLED || status == ShizukuStatus.NOT_RUNNING -> NotRunning
            !mode.needsShizuku -> Fork
            status == ShizukuStatus.NO_PERMISSION -> NeedsPermission
            else -> Active
        }
    }
}

/**
 * Offers Shizuku during setup — but only to someone who already has it.
 *
 * The whole point of CallVault is that it needs no second app, so this must never read as an
 * instruction to go and install one. `PermissionsScreen` therefore renders this only when Shizuku is
 * on the phone at all: for everyone else the card does not exist and setup proceeds as it always has.
 *
 * Installed is not the same as usable, and the card says which — see [ShizukuOffer]. While the server
 * is down the fork is not offered, only described, because choosing Shizuku there ends onboarding with
 * no working recorder and nothing on screen to say so.
 *
 * When it is a real choice it is a real fork, so both sides are buttons of equal weight — "Use Shizuku"
 * skips pairing entirely, "Use CallVault" is the ordinary path. Neither is styled as the
 * recommendation, because which one is better genuinely depends on whether this person wants to keep
 * Shizuku running.
 *
 * Colours are stated explicitly rather than left to the M3 defaults: this app's scheme resolves several
 * of those to its coral tone, and a setup card that shows up red reads as an error.
 */
@Composable
fun ShizukuOfferCard(
    status: ShizukuStatus,
    mode: PrivilegedMode,
    onUseShizuku: () -> Unit,
    onUseCallVault: () -> Unit,
    onGrant: () -> Unit,
) {
    val offer = ShizukuOffer.from(status, mode)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    when (offer) {
                        // The standing title asserts the server is up, so it may only be shown when it is.
                        ShizukuOffer.NotRunning -> R.string.permissions_shizuku_offer_title_stopped
                        else -> R.string.permissions_shizuku_offer_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    when (offer) {
                        ShizukuOffer.NotRunning -> R.string.permissions_shizuku_offer_stopped
                        ShizukuOffer.Fork -> R.string.permissions_shizuku_offer_body
                        ShizukuOffer.NeedsPermission -> R.string.permissions_shizuku_needs_permission
                        ShizukuOffer.Active -> R.string.permissions_shizuku_active
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                // On every branch, because it is the way out of the fork — and while Shizuku is down
                // it is the only honest way on.
                TextButton(onClick = onUseCallVault) {
                    Text(stringResource(R.string.permissions_shizuku_use_callvault))
                }
                if (offer.canChooseShizuku) {
                    TextButton(onClick = onUseShizuku) {
                        Text(stringResource(R.string.permissions_shizuku_use))
                    }
                }
                // Chosen, but Shizuku has not let us in yet — the one remaining step.
                if (offer == ShizukuOffer.NeedsPermission) {
                    TextButton(onClick = onGrant) {
                        Text(stringResource(R.string.settings_shizuku_grant))
                    }
                }
            }
        }
    }
}
