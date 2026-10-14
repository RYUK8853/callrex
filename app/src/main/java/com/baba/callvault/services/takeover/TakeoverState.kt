/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one live call Callrex is currently dealing with, and what state it is in.
 *
 * There is no owner decision screen: every room that rings on the SIP trunk is handed straight
 * to the AI agent by [RingingMonitorService]. So the whole feature is a single
 * [TakeoverCurrentCall] plus the live [AgentCallState] the dialer call screen
 * ([AiCallActivity]) renders (transcript, end button). The authoritative end-of-call signal is
 * the room leaving `/sip/ringing`, not any local clock.
 *
 * State is a [StateFlow] so the monitor service, the call screen and (later) the Agent Calls
 * section all observe the same source of truth without a shared mutable field.
 */

/**
 * The call currently in front of Callrex. [firstSeenMs] is when the monitor first SAW the room
 * in `/sip/ringing`.
 */
data class TakeoverCurrentCall(
    val roomName: String,
    val callDbId: String?,
    val phoneNumber: String?,
    val firstSeenMs: Long,
    /**
     * Engine-side fact at first sight (DYNAMIC-TAKEOVER 10-14): the room was already answered
     * (agent took over) when the monitor first saw it — the monitor then attaches to the live
     * agent view instead of re-dispatching.
     */
    val answered: Boolean = false,
)

/**
 * One line of a live agent transcript, as the Node `/calls/:id?include_transcript=true`
 * response fills in during the call. [speaker] is "user" (the caller) or "agent".
 */
data class AgentTranscriptLine(
    val speaker: String,
    val text: String,
)

/**
 * The live state of the agent call: shown on the dialer call screen ([AiCallActivity]).
 * [transcriptLines] grow as the agent talks; [callDone] flips when the Node record leaves
 * `in_progress` (becomes completed/missed) and the final transcript is available.
 */
data class AgentCallState(
    val callDbId: String?,
    val startedMs: Long,
    val transcriptLines: List<AgentTranscriptLine> = emptyList(),
    val callDone: Boolean = false,
    val finalTranscript: String? = null,
    val finalSummary: String? = null,
    /**
     * The LiveKit room this agent call lives in. Lets the call screen offer "End call" via
     * `/sip/call-drop`.
     */
    val roomName: String? = null,
)

/**
 * Armed when the owner taps "AI Assistant" on the dialer call screen (Option A): the live
 * carrier call is being forwarded to the agent line, and the room that will ring on the SIP
 * trunk is the forwarded call. The [RingingMonitorService] checks this flag to log the origin
 * (forwarded vs. genuine inbound) — the handling is the same: hand it to the agent.
 *
 * [armedMs] bounds how long the arm is honoured: a forwarded call reaches the trunk within
 * seconds (carrier path), so an arm older than the window is stale and the next room is a
 * genuinely new inbound (still agent territory, just not the forwarded one).
 */
data class AgentHandoffArm(
    val targetNumber: String,
    val armedMs: Long,
)

/** How long an [AgentHandoffArm] is honoured before it is treated as stale. */
const val AGENT_HANDOFF_ARM_WINDOW_MS = 90_000L

/**
 * Singleton source of truth for the takeover feature. Owned by the process, observed by the
 * call screen and the Agent Calls section, mutated only by the monitor service and the call
 * screen's own actions (via the monitor, which serialises them).
 */
object TakeoverState {

    private val _current = MutableStateFlow<TakeoverCurrentCall?>(null)
    val current: StateFlow<TakeoverCurrentCall?> = _current.asStateFlow()

    private val _agent = MutableStateFlow<AgentCallState?>(null)
    val agent: StateFlow<AgentCallState?> = _agent.asStateFlow()

    private val _agentHandoffArm = MutableStateFlow<AgentHandoffArm?>(null)
    val agentHandoffArm: StateFlow<AgentHandoffArm?> = _agentHandoffArm.asStateFlow()

    @Synchronized
    fun setCurrent(call: TakeoverCurrentCall?) {
        _current.value = call
        if (call == null) {
            _agent.value = null
        }
    }

    @Synchronized
    fun setAgent(state: AgentCallState?) {
        _agent.value = state
    }

    @Synchronized
    fun armAgentHandoff(targetNumber: String) {
        _agentHandoffArm.value = AgentHandoffArm(targetNumber, System.currentTimeMillis())
    }

    @Synchronized
    fun clearAgentHandoff() {
        _agentHandoffArm.value = null
    }

    /** The arm, if still inside its [AGENT_HANDOFF_ARM_WINDOW_MS] window. */
    @Synchronized
    fun activeAgentHandoff(nowMs: Long = System.currentTimeMillis()): AgentHandoffArm? =
        _agentHandoffArm.value
            ?.takeIf { nowMs - it.armedMs < AGENT_HANDOFF_ARM_WINDOW_MS }

    /**
     * Clear the agent-handoff state (arm + live agent call) after a handoff completes —
     * success, timeout or abort. The current-call slot is managed separately.
     */
    @Synchronized
    fun resetHandoffState() {
        _agentHandoffArm.value = null
        _agent.value = null
    }
}
