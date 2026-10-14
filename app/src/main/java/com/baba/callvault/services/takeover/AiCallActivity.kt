package com.baba.callvault.services.takeover

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baba.callvault.BuildConfig
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.takeover.TakeoverCallRepository
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.ui.theme.Success
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val AiRed = Color(0xFFD71921)

/**
 * Option A call screen (Callrex = default dialer).
 *
 * Three modes:
 *  1. Incoming call (launched by [AiCallInCallService] with a live [Call]):
 *     stock-look caller number + red Decline + green Answer (the original
 *     phone UI), plus the single floating "AI Assistant" pill. Tapping the
 *     pill calls [Call.deflect] with the configured agent line (Vobiz),
 *     forwarding the live ringing call to the AI agent.
 *  2. In-call / dialing (same live [Call], other states): number + End/Cancel.
 *  3. Dial pad (ACTION_DIAL launch, no live [Call]): minimal dialer required
 *     to hold the system dialer role.
 */
class AiCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CALL_NUMBER = "ai_call_number"
        const val TAG = "AiCallActivity"

        /** HandoffConnectingPanel watchdog cadence. */
        private const val HANDOFF_WATCHDOG_TICK_MS = 5_000L
    }

    private var call: Call? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Incoming-call / in-call screen: surface over the lock screen, keep
        // the screen on for the whole ringback + agent window.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        setContentView(null)
        call = AiCallInCallService.activeRingingCall
        setContent {
            CallVaultTheme {
                AiCallRoot(
                    call = call,
                    initialNumber = intent.getStringExtra(EXTRA_CALL_NUMBER).orEmpty(),
                    isDialIntent = Intent.ACTION_DIAL == intent?.action,
                    dialNumber = intent?.data?.takeIf { it.scheme == "tel" }?.let { it.toString().removePrefix("tel:") }.orEmpty(),
                    onDismiss = { finish() }
                )
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) {
            if (state == Call.STATE_DISCONNECTED) {
                runOnUiThread {
                    // Option A handoff: deflect() drops the LOCAL call while the forwarded
                    // call keeps living on the agent line — the arm (just tapped) or an
                    // active agent state means "stay up and render the handoff UI", not
                    // "the call ended".
                    if (TakeoverState.activeAgentHandoff() != null || TakeoverState.agent.value != null) {
                        AppLogger.i(TAG, "local call disconnected during agent handoff — staying up")
                    } else {
                        finish()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        call?.registerCallback(callCallback)
    }

    override fun onStop() {
        super.onStop()
        call?.unregisterCallback(callCallback)
    }

    override fun onDestroy() {
        call = null
        super.onDestroy()
    }
}

@Composable
private fun AiCallRoot(
    call: Call?,
    initialNumber: String,
    isDialIntent: Boolean,
    dialNumber: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val forwardTarget = prefs.getTakeoverForwardNumber()
    val scope = rememberCoroutineScope()

    // Option A agent handoff state (shared with RingingMonitorService): the arm is set by the
    // "AI Assistant" tap; the agent state is set by the monitor once the forwarded room is
    // found and dispatched. While either is live, the screen renders the handoff UI even
    // though the LOCAL call is already disconnected (deflect ends it by design).
    val handoffArm by TakeoverState.agentHandoffArm.collectAsState()
    val agentHandoff by TakeoverState.agent.collectAsState()

    // Track the live call state for the UI (mirrors the [Call.Callback] that
    // closes the activity on disconnect, in AiCallActivity).
    var callState by remember { mutableIntStateOf(call?.getState() ?: Call.STATE_DISCONNECTED) }
    var number by remember { mutableStateOf(initialNumber.ifBlank { call?.details?.handle?.toString().orEmpty() }) }
    var forwarding by remember { mutableStateOf(false) }

    DisposableEffect(call) {
        val c = call ?: return@DisposableEffect onDispose {}
        val cb = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                callState = state
                number = c.details.handle?.toString().orEmpty().ifBlank { number }
                if (state != Call.STATE_RINGING && state != Call.STATE_NEW) forwarding = false
            }
        }
        c.registerCallback(cb)
        callState = c.getState()
        number = c.details.handle?.toString().orEmpty().ifBlank { number }
        onDispose { c.unregisterCallback(cb) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
            isDialIntent && call == null -> DialPadScreen(
                initialNumber = dialNumber,
                onBack = onDismiss
            )

            // Option A handoff: the owner tapped "AI Assistant" — the local call was
            // deflected to the agent line and the room is on its way to the SIP trunk
            // (arm live, agent state not yet) or the agent is already in the room
            // (agent state live). The local [Call] is already DISCONNECTED at this point,
            // so this branch MUST precede the call-state branches; the fall-through below
            // (no arm, no agent, call disconnected) is the normal "call ended → dismiss".
            handoffArm != null || agentHandoff != null -> {
                if (agentHandoff != null) {
                    AgentHandoffLivePanel(number, scope)
                } else {
                    HandoffConnectingPanel(
                        callNumber = number,
                        onGiveUp = {
                            TakeoverState.clearAgentHandoff()
                            TakeoverState.setAgent(null)
                            onDismiss()
                        }
                    )
                }
            }

            call != null && (callState == Call.STATE_ACTIVE || callState == Call.STATE_HOLDING) -> {
                // In-call (owner answered, or owner re-joined after the agent):
                // standard ongoing-call screen.
                CallIdentity(number, stringResource(R.string.ai_call_in_call))
                Spacer(Modifier.weight(1f))
                CallActionButton(
                    icon = Icons.Filled.Call,
                    rotation = 135f,
                    label = stringResource(R.string.ai_call_end),
                    container = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = { call.disconnect() }
                )
                Spacer(Modifier.height(72.dp))
            }

            call != null && (callState == Call.STATE_DIALING || callState == Call.STATE_CONNECTING) -> {
                CallIdentity(number, stringResource(R.string.ai_call_outgoing))
                Spacer(Modifier.weight(1f))
                CallActionButton(
                    icon = Icons.Filled.Call,
                    rotation = 135f,
                    label = stringResource(R.string.ai_call_cancel),
                    container = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = { call.disconnect() }
                )
                Spacer(Modifier.height(72.dp))
            }

            call != null && (callState == Call.STATE_RINGING || callState == Call.STATE_NEW) -> {
                CallIdentity(number, stringResource(R.string.ai_call_incoming))
                Spacer(Modifier.weight(1f))

                // The single floating Callrex control: "AI Assistant".
                FloatingAiButton(
                    enabled = !forwarding,
                    forwarding = forwarding,
                    onClick = {
                        val target = forwardTarget
                        if (target.isNullOrBlank()) {
                            Toast.makeText(context, R.string.ai_call_no_target, Toast.LENGTH_LONG).show()
                        } else if (!prefs.isTakeoverEnabled() || prefs.getTakeoverEngineUrl().isNullOrBlank()) {
                            // Without the engine configured there is nobody to arm — the forwarded
                            // call would ring the agent line with no agent behind it.
                            Toast.makeText(context, R.string.ai_handoff_no_engine, Toast.LENGTH_LONG).show()
                        } else {
                            forwarding = true
                            runCatching {
                                call.deflect(Uri.fromParts("tel", target, null))
                            }.onSuccess {
                                // Arm the auto agent handoff: when the forwarded call lands on
                                // the SIP trunk (5-15 s via the carrier) the monitor hands it
                                // straight to the agent — no second UI, no waiting on the owner.
                                TakeoverState.armAgentHandoff(target)
                            }.onFailure {
                                forwarding = false
                                if (BuildConfig.DEBUG) {
                                    Toast.makeText(context, "deflect failed: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
                Spacer(Modifier.height(28.dp))

                // Original phone UI: decline (left) + answer (right).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallActionButton(
                        icon = Icons.Filled.Call,
                        rotation = 135f,
                        label = stringResource(R.string.ai_call_decline),
                        container = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        onClick = { call.reject(Call.REJECT_REASON_DECLINED) }
                    )
                    CallActionButton(
                        icon = Icons.Filled.Call,
                        rotation = 0f,
                        label = stringResource(R.string.ai_call_answer),
                        container = Success,
                        contentColor = Color(0xFF08392B),
                        onClick = { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
                    )
                }
                Spacer(Modifier.height(64.dp))
            }

            else -> {
                // No live call and not a dial intent (defensive): just go back.
                LaunchedEffect(Unit) { onDismiss() }
                Spacer(Modifier.fillMaxSize())
            }
            }
        }
    }
}

@Composable
private fun CallIdentity(number: String, label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = number.ifBlank { stringResource(R.string.ai_call_unknown) },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    rotation: Float,
    label: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(container)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(36.dp).rotate(rotation)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor
        )
    }
}

@Composable
private fun FloatingAiButton(
    enabled: Boolean,
    forwarding: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(AiRed)
            .then(
                if (enabled) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
                else Modifier
            )
            .padding(horizontal = 26.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (forwarding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = if (forwarding) stringResource(R.string.ai_call_forwarding) else stringResource(R.string.ai_assistant_button),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

// ------------------------------------------------------------------
// Option A — agent handoff (after the "AI Assistant" tap deflected the call)
// ------------------------------------------------------------------

/**
 * Shown right after the "AI Assistant" tap: the local call was deflected to the agent line and
 * the forwarded call is on its way to the SIP trunk (carrier path, 5-15 s). The monitor owns
 * the actual handoff (it finds the room and posts `/sip/agent-takeover`); this panel is the
 * owner-facing "in progress" state.
 *
 * A watchdog auto-closes when the arm outlives its [AGENT_HANDOFF_ARM_WINDOW_MS] window —
 * that is the "forwarded call never reached the trunk" case (agent line unreachable / carrier
 * not routing), and leaving the screen up forever would look broken.
 */
@Composable
private fun HandoffConnectingPanel(
    callNumber: String,
    onGiveUp: () -> Unit,
) {
    val arm by TakeoverState.agentHandoffArm.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val armNow = arm
    val stale = armNow != null && (nowMs - armNow.armedMs) > AGENT_HANDOFF_ARM_WINDOW_MS

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.SmartToy, contentDescription = null,
                modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        if (stale) {
            Text(stringResource(R.string.ai_handoff_stale),
                style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ai_handoff_stale_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        } else {
            Text(stringResource(R.string.ai_handoff_connecting),
                style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(callNumber.ifBlank { stringResource(R.string.ai_call_unknown) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ai_handoff_connecting_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onGiveUp,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_handoff_cancel), fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The agent is (being) dispatched to the forwarded room: live transcript from the Node DB +
 * an End button that drops the LiveKit room via `/sip/call-drop` when the monitor recorded the
 * room name. Mirrors the legacy overlay's [TakeoverState]/agent view, but scoped to the dialer
 * call screen (Option A) — no second overlay.
 */
@Composable
private fun AgentHandoffLivePanel(
    number: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val repo = remember { TakeoverCallRepository(context.applicationContext) }
    val agent = TakeoverState.agent.value
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
            modifier = Modifier.size(96.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.SmartToy, contentDescription = null,
                modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.ai_handoff_live),
            style = MaterialTheme.typography.headlineSmall)
        Text(
            number.ifBlank { stringResource(R.string.ai_call_unknown) },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            SelectionContainer {
                Text(
                    transcript ?: stringResource(R.string.ai_handoff_transcript_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (transcript == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        // End: ask the engine to drop the room. Only wired when the monitor recorded the room
        // name (the armed-handoff path does); otherwise the room ends on its own and the panel
        // closes when TakeoverState resets (room gone).
        val room = agent?.roomName
        Button(
            onClick = {
                if (room.isNullOrBlank()) return@Button
                val prefs = AppPreferences(context.applicationContext)
                val engineUrl = prefs.getTakeoverEngineUrl() ?: return@Button
                val engineToken = prefs.getTakeoverEngineToken()
                scope.launch(Dispatchers.IO) {
                    try {
                        TakeoverApiClient.dropCall(engineUrl, room, engineToken)
                    } catch (e: TakeoverApiClient.TakeoverException) {
                        // 404 = room already gone — the monitor will reset state; ignore.
                        if (e.status != 404) AppLogger.e(AiCallActivity.TAG, "handoff end: drop failed ${e.status}")
                    } finally {
                        scope.launch(Dispatchers.Main) {
                            TakeoverState.setAgent(null)
                            TakeoverState.clearAgentHandoff()
                        }
                    }
                }
            },
            enabled = !room.isNullOrBlank(),
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Call, contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(135f))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.ai_handoff_end), color = MaterialTheme.colorScheme.onError, fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DialPadScreen(initialNumber: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf(initialNumber) }
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#")
    )

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.ai_dial_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = entry.ifBlank { " " },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )
        Spacer(Modifier.weight(1f))
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                entry = (entry + key).take(20)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(key, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Clear (backspace)
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        entry = entry.dropLast(1)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Call
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(Success)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (entry.isBlank()) return@clickable
                        val telecom = context.getSystemService(TelecomManager::class.java)
                        runCatching {
                            telecom.placeCall(Uri.fromParts("tel", entry, null), null)
                        }.onFailure {
                            Toast.makeText(context, R.string.ai_call_no_target, Toast.LENGTH_SHORT).show()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Call, contentDescription = null, tint = Color(0xFF08392B), modifier = Modifier.size(34.dp))
            }
            Box(Modifier.size(72.dp)) // spacer to keep Call centered
        }
        Spacer(Modifier.height(24.dp))
    }
}
