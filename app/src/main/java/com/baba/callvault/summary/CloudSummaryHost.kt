/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.CloudSummaryConfig
import com.baba.callvault.data.cloud.CloudApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [SummaryModelHost] that asks the user's own OpenAI-compatible endpoint instead of loading the
 * on-device model.
 *
 * The runner does not know which host it holds: it chunks, prompts, parses and stores either way,
 * so a cloud summary carries exactly the same shape — citations, dedupe and all — as a local one.
 * Only the words change hands: a chunk's JSON prompt goes to [config.baseUrl]/chat/completions
 * with the user's key, and the answer comes back as plain text for the runner to parse.
 *
 * [modelPath] is part of the shared interface for the local host and unused here; the model is the
 * endpoint's, named by the user.
 */
class CloudSummaryHost(
    private val config: CloudSummaryConfig
) : SummaryModelHost {

    override suspend fun run(
        modelPath: String,
        block: suspend (SummarySession) -> CallSummary?
    ): CallSummary? = withContext(Dispatchers.IO) {
        block { prompt, maxTokens, _ ->
            // The grammar is a local-generation constraint (a json_schema the native engine enforces
            // while decoding). A chat endpoint either honours the request in the prompt or it does
            // not — the prompt already names the exact shape, and the parser accepts whatever comes
            // back that still parses, so there is nothing here to pass through.
            CloudApiClient.chatCompletion(config, prompt, maxTokens)
        }
    }
}
