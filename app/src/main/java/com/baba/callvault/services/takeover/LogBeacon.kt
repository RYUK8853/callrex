/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import android.content.Context
import android.os.Build
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The log beacon — the way the phone's own diagnostics reach the maintainer WITHOUT anyone
 * being told to export, share, or paste a log file.
 *
 * **Why it exists.** The failure mode this app most needs to see is the silent one: the app
 * dies mid-call (the R3 screen-off class), crashes on a takeover call, or simply stops
 * working — and the only person who knows is the one holding the phone. Every previous
 * incident report required the user to notice, and to hand a log over by hand. That loop is
 * what this closes: the phone ships its own redacted log tail, on three triggers, to the
 * local Node (same LAN, same token the takeover feature already stores).
 *
 * **Triggers.**
 *  1. *Crash* — [AppLogger.onUncaught] fires while the process is still alive: the crash
 *     report is persisted immediately and sent in a short best-effort window; whatever is
 *     left is sent on the next app start (the "survivor" path — the most reliable one,
 *     because the new process has a normal stack, a network, and time).
 *  2. *Takeover call end* — [sendAfterCall] after every owner-pickup write-back, success or
 *     failure. A call that misbehaved is exactly the moment its log is worth having.
 *  3. *Manual* — [sendNow] from the Voice Agent settings section.
 *
 * **Privacy.** The tail is the app's own `app_debug.log`, which is redacted on the way to
 * disk (phone numbers, names — see [AppLogger]). Nothing new leaves the device that the
 * in-app log viewer already shows. The beacon is a no-op unless takeover is enabled with a
 * Node URL configured — no takeover, no beacon, no bytes.
 *
 * **Never on the hot path.** Every send is an independent one-shot HTTP call on its own
 * scope with a hard 15 s timeout, wrapped in runCatching. The beacon failing must never
 * be able to slow down, crash, or alter the app — it is diagnostics, not a dependency.
 */
object LogBeacon {

    private const val TAG = "LogBeacon"

    /** Lines of the log tail per send. 500 ≈ a full call's worth of lines, ≈ 55 KB. */
    private const val TAIL_LINES = 500

    /** Hard cap on a single crash-send attempt. The process may be dying; we do not wait. */
    private const val CRASH_SEND_TIMEOUT_MS = 10_000L

    /** Files a pending (crash-time) report may sit in, and how old is too old to bother with. */
    private const val MAX_PENDING = 5
    private const val PENDING_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context

    private val pendingDir: File get() = File(appContext.filesDir, "log_beacon")

    // -------------------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------------------

    /**
     * Wires the crash hook and ships any report the last (possibly dead) run left behind.
     * Called once from [com.baba.callvault.CallVaultApplication].
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        AppLogger.onUncaught = { _, throwable ->
            // Runs on the dying thread. Persist first (must survive), then attempt the send
            // (may not). Both inside runCatching — a throw here would mask the real crash.
            runCatching {
                persistCrashReport(throwable)
                val url = config()?.nodeUrl
                if (url != null) {
                    // fire-and-forget; the surviving send on next start is the real guarantee
                    scope.launch {
                        runCatching {
                            withTimeoutOrNull(CRASH_SEND_TIMEOUT_MS) {
                                send("crash", "uncaught: ${throwable.javaClass.simpleName}")
                            } ?: AppLogger.w(TAG, "crash-send window closed — will retry on next start")
                        }.onFailure { AppLogger.w(TAG, "crash-send failed: ${it.message}") }
                    }
                }
            }.onFailure { AppLogger.w(TAG, "crash hook failed: ${it.message}") }
        }
        // The survivor path: whatever the dead run could not send leaves now, from a live
        // process with a full stack and no deadline.
        scope.launch { flushPending(reason = "survivor", detail = null) }
    }

    // -------------------------------------------------------------------------------------
    // Triggers
    // -------------------------------------------------------------------------------------

    /**
     * Trigger 2 — after a takeover call's write-back finished (regardless of the result).
     * The call's last lines are in the log by now; this is the most valuable routine send.
     */
    fun sendAfterCall(callId: String, writeBackOk: Boolean) {
        val cfg = config() ?: return
        scope.launch {
            runCatching {
                send("call_end_${if (writeBackOk) "ok" else "failed"}", "call=$callId", nodeUrl = cfg.nodeUrl, token = cfg.nodeToken)
            }.onFailure { AppLogger.w(TAG, "post-call send failed: ${it.message}") }
        }
    }

    /**
     * Trigger 3 — manual, from the Voice Agent settings section.
     * [onResult] receives the human-readable outcome for a UI toast; always called.
     */
    fun sendNow(onResult: (String) -> Unit) {
        // The caller may toast the result — toasts only work on a Looper (main) thread,
        // so always hop back to main before invoking [onResult].
        val postToMain = { msg: String ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post { onResult(msg) }
        }
        val cfg = config()
        if (cfg == null) {
            postToMain("No Node URL set in Voice Agent settings")
            return
        }
        scope.launch {
            val result = runCatching {
                send("manual", "sent from settings", nodeUrl = cfg.nodeUrl, token = cfg.nodeToken)
                "Sent ${TAIL_LINES} log lines to the server"
            }.getOrElse { it.message ?: "Send failed" }
            postToMain(result)
        }
    }

