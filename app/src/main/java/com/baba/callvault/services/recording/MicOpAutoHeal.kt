/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.MicOpHealPolicy.Decision
import com.baba.callvault.system.diagnostics.MicOpReport
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clears a microphone indicator left stuck after a call, by replacing the recorder daemon.
 *
 * When AudioFlinger invalidates a capture mid-call (`CBLK_INVALID`), the recording is rebuilt and comes out whole, but
 * the shell uid's RECORD_AUDIO op from the invalidated capture keeps running: the green dot stays on with nothing
 * recording. rc14 stopped and released that track and Android accepted the stop — the op survived anyway (tester's
 * OnePlus 13, 2026-09-11; see `docs/dev-notes/2026-09-05-stuck-mic-case-file.md`). What always clears it is the daemon
 * process ending, which is what every app update has shown.
 *
 * So after a call ends this looks, and if the op is still running with nothing recording, it ends the daemon and lets
 * the keep-alive bring it straight back — the same path a daemon that dies on its own takes. The rules for when that is
 * safe live in [MicOpHealPolicy]; every step is logged under `CV:MicOpHeal` so a report shows exactly what happened.
 */
object MicOpAutoHeal {

    private const val TAG = "CV:MicOpHeal"

    /** Long enough for the end-of-call teardown to finish and for Android to update the op. */
    private const val SETTLE_MS = 8_000L

    /** How long the daemon gets to exit after being asked to. */
    private const val EXIT_WAIT_MS = 5_000L

    /** How long a relaunch gets before this stops waiting; the keep-alive goes on retrying regardless. */
    private const val RELAUNCH_WAIT_MS = 45_000L

    private const val POLL_MS = 250L

    @Volatile
    private var lastHealAtMs: Long? = null

    /** One check at a time: a carrier and a VoIP recording ending together need one look, not two. */
    private val scheduled = AtomicBoolean(false)

    /** Looks for a stuck indicator shortly after [what] ended, on a background thread. */
    fun scheduleAfterCall(context: Context, what: String) {
        val app = context.applicationContext
        if (!scheduled.compareAndSet(false, true)) {
            AppLogger.d(TAG, "a check is already scheduled; not adding one for $what")
            return
        }
        Thread {
            try {
                Thread.sleep(SETTLE_MS)
                checkAndHeal(app, what)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "check after $what failed: ${t.message}")
            } finally {
                scheduled.set(false)
            }
        }.apply { isDaemon = true; name = "cv-micop-heal" }.start()
    }

    private fun checkAndHeal(context: Context, what: String) {
        val prefs = AppPreferences(context)
        val standalone = prefs.getPrivilegedMode() == PrivilegedMode.STANDALONE
        // Read only where there is a daemon of ours to ask; Shizuku mode is ruled out by the policy anyway.
        val held = if (standalone) shellMicOps() else emptyList()
        if (held == null) {
            AppLogger.i(TAG, "after $what: could not read the microphone app-op; leaving it")
            return
        }

        val decision = MicOpHealPolicy.decide(
            shellMicOpsHeld = held.size,
            callOrRecordingActive = callOrRecordingActive(context),
            standalone = standalone,
            canRelaunch = canRelaunch(context, prefs),
            lastHealAtMs = lastHealAtMs,
            nowMs = SystemClock.elapsedRealtime(),
        )
        AppLogger.i(
            TAG,
            "after $what: ${held.size} shell microphone op(s) running" +
                (if (held.isEmpty()) "" else " (since ${held.joinToString { it.startedAt }})") + " → $decision",
        )
        if (decision != Decision.HEAL) return

        // Asked again at the last moment: a call can begin during the settle, and ending the daemon then would end it.
        if (callOrRecordingActive(context)) {
            AppLogger.i(TAG, "a call started before the heal; leaving the daemon alone")
            return
        }
        heal(held)
    }

    private fun heal(held: List<MicOpReport.HeldOp>) {
        val service = RecorderConnection.service ?: run {
            AppLogger.w(TAG, "the daemon went away before the heal; nothing to replace")
            return
        }
        lastHealAtMs = SystemClock.elapsedRealtime()
        AppLogger.w(
            TAG,
            "microphone indicator stuck with nothing recording (${held.joinToString { it.startedAt }}) — replacing the recorder daemon",
        )

        // destroy() exits the process, so the call may come back as a dead-object error; that is the expected outcome.
        runCatching { service.destroy() }
        if (!waitFor(EXIT_WAIT_MS) { !RecorderConnection.isConnected }) {
            AppLogger.w(TAG, "the daemon did not exit within ${EXIT_WAIT_MS}ms; leaving it")
            return
        }
        // The keep-alive relaunches on the binder's death; this only waits to report the result.
        if (!waitFor(RELAUNCH_WAIT_MS) { RecorderConnection.isConnected }) {
            AppLogger.e(TAG, "the daemon is not back after ${RELAUNCH_WAIT_MS}ms — the keep-alive goes on retrying")
            return
        }

        val after = shellMicOps()
        AppLogger.i(
            TAG,
            when {
                after == null -> "daemon replaced; could not re-read the microphone app-op"
                after.isEmpty() -> "daemon replaced; the microphone indicator is clear"
                else -> "daemon replaced but ${after.size} shell microphone op(s) still running " +
                    "(since ${after.joinToString { it.startedAt }}) — held by something else; not retrying for " +
                    "${MicOpHealPolicy.COOLDOWN_MS / 60_000} min"
            },
        )
    }

    /** The shell uid's running microphone ops, read through the daemon, or null when they could not be read. */
    private fun shellMicOps(): List<MicOpReport.HeldOp>? {
        val service = RecorderConnection.service ?: return null
        val dump = runCatching { service.diagnosticDump("appops_mic", null) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { service.diagnosticDump("appops_all", null) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        return MicOpReport.heldMicOps(dump).filter { it.isShell }
    }

    private fun callOrRecordingActive(context: Context): Boolean {
        // The carrier recording service holds the shared notification for as long as it is in the foreground,
        // including a ringing call it is only offering to record.
        if (SharedStatusNotice.isHeldByRecording) return true
        if (VoipRecordingCoordinator.isRecording) return true
        val mode = runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
        if (mode != null && mode != AudioManager.MODE_NORMAL) return true
        return VoipTelephonyGate.managedCallInProgress(context)
    }

    private fun canRelaunch(context: Context, prefs: AppPreferences): Boolean = MicOpHealPolicy.canRelaunch(
        wdEnabled = AdbShell.isWirelessDebuggingEnabled(context),
        usbDebuggingEnabled = AdbShell.isUsbDebuggingEnabled(context),
        loopbackArmed = AdbShell.isLoopbackArmed(context),
        hasWriteSecureSettings = AdbShell.hasWriteSecureSettings(context),
        offlineEnabled = prefs.isOfflineRecordingEnabled(),
        wifiConnected = isWifiConnected(context),
    )

    private fun isWifiConnected(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }
}
