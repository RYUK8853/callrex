/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.takeover.TakeoverCallRepository
import com.baba.callvault.services.takeover.TakeoverApiClient.TakeoverException
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The full-screen answer screen for an inbound SIP call. It is a plain [Activity] — not a
 * `WindowManager` overlay and not `SYSTEM_ALERT_WINDOW` — launched by [RingingMonitorService]
 * with `FLAG_ACTIVITY_NEW_TASK`, `showWhenLocked` and `turnScreenOn`. That combination puts it
 * above the lock screen and turns the screen on with no extra permission, which is exactly the
 * ringback experience the sheet wants (answer on a locked phone).
 *
 * **The screen is a pure function of [TakeoverState].** The monitor owns the *lifecycle* of a
 * call (it knows when a room appears / leaves `/sip/ringing`); this Activity only renders whatever
 * phase the state says and routes button taps back to the engine through [TakeoverApiClient].
 * When the room leaves the ringback list the monitor clears [TakeoverState.current] and sets the
 * phase to IDLE, and the `DisposableEffect` below finishes the Activity — so a call that ends
 * server-side (answered elsewhere, window expired) closes the overlay with no tap required.
 *
 * **Three buttons, three outcomes:**
 *  - "Main baat karta hoon"  → `human-join` → LiveKit join, mic on → phase OWNER_CALL.
 *  - "AI agent utha le"     → `agent-takeover` → phase AGENT_CALL + live transcript.
 *  - "Decline"              → `call-drop` → phase IDLE (or the room simply leaves).
 *
 * A 404 (room gone) or 409 (already answered) on any of the first two is a STALE OVERLAY, not an
 * error to show: it means the window already resolved, so the correct UX is to close (404) or
 * flip to the agent view (409), never a red error card over a call that is no longer there.
 */
class TakeoverOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROOM = "takeover_extra_room"
        const val EXTRA_CALL_DB_ID = "takeover_extra_call_db_id"
        const val EXTRA_PHONE = "takeover_extra_phone"
    }

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Above the lock screen, screen on — these are Activity (API 27+) calls, so they live
        // here, not in the service that launches us. minSdk is 30, so both are always available.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        setContentView(null)
        setContent { CallVaultTheme { OverlayRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // If the state went IDLE while we were away (room resolved server-side), close.
        if (TakeoverState.phase.value == TakeoverPhase.IDLE && TakeoverState.current.value == null) {
            AppLogger.i(TAG, "onResume with no active call — closing overlay")
            finish()
        }
    }

    override fun onDestroy() {
        // Leave the LiveKit room if we are still in an owner call when the activity dies
        // (e.g. the window closed server-side while we were on the line). The monitor owns the
        // authoritative end; this is the belt.
        if (TakeoverState.phase.value == TakeoverPhase.OWNER_CALL) {
            LiveKitRoomManager.disconnect()
        }
        scope.cancel()
        super.onDestroy()
    }
}

@Composable
private fun OverlayRoot() {
    val phase by TakeoverState.phase.collectAsState()
    val current by TakeoverState.current.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Close when the monitor resolves the call (phase -> IDLE and no current).
    DisposableEffect(phase, current) {
        if (phase == TakeoverPhase.IDLE && current == null) {
            (context as? ComponentActivity)?.finish()
        }
        onDispose { }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (phase) {
            TakeoverPhase.IDLE -> Unit // transient: the room may already be resolved
            TakeoverPhase.RINGING -> RingingPanel(current, context, scope)
            TakeoverPhase.OWNER_CALL -> OwnerCallPanel()
            TakeoverPhase.AGENT_CALL -> AgentCallPanel(context, scope)
        }
    }
}

// ------------------------------------------------------------------
// RINGING — the decision screen
// ------------------------------------------------------------------

