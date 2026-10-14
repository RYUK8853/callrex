/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import android.content.Context
import com.baba.callvault.utils.AppLogger
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * The owner's LiveKit presence: joins the ringing room over WebRTC when the owner taps
 * "Main baat karta hoon", publishes the mic (the caller hears the owner), auto-plays the
 * caller's audio, and disconnects when the owner ends the call.
 *
 * **One Room, one at a time.** The sheet guarantees one trunk / one room / one call, so this
 * manager holds a single [Room]. A second join attempt while one is live replaces the old one
 * after disconnecting it — that can only happen if the user opened two overlays, which the
 * monitor already guards against.
 *
 * **Audio.** The Room is created with the default two-way-communication audio type: mic input
 * goes to the room (the caller hears us) and subscribed remote audio (the caller) is played
 * through the speaker. Nothing is recorded here — the owner case's recording is Callrex's
 * existing capture running in parallel (the WebRTC call rides the same audio mix a VoIP call
 * does, so the app's own capture sees both sides exactly as it sees WhatsApp/Signal calls).
 *
 * **Blocking connect on purpose.** [join] is called from the overlay's button handler, which
 * already runs off the main thread for the engine call; the WebRTC handshake is short and the
 * UX ("joining…") is worth one blocking wait bounded by the SDK's own timeouts. If the join
 * fails the overlay surfaces the error and falls back to the ringback state — the window is
 * still open.
 */
object LiveKitRoomManager {

    private const val TAG = "CV:LiveKitRoom"

    private var room: Room? = null

    /** True while a Room is connected (state CONNECTED). */
    val isConnected: Boolean
        get() = room?.state == Room.State.CONNECTED

    /**
     * Join [result] from the engine's `/sip/human-join` answer: connect with the issued token
     * and publish the mic. Throws on failure; the caller (overlay) handles it.
     */
    fun join(
        context: Context,
        result: TakeoverApiClient.HumanJoinResult,
    ) {
        disconnect()
        val r = LiveKit.create(context.applicationContext)
        room = r
        AppLogger.i(TAG, "joining room ${result.roomName} at ${result.serverUrl}")
        runBlocking {
            r.connect(result.serverUrl, result.participantToken)
        }
        runBlocking {
            r.localParticipant.setMicrophoneEnabled(true)
        }
        AppLogger.i(TAG, "joined ${result.roomName}, mic on")
    }

    /**
     * Leave the room and release the Room. Idempotent; safe to call from anywhere.
     */
    fun disconnect() {
        val r = room ?: return
        room = null
        runCatching {
            runBlocking {
                r.disconnect()
            }
        }.onFailure {
            AppLogger.w(TAG, "disconnect failed: ${it.message}")
        }
        AppLogger.i(TAG, "disconnected")
    }

    /**
     * Whether the current mic is being published. The overlay's mute button toggles this.
     */
    fun setMicrophoneEnabled(enabled: Boolean) {
        val r = room ?: return
        runBlocking {
            r.localParticipant.setMicrophoneEnabled(enabled)
        }
    }

    val microphoneEnabled: Boolean
        get() = room?.localParticipant?.isMicrophoneEnabled == true
}
