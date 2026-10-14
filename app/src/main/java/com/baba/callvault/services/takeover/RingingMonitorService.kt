/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The ringing monitor: while dynamic takeover is enabled, this foreground service polls the
 * voice-agent engine's `GET /sip/ringing` every 2 s and, when a room it has not seen appears,
 * raises the [TakeoverOverlayActivity] so the owner can answer (owner / agent / decline) inside
 * the ringback window.
 *
 * **Why a foreground service and not a Worker.** WorkManager jobs are deferred by the minute and
 * re-scheduled on throttling — the ringback window is ~45 s and the decision is time-critical. A
 * persistent FGS gives a tight, predictable 2 s cadence. The cost (a notification) is the
 * feature being on at all: it only runs when the user has explicitly turned takeover on AND
 * pointed us at a reachable engine.
 *
 * **Back-off when the engine is unreachable.** A 2 s poll against a dead host would spin the
 * network stack. On a "server down" answer (status 0 / connection error) the poll backs off to a
 * longer interval and returns to 2 s the first time the engine answers. A 200 with an empty list
 * is a HEALTHY answer and keeps the fast cadence.
 *
 * **The authoritative signal is the room set, not a timer.** A call is "new" when its room name
 * is in this poll's `ringing` list and was not in the previous one. It is "gone" when it leaves
 * the list — that is what dismisses the overlay, not the local 45 s clock (the server owns the
 * deadline and can extend or truncate it).
 */
class RingingMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val seenRooms = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AppLogger.i(TAG, "RingingMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LOCAL_RINGING -> {
                // The overlay itself is launched DIRECTLY by [raiseLocalRinging] (before this
                // intent arrives), so the ringback decision does not depend on the FGS start
                // succeeding. This branch only satisfies the startForeground() contract and keeps
                // the process warm with the persistent notification.
                startAsForeground()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                startPolling()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.takeover_monitor_title))
            .setContentText(getString(R.string.takeover_monitor_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.takeover_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.takeover_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var backoffMs = POLL_FAST_MS
            while (isActive) {
                val prefs = AppPreferences(this@RingingMonitorService)
                // The feature can be switched off at any time; stop without leaving the host spinning.
                if (!prefs.isTakeoverEnabled()) break
                val engineUrl = prefs.getTakeoverEngineUrl()
                val engineToken = prefs.getTakeoverEngineToken()
                if (engineUrl.isNullOrBlank()) break

                val result = runCatching { TakeoverApiClient.getRinging(engineUrl, engineToken) }
                when (val value = result.getOrNull()) {
                    null -> {
                        // Server down / timeout / network. Back off; the host may be on another
                        // network or simply off. Never treat this as "no call" — that would
                        // dismiss a live overlay.
                        val e = result.exceptionOrNull()
                        AppLogger.w(TAG, "ringing poll unreachable: ${e?.message}")
                        backoffMs = POLL_SLOW_MS
                    }
                    else -> {
                        backoffMs = POLL_FAST_MS
                        reconcile(value, prefs)
                    }
                }
                delay(backoffMs)
            }
        }
    }

    /**
     * Diff this poll's room set against the last one: a new room raises the overlay; a room that
     * disappeared dismisses it. Multi-room entries are logged (the sheet's "stack later" case) and
     * the first unseen room wins.
     */
    private fun reconcile(ringing: List<TakeoverApiClient.RingingCall>, prefs: AppPreferences) {
        if (ringing.size > 1) {
            AppLogger.w(TAG, "Multiple ringing entries (${ringing.size}) — one trunk should give one; handling the first only")
        }
        val roomNames = ringing.map { it.roomName }.toSet()
        val newRooms = roomNames - seenRooms
        val goneRooms = seenRooms - roomNames

        // Gone first: a room that left the list means its window closed / it was answered elsewhere.
        goneRooms.forEach { gone ->
            AppLogger.i(TAG, "room gone: $gone")
            onRoomGone()
        }
        seenRooms.clear()
        seenRooms += roomNames

        // New room: raise the overlay for the first one we have not already surfaced.
        newRooms.forEach { room ->
            val call = ringing.first { it.roomName == room }
            AppLogger.i(TAG, "new ringing room: $room callDbId=${call.callDbId} phone=${call.phoneNumber} answered=${call.answered}")
            onNewCall(TakeoverCurrentCall(
                roomName = call.roomName,
                callDbId = call.callDbId,
                phoneNumber = call.phoneNumber,
                firstSeenMs = System.currentTimeMillis(),
                answered = call.answered,
            ))
        }
    }

    private fun onNewCall(call: TakeoverCurrentCall) {
        // If we already have a live call in progress (owner/agent), do not clobber it.
        if (TakeoverState.current.value != null) {
            AppLogger.w(TAG, "new ringing while one is already in flight — ignoring")
            return
        }

        // Option A (dialer mode): the owner just tapped "AI Assistant" on the dialer call
        // screen — the live carrier call is being forwarded to the agent line and THIS room
        // is the forwarded call landing on the SIP trunk. Hand it straight to the agent:
        // no legacy 3-button overlay (that would put a second call UI on a call the owner
        // already decided to hand over). The dialer call screen renders the handoff + live
        // transcript off TakeoverState; it must not be clobbered by the overlay.
        val arm = TakeoverState.activeAgentHandoff()
        if (arm != null) {
            AppLogger.i(TAG, "armed agent handoff (target ${arm.targetNumber}) — auto takeover for room ${call.roomName}, no overlay")
            TakeoverState.setPhase(TakeoverPhase.AGENT_CALL)
            TakeoverState.setCurrent(call)
            TakeoverState.setAgent(
                AgentCallState(
                    callDbId = call.callDbId,
                    startedMs = System.currentTimeMillis(),
                    roomName = call.roomName,
                ),
            )
            scope.launch(Dispatchers.IO) {
                autoAgentTakeover(call, ArmOutcome(arm))
            }
            return
        }

        // The room already shows as answered (e.g. the app/monitor restarted mid-call, or the
        // agent connected between two polls). Don't offer stale decision buttons: go straight
        // to the agent view with its transcript.
        if (call.answered) {
            AppLogger.i(TAG, "room already answered on first sight: ${call.roomName} — straight to agent view")
            TakeoverState.setPhase(TakeoverPhase.AGENT_CALL)
            TakeoverState.setCurrent(call)
            TakeoverState.setAgent(
                AgentCallState(
                    callDbId = call.callDbId,
                    startedMs = System.currentTimeMillis(),
                ),
            )
            launchOverlay(call.roomName, call.callDbId, call.phoneNumber)
            return
        }

        TakeoverState.setPhase(TakeoverPhase.RINGING)
        TakeoverState.setCurrent(call)
        launchOverlay(call.roomName, call.callDbId, call.phoneNumber)
    }

    /**
     * Armed-handoff takeover with retry: the forwarded call reaches the SIP trunk a few
     * seconds AFTER the app taps deflect (carrier path), so the very first `/sip/agent-takeover`
     * often 404s (room not there yet). Retry every 2 s until it lands or the window closes.
     *
     * Terminal outcomes:
     *  - 200: agent dispatched. The room STAYS in `/sip/ringing` as answered=true (engine
     *    10-14 contract) until the LiveKit room itself goes away, so the handoff UI keeps its
     *    live transcript and ends when [onRoomGone] fires.
     *  - 404 after the deadline: the forwarded call never arrived (agent line unreachable /
     *    carrier not routing) — reset state so the handoff UI shows the failed end.
     *  - 409: something else answered first (owner beat the forward, or a race) — keep the
     *    agent view, the transcript is still the right thing to show.
     */
    private suspend fun autoAgentTakeover(call: TakeoverCurrentCall, arm: ArmOutcome) {
        val prefs = AppPreferences(applicationContext)
        val engineUrl = prefs.getTakeoverEngineUrl()
        val engineToken = prefs.getTakeoverEngineToken()
        if (engineUrl.isNullOrBlank()) {
            AppLogger.e(TAG, "auto takeover: no engine URL — cannot dispatch agent")
            resetHandoffState()
            return
        }
        val deadlineMs = System.currentTimeMillis() + AUTO_TAKEOVER_WINDOW_MS
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                TakeoverApiClient.agentTakeover(engineUrl, call.roomName, engineToken)
                AppLogger.i(TAG, "auto agent takeover OK for room ${call.roomName}")
                TakeoverState.clearAgentHandoff()
                return
            } catch (e: TakeoverApiClient.TakeoverException) {
                when (e.status) {
                    404 -> {
                        // Room still not on the trunk — keep retrying until the deadline.
                        AppLogger.w(TAG, "auto takeover: room ${call.roomName} not on trunk yet (404), retrying")
                    }
                    409 -> {
                        AppLogger.i(TAG, "auto takeover: already answered (409) — keeping agent view")
                        TakeoverState.clearAgentHandoff()
                        return
                    }
                    else -> {
                        // 5xx / 0 (network): transient — retry within the window.
                        AppLogger.w(TAG, "auto takeover: engine error ${e.status} (${e.message}), retrying")
                    }
                }
            }
            delay(TAKEOVER_RETRY_MS)
        }
        AppLogger.e(TAG, "auto takeover: window closed without agent answer for room ${call.roomName}")
        TakeoverState.clearAgentHandoff()
        resetHandoffState()
    }

    /** Reset the takeover state so the handoff UI transitions to its terminal (failed) view. */
    private fun resetHandoffState() {
        TakeoverState.setCurrent(null)
        TakeoverState.setPhase(TakeoverPhase.IDLE)
    }

    /** Small holder so the retry loop can log the arm that caused the takeover. */
    private data class ArmOutcome(val arm: AgentHandoffArm)

    private fun onRoomGone() {
        // The authoritative end-of-window. Whatever phase we were in, the room is gone.
        // The overlay (if up) reads the phase/current and closes; the monitor resets state.
        val was = TakeoverState.current.value
        if (was == null) return
        AppLogger.i(TAG, "resetting takeover state (room gone), was=${was.roomName}")
        TakeoverState.setCurrent(null)
        TakeoverState.setPhase(TakeoverPhase.IDLE)
        dismissOverlay()
    }

    private fun launchOverlay(room: String, callDbId: String?, phone: String?) {
        val intent = Intent(this, TakeoverOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(TakeoverOverlayActivity.EXTRA_ROOM, room)
            putExtra(TakeoverOverlayActivity.EXTRA_CALL_DB_ID, callDbId)
            putExtra(TakeoverOverlayActivity.EXTRA_PHONE, phone)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            AppLogger.e(TAG, "could not launch overlay: ${it.message}")
        }
    }

    private fun dismissOverlay() {
        // The overlay observes TakeoverState and closes itself; nothing to send.
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        // Leave state clean if the service is torn down mid-call (e.g. the user turned the
        // feature off). The live overlay, if any, will see the phase reset on its next tick.
        TakeoverState.setCurrent(null)
        TakeoverState.setPhase(TakeoverPhase.IDLE)
        super.onDestroy()
        AppLogger.i(TAG, "RingingMonitorService destroyed")
    }

    companion object {
        private const val TAG = "CV:RingingMonitor"
        private const val CHANNEL_ID = "takeover_monitor"
        private const val NOTIFICATION_ID = 5150

        /** The sheet's cadence. */
        private const val POLL_FAST_MS = 2_000L
        /** Unreachable-engine back-off: still responsive, not a hot loop. */
        private const val POLL_SLOW_MS = 10_000L

        const val ACTION_START = "com.baba.callvault.TAKEOVER_MONITOR_START"
        const val ACTION_STOP = "com.baba.callvault.TAKEOVER_MONITOR_STOP"
        /** Raised by [NormalCallTrigger] for a normal (non-SIP) incoming call on the carrier. */
        const val ACTION_LOCAL_RINGING = "com.baba.callvault.TAKEOVER_LOCAL_RINGING"
        /** Extra carrying the caller number for [ACTION_LOCAL_RINGING]. */
        const val EXTRA_LOCAL_NUMBER = "takeover_local_number"

        /**
         * How long the armed handoff keeps retrying `/sip/agent-takeover` after the forwarded
         * room appears. The carrier path (deflect → Vobiz → SIP trunk → LiveKit room) normally
         * lands within 5-15 s; 44 s covers slow routing without holding the state forever.
         */
        private const val AUTO_TAKEOVER_WINDOW_MS = 44_000L
        /** Retry cadence for the armed-handoff takeover (matches the poll cadence). */
        private const val TAKEOVER_RETRY_MS = 2_000L

        fun start(context: Context) {
            val i = Intent(context, RingingMonitorService::class.java).apply { action = ACTION_START }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, RingingMonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }

        /**
         * Start the monitor if takeover is enabled and configured; stop it otherwise. Called from
         * the settings toggle and on app start so the feature is on exactly when the user wants.
         */
        fun syncWithPrefs(context: Context) {
            val prefs = AppPreferences(context)
            val shouldRun = prefs.isTakeoverEnabled() && !prefs.getTakeoverEngineUrl().isNullOrBlank()
            if (shouldRun) {
                start(context)
            } else {
                stop(context)
            }
        }

        /**
         * Bring the monitor foreground (so it may launch the overlay) and raise it for a
         * **normal** incoming call on the carrier. Called by [NormalCallTrigger]. Unlike
         * [start] this does not require an engine URL — the local path needs no backend to be
         * armed, only that takeover is enabled.
         */
        fun raiseLocalRinging(context: Context, number: String?) {
            val i = Intent(context, RingingMonitorService::class.java).apply {
                action = ACTION_LOCAL_RINGING
                putExtra(EXTRA_LOCAL_NUMBER, number)
            }
            runCatching {
                context.startForegroundService(i)
            }.onFailure {
                // Background FGS start is blocked when the app has no BAL exemption yet
                // (SYSTEM_ALERT_WINDOW not granted). The overlay simply won't raise; the log
                // tells the field test why. Never crash the phone-state path.
                AppLogger.w(TAG, "raiseLocalRinging startForegroundService blocked: ${it.message}")
            }
        }
    }
}