@Composable
private fun RingingPanel(
    current: TakeoverCurrentCall?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val prefs = AppPreferences(context.applicationContext)
    var busy by remember { mutableStateOf(false) }

    // 1 s tick so the ringback timer updates and the "number fills in on a later tick" refreshes.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val left = (current?.secondsLeftNow(nowMs) ?: 0).coerceAtLeast(0)
    val fraction = (left.toFloat() / RINGBACK_WINDOW_SECONDS).coerceIn(0f, 1f)
    val number = current?.phoneNumber
        ?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.takeover_unknown_number)

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.takeover_ringing_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            number,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.takeover_ringing_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Ringback timer bar: drains over the window. The authoritative end is the room leaving
        // /sip/ringing; this is the display.
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        if (left <= 10) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${left}s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.takeover_joining))
        } else {
            // Owner — primary, full-width, the recommended path.
            Button(
                onClick = {
                    if (current != null) {
                        busy = true
                        onOwnerPickup(current, prefs, context, scope) { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Filled.Person, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.takeover_btn_owner), fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (current != null) {
                        busy = true
                        onAgentTakeover(current, prefs, scope) { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.takeover_btn_agent), fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = {
                    if (current != null) {
                        busy = true
                        onDecline(current, prefs, scope) { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.DoNotDisturb, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.takeover_btn_decline), color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------
// OWNER_CALL — you are on the line
// ------------------------------------------------------------------

@Composable
private fun OwnerCallPanel() {
    var muted by remember { mutableStateOf(!LiveKitRoomManager.microphoneEnabled) }
    val number = remember { TakeoverState.current.value?.phoneNumber }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person, contentDescription = null,
                modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            number ?: stringResource(R.string.takeover_unknown_number),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.takeover_owner_live),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.takeover_owner_live_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = {
                    val next = !muted
                    muted = next
                    LiveKitRoomManager.setMicrophoneEnabled(!next)
                },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(if (muted) stringResource(R.string.takeover_btn_unmute)
                     else stringResource(R.string.takeover_btn_mute))
            }
            Button(
                onClick = {
                    LiveKitRoomManager.disconnect()
                    TakeoverState.setPhase(TakeoverPhase.IDLE)
                    TakeoverState.setCurrent(null)
                },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(
                    stringResource(R.string.takeover_btn_end),
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------
// AGENT_CALL — the AI is on the call, live transcript
// ------------------------------------------------------------------

@Composable
private fun AgentCallPanel(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val agent by TakeoverState.agent.collectAsState()
    val repo = remember { TakeoverCallRepository(context.applicationContext) }
    var transcript by remember { mutableStateOf<String?>(null) }

    val callId = agent?.callDbId
    LaunchedEffect(callId) {
        if (callId != null) {
            repo.observeTranscript(callId).collect { transcript = it }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Call, contentDescription = null,
                modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.takeover_agent_live),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.takeover_agent_live_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            SelectionContainer {
                Text(
                    transcript ?: stringResource(R.string.takeover_agent_transcript_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (transcript == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------
// Button actions
// ------------------------------------------------------------------

/**
 * "Main baat karta hoon": ask the engine for a participant token, join the room over WebRTC with
 * the mic on, and flip to OWNER_CALL. The owner-case RECORDING is not started here — when the
 * LiveKit mic goes live the device audio mode flips to MODE_IN_COMMUNICATION and the existing
 * VoIP detector ([com.baba.callvault.services.recording.VoipCallDetector]) arms the same capture
 * pipeline it uses for WhatsApp/Signal calls; the [TakeoverState.setOwnerWriteback] call here only
 * records WHAT to push to Node once that local recording is transcribed.
 */
private fun onOwnerPickup(
    current: TakeoverCurrentCall,
    prefs: AppPreferences,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    val room = current.roomName
    val engineUrl = prefs.getTakeoverEngineUrl() ?: return
    val engineToken = prefs.getTakeoverEngineToken()
    scope.launch(Dispatchers.IO) {
        try {
            val join = TakeoverApiClient.joinAsOwner(engineUrl, room, engineToken)
            LiveKitRoomManager.join(context, join)
            TakeoverState.setPhase(TakeoverPhase.OWNER_CALL)
            TakeoverState.setOwnerWriteback(
                TakeoverState.OwnerWriteback(
                    callDbId = current.callDbId ?: room,
                    phoneNumber = current.phoneNumber,
                    recordingStartedMs = System.currentTimeMillis(),
                    engineUrl = engineUrl,
                    nodeUrl = prefs.getTakeoverNodeUrl().orEmpty(),
                    engineToken = engineToken,
                    nodeToken = prefs.getTakeoverNodeToken(),
                ),
            )
            AppLogger.i(TAG, "owner joined room $room")
        } catch (e: TakeoverException) {
            when (e.status) {
                404 -> {
                    AppLogger.i(TAG, "human-join 404: room gone — closing overlay")
                    TakeoverState.setPhase(TakeoverPhase.IDLE)
                    TakeoverState.setCurrent(null)
                }
                409 -> {
                    AppLogger.i(TAG, "human-join 409: already answered — switching to agent view")
                    TakeoverState.setPhase(TakeoverPhase.AGENT_CALL)
                    TakeoverState.setAgent(
                        AgentCallState(
                            callDbId = current.callDbId,
                            startedMs = System.currentTimeMillis(),
                        ),
                    )
                }
                else -> AppLogger.e(TAG, "human-join failed: ${e.message}")
            }
        } finally {
            scope.launch(Dispatchers.Main) { onDone() }
        }
    }
}

/**
 * "AI agent utha le": hand the call to the delegated caller bot. The engine starts the LiveKit
 * Agents worker in the room; the transcript lands in the Node DB, which [AgentCallPanel] reads.
 */
private fun onAgentTakeover(
    current: TakeoverCurrentCall,
    prefs: AppPreferences,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    val room = current.roomName
    val engineUrl = prefs.getTakeoverEngineUrl() ?: return
    val engineToken = prefs.getTakeoverEngineToken()
    scope.launch(Dispatchers.IO) {
        try {
            TakeoverApiClient.agentTakeover(engineUrl, room, engineToken)
            TakeoverState.setPhase(TakeoverPhase.AGENT_CALL)
            TakeoverState.setAgent(
                AgentCallState(
                    callDbId = current.callDbId,
                    startedMs = System.currentTimeMillis(),
                ),
            )
            AppLogger.i(TAG, "agent takeover for room $room")
        } catch (e: TakeoverException) {
            when (e.status) {
                404 -> {
                    AppLogger.i(TAG, "agent-takeover 404: room gone — closing overlay")
                    TakeoverState.setPhase(TakeoverPhase.IDLE)
                    TakeoverState.setCurrent(null)
                }
                409 -> {
                    AppLogger.i(TAG, "agent-takeover 409: already answered elsewhere — closing")
                    TakeoverState.setPhase(TakeoverPhase.IDLE)
                    TakeoverState.setCurrent(null)
                }
                else -> AppLogger.e(TAG, "agent-takeover failed: ${e.message}")
            }
        } finally {
            scope.launch(Dispatchers.Main) { onDone() }
        }
    }
}

/** "Decline": ask the engine to drop the room, then reset. Best-effort: a 404 is already fine. */
private fun onDecline(
    current: TakeoverCurrentCall,
    prefs: AppPreferences,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
) {
    val room = current.roomName
    val engineUrl = prefs.getTakeoverEngineUrl() ?: return
    val engineToken = prefs.getTakeoverEngineToken()
    scope.launch(Dispatchers.IO) {
        try {
            TakeoverApiClient.dropCall(engineUrl, room, engineToken)
        } finally {
            TakeoverState.setPhase(TakeoverPhase.IDLE)
            TakeoverState.setCurrent(null)
            scope.launch(Dispatchers.Main) { onDone() }
            AppLogger.i(TAG, "declined room $room")
        }
    }
}

private const val TAG = "CV:TakeoverOverlay"
