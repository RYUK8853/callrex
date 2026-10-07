/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Answers the one question Track A turns on: **can the app start a capture track it was handed, itself?**
 *
 * Capture permission is checked when a track is CREATED, not when it is started, and a track created
 * before a call and started at call-connect has been measured delivering real audio (−6.7 dBFS against
 * a −5.6 dBFS control). So if AudioFlinger accepts a `start` from the app's uid on a track the daemon
 * created, the daemon becomes a once-per-boot track factory and leaves the call path entirely — no ADB,
 * no debugging switch, and no 18-second cold start when a call arrives.
 *
 * If it refuses, Track A cannot remove the daemon from the call path and we stop there. Either answer
 * is worth having; neither is worth guessing at.
 *
 * Deliberately a probe rather than a feature: it runs only when invoked explicitly through the
 * provider, does no recording, and holds nothing afterwards.
 */
object TrackAProbe {

    private const val TAG = "CV:TrackAProbe"

    @Volatile private var armed = false
    @Volatile private var handedBinder: IBinder? = null
    @Volatile private var handedCblk: ParcelFileDescriptor? = null
    @Volatile private var handedGeometry: HandoffGeometry? = null

    /** True while a probe is waiting for its handoff, so the receiver routes it here instead of recording. */
    val isArmed: Boolean get() = armed

    /** Called by [HandoffReceiver] when a delivery arrives during a probe. */
    fun onHandoff(binder: IBinder, cblkFd: ParcelFileDescriptor?, frameCount: Int, rate: Int, channels: Int) {
        handedBinder = binder
        handedCblk = cblkFd
        // The library must be loaded before ANY native call. Skipping this took the whole app process
        // down with an UnsatisfiedLinkError inside a binder transaction.
        if (!AudioHandoffNative.ensureLoaded()) {
            AppLogger.w(TAG, "probe: libaudiohandoff.so unavailable in the app")
            return
        }
        handedGeometry = cblkFd?.let {
            HandoffGeometry.of(frameCount, rate, channels, AudioHandoffNative.nativeAshmemSize(it.fd))
        }
        AppLogger.i(TAG, "probe received the handed-over track (geometry=${handedGeometry != null})")
    }

    /**
     * Takes a **stopped** track from the daemon and keeps it, so the daemon can then be killed.
     *
     * Split from [measure] deliberately: the whole claim is that the track outlives its creator, and
     * that can only be shown if the two steps are separated by the daemon's death.
     */
    fun arm(): Bundle {
        val result = Bundle()
        val service = RecorderConnection.service
            ?: return result.apply { putString("result", "NO_DAEMON — it must be alive to create the track") }

        armed = true
        handedBinder = null
        handedCblk = null
        handedGeometry = null
        val delivered = runCatching { service.startHandoffHeld("voice-call", 48_000, 2) }
            .onFailure { AppLogger.w(TAG, "startHandoffHeld threw: ${it.message}") }
            .getOrDefault(false)
        armed = false

        val ok = delivered && handedBinder != null && handedGeometry != null
        result.putBoolean("armed", ok)
        result.putString(
            "result",
            if (ok) "ARMED — track held, stopped. Now kill the daemon by pid, then place a call and measure."
            else "FAILED to arm (delivered=$delivered, binder=${handedBinder != null}, geometry=${handedGeometry != null})",
        )
        return result
    }

