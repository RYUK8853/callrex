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
            ACTION_START -> {
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
            AppLogger.i(TAG, "new ringing room: $room callDbId=${call.callDbId} phone=${call.phoneNumber}")
            onNewCall(TakeoverCurrentCall(
                roomName = call.roomName,
                callDbId = call.callDbId,
                phoneNumber = call.phoneNumber,
                firstSeenMs = System.currentTimeMillis(),
            ))
        }
    }

    private fun onNewCall(call: TakeoverCurrentCall) {
        // If we already have a live call in progress (owner/agent), do not clobber it.
        if (TakeoverState.current.value != null) {
            AppLogger.w(TAG, "new ringing while one is already in flight — ignoring")
            return
        }
        TakeoverState.setPhase(TakeoverPhase.RINGING)
        TakeoverState.setCurrent(call)
        launchOverlay(call.roomName, call.callDbId, call.phoneNumber)
    }

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
