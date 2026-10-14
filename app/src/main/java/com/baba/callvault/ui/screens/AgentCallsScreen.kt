/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.takeover.TakeoverCallRepository
import com.baba.callvault.data.takeover.db.TakeoverCallEntry
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.theme.LocalCvBrand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Agent calls" page: the locally-cached view of the inbound SIP calls the voice agent
 * handled. Node is the source of truth — [TakeoverCallRepository.refresh] pulls the completed,
 * missed and ringing records on entry (and on the refresh tap) and upserts them into the Room —
 * so the page renders the cache and says nothing when the Node is unreachable (the cache is the
 * page, the poll is how it stays current).
 *
 * One card per call: number + time + who answered (the sheet's [TakeoverCallEntry.answerMode]),
 * then, under a divider, the agent's captured message, the call summary and the transcript —
 * the three fields the integration sheet calls out as the detail view for the agent case.
 */
@Composable
fun AgentCallsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }
    val repo = remember { TakeoverCallRepository(context) }
    val scope = rememberCoroutineScope()
    val brand = LocalCvBrand.current

    val entries by remember { repo.observeAll() }.collectAsState(initial = emptyList())
    var refreshing by rememberSaveable { mutableStateOf(false) }
    var notConfigured by remember {
        mutableStateOf(
            prefs.getTakeoverNodeUrl().isNullOrBlank() || prefs.getTakeoverNodeToken().isNullOrBlank(),
        )
    }

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    fun doRefresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repo.refresh()
                notConfigured =
                    prefs.getTakeoverNodeUrl().isNullOrBlank() || prefs.getTakeoverNodeToken().isNullOrBlank()
            } finally {
                refreshing = false
            }
        }
    }

    // First time on the page: pull once so a cache from a previous session fills its gaps.
    androidx.compose.runtime.LaunchedEffect(Unit) { doRefresh() }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.takeover_agent_calls_title),
        onBack = onBack,
        actions = {
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(onClick = { doRefresh() }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.takeover_agent_calls_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            EmptyAgentCalls(configured = !notConfigured, padding = innerPadding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.callId }) { entry ->
                    AgentCallCard(entry = entry, dateFormat = dateFormat)
                }
            }
        }
    }
}

@Composable
private fun EmptyAgentCalls(configured: Boolean, padding: PaddingValues) {
    val brand = LocalCvBrand.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 80.dp,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.takeover_agent_calls_empty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (configured) R.string.takeover_agent_calls_empty_configured
                    else R.string.takeover_agent_calls_empty_not_configured,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentCallCard(
    entry: TakeoverCallEntry,
    dateFormat: SimpleDateFormat,
) {
    val brand = LocalCvBrand.current
    val answeredByYou = entry.answerMode == "owner"
    val whenText = entry.startedAtMs?.let { dateFormat.format(Date(it)) }

    CvCard(onClick = {}, contentPadding = PaddingValues(16.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.phoneNumber ?: stringResource(R.string.takeover_unknown_number),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOfNotNull(
                            whenText,
                            entry.status?.let { s ->
                                stringResource(
                                    if (s == "completed") R.string.takeover_status_completed
                                    else if (s == "missed") R.string.takeover_status_missed
                                    else R.string.takeover_status_unknown,
                                )
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (answeredByYou) R.string.takeover_answered_by_you
                        else R.string.takeover_answered_by_agent,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (answeredByYou) brand.info else brand.success,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            entry.capturedMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.takeover_captured_message_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            entry.callSummary?.takeIf { it.isNotBlank() }?.let { sum ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.takeover_summary_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sum,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            entry.transcript?.takeIf { it.isNotBlank() }?.let { tr ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.takeover_transcript_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
