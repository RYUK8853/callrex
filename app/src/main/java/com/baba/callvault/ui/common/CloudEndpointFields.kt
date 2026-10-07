/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences

/**
 * The "on-device or your own API" control: an engine switch and, when the user's endpoint is
 * chosen, the three fields that make a request possible — base URL, key, model.
 *
 * Shared by the transcription and the summarisation sections because the shape is identical and
 * the trust note must not be written twice: the words leave the phone only to the host the user
 * typed in, over HTTPS, with the key the user typed in.
 *
 * @param engineLabel The dropdown's label, naming which engine this is ("Transcription engine").
 * @param engine The stored engine id — [AppPreferences.ENGINE_LOCAL] or [AppPreferences.ENGINE_CLOUD].
 * @param onEngineChange Persisted by the caller (the settings view model, or the summary section's
 *   own preferences write), then the screen refreshes from it.
 * @param onClear Deletes the stored endpoint and key, for switching providers without losing the
 *   engine choice.
 */
@Composable
internal fun CloudEndpointFields(
    engineLabel: String,
    engine: String,
    baseUrl: String?,
    apiKey: String?,
    model: String?,
    onEngineChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onClear: () -> Unit
) {
    val isCloud = engine == AppPreferences.ENGINE_CLOUD
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        M3DropdownField(
            label = engineLabel,
            selected = listOf(
                OptionItem(AppPreferences.ENGINE_LOCAL, stringResource(R.string.cloud_engine_local)),
                OptionItem(AppPreferences.ENGINE_CLOUD, stringResource(R.string.cloud_engine_cloud))
            ).first { it.key == engine },
            options = listOf(
                OptionItem(AppPreferences.ENGINE_LOCAL, stringResource(R.string.cloud_engine_local)),
                OptionItem(AppPreferences.ENGINE_CLOUD, stringResource(R.string.cloud_engine_cloud))
            ),
            onOptionSelected = { onEngineChange(it.key) }
        )

        if (!isCloud) {
            // The whole point of the default, said where the switch lives: nothing about the call
            // leaves the device.
            Text(
                text = stringResource(R.string.cloud_local_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            return@Column
        }

        OutlinedTextField(
            value = baseUrl.orEmpty(),
            onValueChange = onBaseUrlChange,
            label = { Text(stringResource(R.string.cloud_url_label)) },
            placeholder = { Text(stringResource(R.string.cloud_url_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = apiKey.orEmpty(),
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(R.string.cloud_api_key_label)) },
            placeholder = { Text(stringResource(R.string.cloud_api_key_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = model.orEmpty(),
            onValueChange = onModelChange,
            label = { Text(stringResource(R.string.cloud_model_label)) },
            placeholder = { Text(stringResource(R.string.cloud_model_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Text(
            text = stringResource(R.string.cloud_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        TextButton(onClick = onClear, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(stringResource(R.string.cloud_clear))
        }
    }
}
