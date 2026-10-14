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
 * voice-agent engine's `GET /sip/ringing` every 2 s and, when a room appears, hands it STRAIGHT
 * to the AI agent (`POST /sip/agent-takeover`) — the owner never sees a decision screen. The
 * agent call screen ([AiCallActivity], the default-dialer UI) renders the handoff + live
 * transcript off [TakeoverState].
 *
 * **Why a foreground service and not a Worker.** WorkManager jobs are deferred by the minute and
 * re-scheduled on throttling — the ringback window is ~45 s and the handoff is time-critical. A
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
 * the list — that is what ends the agent call, not the local clock (the server owns the
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        startPolling()
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
     * Diff this poll's room set against the last one: a new room triggers the agent handoff; a
     * room that disappeared ends it. Multi-room entries are logged (one trunk should give one)
     * and the first unseen room wins.
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

        // New room: hand the first unseen one to the AI agent.
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

    /**
     * A room is ringing on the SIP trunk: hand it STRAIGHT to the AI agent. There is no owner
     * decision screen anymore (the user's call UI is the stock dialer with the single floating
     * "AI Assistant" button) — every room that rings is agent territory, whether it arrived as a
     * forwarded call (the owner just tapped the button; an [TakeoverState.activeAgentHandoff] is
     * set) or as a genuine inbound (the owner isn't looking at this phone right now, which is
     * exactly when the agent should take over).
     *
     * Both cases run the same retry loop: the forwarded room lands on the trunk a few seconds
     * after the deflect (carrier path), so the very first `agent-takeover` often 404s — retry
     * every 2 s until it lands or the window closes.
     */
    private fun onNewCall(call: TakeoverCurrentCall) {
        // If we already have a live call in progress (agent), do not clobber it.
        if (TakeoverState.current.value != null) {
            AppLogger.w(TAG, "new ringing while one is already in flight — ignoring")
            return
        }

        val arm = TakeoverState.activeAgentHandoff()
        if (arm != null) {
            AppLogger.i(TAG, "armed agent handoff (target ${arm.targetNumber}) — auto takeover for room ${call.roomName}")
        } else if (call.answered) {
            // Already answered when we first saw it (app restarted mid-call, or the agent
            // connected between polls) — attach to the live agent view, no dispatch needed.
            AppLogger.i(TAG, "room already answered on first sight: ${call.roomName} — attaching to agent view")
        } else {
            AppLogger.i(TAG, "new inbound room ${call.roomName} — handing straight to the agent")
        }

        TakeoverState.setAgent(
            AgentCallState(
                callDbId = call.callDbId,
                startedMs = System.currentTimeMillis(),
                roomName = call.roomName,
            ),
        )
        TakeoverState.setCurrent(call)
        scope.launch(Dispatchers.IO) {
            agentTakeover(call, arm != null)
        }
    }

    /**
     * Dispatch the agent with retry. Terminal outcomes:
     *  - 200: agent dispatched. The room STAYS in `/sip/ringing` as answered=true (engine 10-14
     *    contract) until the LiveKit room itself goes away, so the call screen keeps its live
     *    transcript and ends when [onRoomGone] fires.
     *  - 404 after the deadline: the call never arrived (agent line unreachable / carrier not
     *    routing) — reset state so the call screen shows the failed end.
     *  - 409: something else answered first (a race, or the owner already on the line) — keep
     *    the agent view, the transcript is still the right thing to show.
     */
    private suspend fun agentTakeover(call: TakeoverCurrentCall, armed: Boolean) {
        val prefs = AppPreferences(applicationContext)
        val engineUrl = prefs.getTakeoverEngineUrl()
        val engineToken = prefs.getTakeoverEngineToken()
        if (engineUrl.isNullOrBlank()) {
            AppLogger.e(TAG, "auto takeover: no engine URL — cannot dispatch agent")
            TakeoverState.resetHandoffState()
            return
        }
        val deadlineMs = System.currentTimeMillis() + AUTO_TAKEOVER_WINDOW_MS
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                TakeoverApiClient.agentTakeover(engineUrl, call.roomName, engineToken)
                AppLogger.i(TAG, "agent takeover OK for room ${call.roomName}")
                TakeoverState.clearAgentHandoff()
                return
            } catch (e: TakeoverApiClient.TakeoverException) {
                when (e.status) {
                    404 -> {
                        // Room still not on the trunk — keep retrying until the deadline.
                        AppLogger.w(TAG, "agent takeover: room ${call.roomName} not on trunk yet (404), retrying")
                    }
                    409 -> {
                        AppLogger.i(TAG, "agent takeover: already answered (409) — keeping agent view")
                        TakeoverState.clearAgentHandoff()
                        return
                    }
                    else -> {
                        // 5xx / 0 (network): transient — retry within the window.
                        AppLogger.w(TAG, "agent takeover: engine error ${e.status} (${e.message}), retrying")
                    }
                }
            }
            delay(TAKEOVER_RETRY_MS)
        }
        val why = if (armed) "window closed without agent answer" else "inbound never reached the trunk"
        AppLogger.e(TAG, "agent takeover: $why for room ${call.roomName}")
        TakeoverState.clearAgentHandoff()
        resetCallState()
    }

    /** Reset the takeover state so the call screen transitions to its terminal (failed) view. */
    private fun resetCallState() {
        TakeoverState.setCurrent(null)
        TakeoverState.setAgent(null)
    }

    private fun onRoomGone() {
        // The authoritative end of the call: the room left the ringing list. Whatever state we
        // were in, the call screen (if showing the agent view) reads TakeoverState and closes;
        // the monitor resets the state.
        val was = TakeoverState.current.value
        if (was == null) return
        AppLogger.i(TAG, "resetting takeover state (room gone), was=${was.roomName}")
        TakeoverState.setCurrent(null)
        TakeoverState.setAgent(null)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        // Leave state clean if the service is torn down mid-call (e.g. the user turned the
        // feature off). The call screen, if any, will see the reset on its next tick.
        TakeoverState.setCurrent(null)
        TakeoverState.setAgent(null)
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
    }
}
