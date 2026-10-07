/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.os.IBinder
import android.os.Parcel
import com.baba.callvault.utils.AppLogger

/**
 * Starts and stops a capture track the app was handed, **from the app**, with no daemon involved.
 *
 * This is the hinge of Track A. Capture permission is checked when the track is CREATED, not when it is
 * started — so if a privileged daemon creates the track once and hands it over, the app may be able to
 * run it per call by itself. That would take the daemon off the call path entirely, and with it the
 * 18-second cold start that loses short calls when the daemon has died.
 *
 * The handed-over object is an `IAudioRecord` binder, already surfaced as a Java `IBinder` by
 * [AudioHandoffNative.nativeExtractBinder]. Its AIDL is a plain two-argument `start` and a no-argument
 * `stop`, so no native code is needed here — a `transact` with the right descriptor and transaction
 * codes is the whole thing.
 *
 * **Whether AudioFlinger accepts it is the open question.** `start` may validate the *calling* uid (the
 * app, unprivileged) rather than the uid registered when the track was created (shell). If it rejects
 * us, Track A cannot remove the daemon from the call path and this class is the proof; if it accepts,
 * Track A is real. Either way the answer is a measurement, not an argument — see
 * `docs/dev-notes/transport-and-daemon-architecture.md`.
 */
object HeldRecordControl {

    private const val TAG = "CV:HeldRecord"

    /** `IAudioRecord`'s AIDL descriptor; the token every transaction must carry. */
    private const val DESCRIPTOR = "android.media.IAudioRecord"

    /** AIDL transaction codes, assigned in declaration order from `FIRST_CALL_TRANSACTION`. */
    private const val TX_START = IBinder.FIRST_CALL_TRANSACTION       // start(event, triggerSession)
    private const val TX_STOP = IBinder.FIRST_CALL_TRANSACTION + 1    // stop()

    /** `AudioSystem::SYNC_EVENT_NONE` — start immediately rather than waiting on another session. */
    private const val SYNC_EVENT_NONE = 0
    private const val NO_TRIGGER_SESSION = 0

    /**
     * Starts the handed-over track. Returns false if the binder is dead or AudioFlinger refuses us —
     * refusal is a real answer, not an error to paper over, so it is logged with its exception.
     */
    fun start(binder: IBinder): Boolean = transact(binder, TX_START, "start") { data ->
        data.writeInt(SYNC_EVENT_NONE)
        data.writeInt(NO_TRIGGER_SESSION)
    }

    /** Stops the handed-over track. Safe to call when it was never started. */
    fun stop(binder: IBinder): Boolean = transact(binder, TX_STOP, "stop") {}

    private inline fun transact(
        binder: IBinder,
        code: Int,
        name: String,
        writeArgs: (Parcel) -> Unit,
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            writeArgs(data)
            val delivered = binder.transact(code, data, reply, 0)
            // The C++ AIDL backend replies with a Status: 0 for OK, otherwise an exception code and
            // message that Java can read directly. A rejection surfaces here rather than silently
            // returning a track that never produces audio.
            reply.readException()
            AppLogger.i(TAG, "IAudioRecord.$name accepted from the app (delivered=$delivered)")
            delivered
        } catch (t: Throwable) {
            // The error CODE is what distinguishes "you may not do this" from "this track is gone", so
            // record it rather than just the class name.
            // The error CODE distinguishes "you may not do this" from "this track is gone". Read it
            // reflectively: ServiceSpecificException is not on this compile SDK's surface.
            val errorCode = runCatching {
                t.javaClass.getField("errorCode").getInt(t).toString()
            }.getOrDefault("?")
            AppLogger.w(TAG, "IAudioRecord.$name REFUSED: ${t.javaClass.simpleName} errorCode=$errorCode msg=${t.message}")
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
