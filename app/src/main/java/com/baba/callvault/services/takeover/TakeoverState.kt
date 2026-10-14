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
 * The sheet's constraint is "1 call at a time" (one trunk, one room), so a single
 * [TakeoverPhase] plus a single [TakeoverCurrentCall] models the whole feature. The
 * multi-call edge case the sheet mentions ("if two entries ever appear, stack the overlays —
 * design later") is deliberately NOT built: the extra entries are logged and the FIRST one
 * wins, which is the safe behaviour when the design has not been made yet.
 *
 * State is a [StateFlow] so the overlay, the monitor service and (later) the Agent Calls
 * section all observe the same source of truth without a shared mutable field.
 */

/** What Callrex is doing about the current call, if anything. */
enum class TakeoverPhase {
    /** No ringing call. The monitor polls; the overlay is closed. */
    IDLE,

    /** A room is ringing. The overlay is up and the owner must decide within the window. */
    RINGING,

    /** The owner tapped "Main baat karta hoon"; the app is joining the room / in the call. */
    OWNER_CALL,

    /** The owner tapped "AI agent utha le"; the agent is on the call, transcript lands via Node. */
    AGENT_CALL,
}

/** The ringback window the overlay counts down. Trunk default; read once per call. */
internal const val RINGBACK_WINDOW_SECONDS = 45L

/**
 * The call currently in front of Callrex. [firstSeenMs] is when the monitor first SAW the room
 * in `/sip/ringing` — the timer counts down from that, which is always a few seconds into the
 * real window (the engine itself ticks every 2 s), so the bar is a slightly pessimistic
 * display of the true deadline, never an optimistic one.
 */
data class TakeoverCurrentCall(
    val roomName: String,
    val callDbId: String?,
    val phoneNumber: String?,
    val firstSeenMs: Long,
) {
    /**
     * How many milliseconds of the ringback window are (pessimistically) left. The overlay
     * treats <= 0 as "the window is gone, the call will drop" — but the AUTHORITATIVE end is
     * the room disappearing from `/sip/ringing`, not this clock: the server can extend or
     * truncate the window and the poll is the truth.
     */
    fun secondsLeftNow(nowMs: Long = System.currentTimeMillis()): Long =
        RINGBACK_WINDOW_SECONDS - (nowMs - firstSeenMs) / 1000L
}

/**
 * One line of a live agent transcript, as the Node `/calls/:id?include_transcript=true`
 * response fills in during the call. [speaker] is "user" (the caller) or "agent".
 */
data class AgentTranscriptLine(
    val speaker: String,
    val text: String,
)

/**
 * The live state of the agent branch: shown in the overlay's "AI baat kar raha hai" panel.
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
)

/**
 * Singleton source of truth for the takeover feature. Owned by the process, observed by the
 * overlay and the Agent Calls section, mutated only by the monitor service and the overlay's
 * own button actions (via the monitor, which serialises them).
 */
object TakeoverState {

    private val _phase = MutableStateFlow(TakeoverPhase.IDLE)
    val phase: StateFlow<TakeoverPhase> = _phase.asStateFlow()

    private val _current = MutableStateFlow<TakeoverCurrentCall?>(null)
    val current: StateFlow<TakeoverCurrentCall?> = _current.asStateFlow()

    private val _agent = MutableStateFlow<AgentCallState?>(null)
    val agent: StateFlow<AgentCallState?> = _agent.asStateFlow()

    /**
     * Whether the overlay should be on screen RIGHT NOW. The monitor sets it when a call
     * appears (IDLE/RINGING -> overlay up) and the overlay itself confirms by reading it.
     */
    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    @Synchronized
    fun setPhase(p: TakeoverPhase) {
        _phase.value = p
    }

    @Synchronized
    fun setCurrent(call: TakeoverCurrentCall?) {
        _current.value = call
        if (call == null) {
            _agent.value = null
            _overlayVisible.value = false
        }
    }

    @Synchronized
    fun setAgent(state: AgentCallState?) {
        _agent.value = state
    }

    fun setOverlayVisible(visible: Boolean) {
        _overlayVisible.value = visible
    }

    /**
     * The owner-pickup write-back context: after the owner's call ends, this is what lets
     * the (later) write-back job find the right Node record to update with the local
     * transcript. Null when there is no owner call in flight.
     */
    private val _ownerWriteback = MutableStateFlow<OwnerWriteback?>(null)
    val ownerWriteback: StateFlow<OwnerWriteback?> = _ownerWriteback.asStateFlow()

    @Synchronized
    fun setOwnerWriteback(w: OwnerWriteback?) {
        _ownerWriteback.value = w
    }

    /**
     * Everything the owner-case write-back needs to land the local transcript + summary into
     * the Node DB for this call, once the local transcription of the WebRTC recording is done.
     * [recordingStartedMs] is the app-side start time, used to match the local recording file
     * that was produced for this call.
     */
    data class OwnerWriteback(
        val callDbId: String,
        val phoneNumber: String?,
        val recordingStartedMs: Long,
        val engineUrl: String,
        val nodeUrl: String,
        val engineToken: String?,
        val nodeToken: String?,
    )
}