    /**
     * Starts the held track **from the app**, drains the ring and reports the level.
     *
     * This is the measurement Track A stands or falls on. Run it with the daemon dead and a call in
     * progress: real audio here means capture no longer depends on the daemon at call time.
     */
    fun measure(context: android.content.Context, seconds: Int): Bundle {
        val result = Bundle()
        val binder = handedBinder
        val cblk = handedCblk
        val geometry = handedGeometry
        if (binder == null || cblk == null || geometry == null) {
            return result.apply { putString("result", "NOT_ARMED — run trackAProbeArm first") }
        }

        // Refuse to measure unless a call is actually up. VOICE_CALL reads digital silence with no
        // call in progress, so a measurement taken outside one looks exactly like a negative result —
        // which is how two runs were wasted before this guard existed.
        if (!isCallActive(context)) {
            return result.apply {
                putString("result", "NO_CALL — refusing to measure; VOICE_CALL is silent with no call up")
            }
        }

        // Validate BEFORE any native mmap — skipping this aborted the whole app process (SIGABRT in
        // the drain thread), which is precisely what this check exists to prevent.
        geometry.validationError()?.let {
            return result.apply { putString("result", "BAD_GEOMETRY — $it") }
        }
        val binderAlive = runCatching { binder.pingBinder() }.getOrDefault(false)
        val daemonAlive = runCatching { RecorderConnection.service?.asBinder()?.pingBinder() == true }.getOrDefault(false)
        if (!HeldRecordControl.start(binder)) {
            return result.apply {
                putString(
                    "result",
                    "START_REFUSED — AudioFlinger rejected the app's start " +
                        "(daemonAlive=$daemonAlive, trackBinderAlive=$binderAlive). See the log for the code.",
                )
            }
        }

        val flag = ByteBuffer.allocateDirect(4)
        val pipe = ParcelFileDescriptor.createPipe()
        // Hand OWNERSHIP of the write end to native: it closes the fd when the drain ends, and fdsan
        // aborts the whole process if a ParcelFileDescriptor still claims to own it.
        val writeFd = pipe[1].detachFd()
        var peak = 0
        var samples = 0L
        val drain = Thread {
            runCatching {
                AudioHandoffNative.nativeDrainToPipe(
                    cblk.fd, geometry.cblkSize, geometry.wrapFrames, geometry.dataOff,
                    geometry.frameSize, HandoffGeometry.GUARD_FRAMES, writeFd, flag, seconds,
                    // A one-shot measurement, not a recording: nothing rebuilds it, so the pipe must
                    // close on exit or the probe's reader would never see EOF.
                    false,
                    null,   // the probe reports its own result; drain counters add nothing here
                )
            }.onFailure { AppLogger.w(TAG, "drain failed: ${it.message}") }
        }.apply { isDaemon = true; start() }

        runCatching {
            FileInputStream(pipe[0].fileDescriptor).use { input ->
                val buf = ByteArray(8192)
                val deadline = System.currentTimeMillis() + seconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    var i = 0
                    while (i + 1 < n) {
                        val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                        i += 2
                    }
                    samples += n / 2
                }
            }
        }.onFailure { AppLogger.w(TAG, "read failed: ${it.message}") }

        flag.putInt(0, 1)
        runCatching { drain.join(2000) }
        runCatching { pipe[0].close() }
        runCatching { HeldRecordControl.stop(binder) }

        val db = if (peak <= 0) -120.0 else 20 * Math.log10(peak / 32768.0)
        val verdict = when {
            samples == 0L -> "NO DATA — the ring produced nothing. Track A does not work this way."
            db < -70 -> "SILENT (%.1f dBFS) — the track runs but carries no audio with the daemon dead.".format(db)
            else -> "POSITIVE (%.1f dBFS) — real audio through a track the app started, daemon alive=%s.".format(db, daemonAlive)
        }
        AppLogger.i(TAG, verdict)
        result.putString("result", verdict)
        result.putBoolean("daemonAlive", daemonAlive)
        result.putLong("samples", samples)
        return result
    }

    /**
     * True only while a carrier call is up.
     *
     * Asks `AudioManager` rather than shelling out to `dumpsys`: the app holds no DUMP permission, so
     * the shell version always answered "no call" and refused every measurement.
     */
    private fun isCallActive(context: android.content.Context): Boolean = runCatching {
        context.getSystemService(android.media.AudioManager::class.java)?.mode ==
            android.media.AudioManager.MODE_IN_CALL
    }.getOrDefault(false)

    /**
     * Asks the daemon for a **stopped** track, then tries to start it from here — the app's process,
     * the app's uid. Returns a bundle the caller can read straight from `adb shell content call`.
     */
    fun run(): Bundle {
        val result = Bundle()
        val service = RecorderConnection.service
        if (service == null) {
            result.putString("result", "NO_DAEMON — the daemon must be connected to create the track")
            return result
        }

        armed = true
        handedBinder = null
        val delivered = runCatching { service.startHandoffHeld("voice-call", 48_000, 2) }
            .onFailure { AppLogger.w(TAG, "startHandoffHeld threw: ${it.message}") }
            .getOrDefault(false)
        armed = false

        val binder = handedBinder
        if (!delivered || binder == null) {
            result.putString("result", "NO_HANDOFF — daemon did not deliver a track (delivered=$delivered)")
            return result
        }

        // The measurement. A refusal here means AudioFlinger validates the CALLING uid rather than the
        // one registered when the track was created — which would end Track A.
        val started = HeldRecordControl.start(binder)
        val verdict = if (started) {
            "POSITIVE — the app started a track the daemon created. Track A can take the daemon off " +
                "the call path; next step is proving audio flows with the daemon killed."
        } else {
            "NEGATIVE — AudioFlinger refused a start from the app's uid, so the daemon is still needed " +
                "at call time and Track A cannot remove it. See the log for the exception."
        }
        AppLogger.i(TAG, verdict)

        // Leave nothing running: this is a permission probe, not a recording.
        runCatching { HeldRecordControl.stop(binder) }
        runCatching { RecorderConnection.service?.stopHandoff() }

        result.putBoolean("started", started)
        result.putString("result", verdict)
        return result
    }
}