    // -------------------------------------------------------------------------------------
    // Core
    // -------------------------------------------------------------------------------------

    /**
     * Reads the log tail, builds the payload, posts it to `POST {node}/debug/logs`.
     * A single bounded HTTP call. Never throws past the call site's runCatching.
     */
    private suspend fun send(reason: String, detail: String, nodeUrl: String? = null, token: String? = null): Unit {
        val url = nodeUrl ?: config()?.nodeUrl ?: return
        val prefs = AppPreferences(appContext)
        val tail = runCatching {
            AppLogger.readTail(TAIL_LINES)?.takeIf { it.isNotBlank() } ?: "(no log yet)"
        }.getOrDefault("(no log yet)")

        // A pending crash report (from a dead run) rides along when it exists: one request
        // carries both the fresh tail and the crash that happened before.
        val pending = pendingDir.listFiles()?.filter { it.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull()
        val crashTrace = pending?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

        val body = JSONObject()
            .put("device", Build.MODEL)
            .put("model", Build.MANUFACTURER)
            .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("app", "callrex ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .put("reason", reason)
            .put("detail", detail)
            .put("crash", crashTrace ?: JSONObject.NULL)
            .put("log_tail", tail)

        TakeoverApiClient.postDebugLog(url, token ?: prefs.getTakeoverNodeToken(), body)
        // It left the device: drop the copy we carried.
        pending?.delete()
        AppLogger.i(TAG, "beacon: $reason sent (${body.length()} bytes)")
    }

    /**
     * The survivor path: on start, send any crash reports persisted by a previous run that
     * did not make it in its own short window. Oldest first, up to [MAX_PENDING], skipping
     * anything older than a week (stale evidence is worse than none).
     */
    private suspend fun flushPending(reason: String, detail: String?) {
        val cfg = config() ?: return
        val files = pendingDir.listFiles()
            ?.filter { it.name.startsWith("crash_") }
            ?.filter { System.currentTimeMillis() - it.lastModified() < PENDING_MAX_AGE_MS }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (f in files.take(MAX_PENDING)) {
            runCatching {
                val body = JSONObject()
                    .put("device", Build.MODEL)
                    .put("model", Build.MANUFACTURER)
                    .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    .put("app", "callrex ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    .put("reason", "crash")
                    .put("detail", detail ?: "resending report left behind by a previous run")
                    .put("crash", f.readText())
                    .put("log_tail", AppLogger.readTail(TAIL_LINES) ?: "(no log yet)")
                TakeoverApiClient.postDebugLog(cfg.nodeUrl, cfg.nodeToken, body)
                f.delete()
                AppLogger.i(TAG, "beacon: resent pending crash report ${f.name}")
            }.onFailure { AppLogger.w(TAG, "beacon: pending resend failed: ${it.message}") }
        }
    }

    /**
     * Writes the crash report to a pending file IMMEDIATELY (synchronous — we are in the
     * uncaught-exception path and the process is about to die; an async write could be cut
     * off). Bounded to a few KB; a stack trace is small by definition.
     */
    private fun persistCrashReport(throwable: Throwable) {
        runCatching {
            val dir = pendingDir
            dir.mkdirs()
            // Never let a full directory of old reports block a new one.
            dir.listFiles()
                ?.filter { it.name.startsWith("crash_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_PENDING)
                ?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash_${stamp}.txt")
            file.writeText(
                "Crash: ${throwable.javaClass.name}\n" +
                    "Message: ${throwable.message}\n" +
                    "App: callrex ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "Time: ${Date()}\n" +
                    "Thread: ${Thread.currentThread().name}\n" +
                    "-----\n" +
                    AppLogger.redactForReport(android.util.Log.getStackTraceString(throwable)),
            )
            AppLogger.i(TAG, "crash report persisted: ${file.name}")
        }.onFailure { AppLogger.w(TAG, "persistCrashReport failed: ${it.message}") }
    }

    // -------------------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------------------

    private class Cfg(val nodeUrl: String, val nodeToken: String?)

    /**
     * The beacon is a child of the takeover feature, not a standalone setting: same server,
     * same token, same "is this machine set up" question. No takeover config, no beacon —
     * which is also why it can never leak logs to a machine nobody configured.
     */
    private fun config(): Cfg? {
        val p = AppPreferences(appContext)
        if (!p.isTakeoverEnabled()) return null
        val url = p.getTakeoverNodeUrl() ?: return null
        if (url.isBlank()) return null
        return Cfg(url, p.getTakeoverNodeToken())
    }
}
