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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.baba.callvault.BuildConfig
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.ui.theme.Success

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
                runOnUiThread { finish() }
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
                        } else {
                            forwarding = true
                            runCatching {
                                call.deflect(Uri.fromParts("tel", target, null))
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
