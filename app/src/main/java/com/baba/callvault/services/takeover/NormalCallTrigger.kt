/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.takeover

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.WindowManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger

/**
 * Triggers the takeover overlay for **normal** (carrier, non-SIP) incoming calls.
 *
 * The SIP path ([RingingMonitorService] engine polling) only raises the overlay for calls
 * arriving through the LiveKit SIP trunk. This object covers the other half of "when someone
 * calls me": a call landing on the phone's own number straight through the carrier. It is fed
 * from [com.baba.callvault.services.call.CallSessionManager.handlePhoneState], the single choke
 * point every phone-state change flows through (broadcast receiver + post-boot live listener).
 *
 * **Who launches what.** The overlay activity is launched DIRECTLY from here, with
 * `showWhenLocked` + `turnScreenOn`: while the call rings, the dialer is the foreground app, so
 * this is a background activity launch, and the exemption that makes it work on Android 10-16 is
 * the `SYSTEM_ALERT_WINDOW` grant (asked for once in Settings, see [overlayPermissionGranted]).
 * The [RingingMonitorService] is started on top only as best-effort process warmth (persistent
 * notification) — if that start is blocked, the overlay still comes up.
 *
 * **Number arrives late.** Android sends two RINGING broadcasts (see [PhoneStateReceiver](
 * com.baba.callvault.services.call.PhoneStateReceiver) KDoc): the first with a null number, the
 * second (READ_CALL_LOG granted) carrying it. We raise the overlay immediately — unknown number
 * until the second broadcast refreshes [TakeoverState.current].
 *
 * **Deliberately minimal.** The overlay is the deliverable; the "hand to agent" and "reject the
 * carrier call" actions are provider-dependent mechanisms to be wired when the owner chooses
 * them. For now both local buttons just close the overlay, leaving the call with the dialer.
 */
object NormalCallTrigger {

    private const val TAG = "CV:NormalCallTrigger"

    /**
     * Synthetic room name for local (non-SIP) calls. The overlay and [TakeoverState] key off the
     * room name to tell the two paths apart; a local call is not a real engine room, so it uses
     * this constant instead of a LiveKit room id. Stable across the double-broadcast pair.
     */
    const val ROOM_LOCAL = "local_normal_incall"

    /** True when [roomName] names a local (normal-call) takeover rather than a SIP room. */
    fun isLocalRoom(roomName: String?): Boolean = roomName == ROOM_LOCAL

    /** Whether the user has granted the "display over other apps" permission the overlay needs. */
    fun overlayPermissionGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(context)
        else
            true

    /** Open the system "display over other apps" screen for this app (one-time grant). */
    fun openOverlayPermissionScreen(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogger.w(TAG, "cannot open overlay permission screen: ${it.message}") }
    }

    /**
     * Called on every phone-state change (main thread; kept cheap — one pref read, state writes,
     * at most two intent starts).
     *
     *  - **RINGING**, takeover enabled, nothing in flight: raise the overlay (number may be null
     *    on the first broadcast — the numbered one refreshes it).
     *  - **RINGING** while a local call is in flight: if this broadcast carries the number and
     *    the overlay doesn't have it yet, update the display.
     *  - **OFFHOOK** (owner answered on the dialer) or **IDLE** (ended / voicemail) while a local
     *    call is in flight: close the overlay.
     */
    fun onPhoneState(context: Context, stateString: String, phoneNumber: String?) {
        when (stateString) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val prefs = AppPreferences(context)
                if (!prefs.isTakeoverEnabled()) return

                // Option A: when Callrex IS the default dialer, the InCallService path
                // (AiCallInCallService -> AiCallActivity, stock look + "AI Assistant" button)
                // already owns the call UI. Raising the old 3-button overlay here too would
                // stack two call screens — so this legacy path steps aside entirely.
                if (DialerRole.isHeld(context)) {
                    AppLogger.i(TAG, "normal incoming call ${phoneNumber ?: "<unknown>"} — default-dialer path owns the UI; legacy overlay skipped")
                    return
                }

                val cur = TakeoverState.current.value
                if (cur != null) {
                    // Double-broadcast: the second RINGING carries the real number.
                    if (isLocalRoom(cur.roomName) &&
                        cur.phoneNumber.isNullOrBlank() &&
                        !phoneNumber.isNullOrBlank()
                    ) {
                        AppLogger.i(TAG, "number arrived on second broadcast: $phoneNumber")
                        TakeoverState.setCurrent(cur.copy(phoneNumber = phoneNumber))
                    }
                    return
                }
                raiseOverlay(context, phoneNumber)
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK,
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val cur = TakeoverState.current.value
                if (cur != null && isLocalRoom(cur.roomName)) {
                    AppLogger.i(TAG, "local call now $stateString — closing takeover overlay")
                    TakeoverState.setCurrent(null)
                    TakeoverState.setPhase(TakeoverPhase.IDLE)
                }
            }
        }
    }

    /**
     * Raise the takeover overlay for a normal incoming call.
     *
     * Order matters: state first (the overlay is a pure function of [TakeoverState] and renders
     * whatever it is told), then the activity. The activity start is wrapped so a blocked
     * background launch (no overlay permission yet) degrades to a logged miss — it must never
     * crash the phone-state path or leave the state pointing at a call with no overlay.
     */
    private fun raiseOverlay(context: Context, number: String?) {
        val app = context.applicationContext
        if (!overlayPermissionGranted(app)) {
            AppLogger.w(TAG, "normal incoming call ${number ?: "<unknown>"} — overlay NOT raised: 'display over other apps' permission is missing (Settings -> Voice Agent -> allow overlay)")
            LogBeacon.sendNow { /* fire-and-forget diagnostics */ }
            return
        }

        AppLogger.i(TAG, "normal incoming call ${number ?: "<unknown>"} — raising takeover overlay")
        TakeoverState.setPhase(TakeoverPhase.RINGING)
        TakeoverState.setCurrent(
            TakeoverCurrentCall(
                roomName = ROOM_LOCAL,
                callDbId = null,
                phoneNumber = number,
                firstSeenMs = System.currentTimeMillis(),
            ),
        )

        // 1) The ringback decision screen — the actual feature.
        val intent = Intent(app, TakeoverOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(TakeoverOverlayActivity.EXTRA_ROOM, ROOM_LOCAL)
            // No EXTRA_CALL_DB_ID: a local call has no engine room / server-owned window; the
            // overlay treats a null callDbId as "the call is local, the phone ends it".
            putExtra(TakeoverOverlayActivity.EXTRA_PHONE, number)
        }
        runCatching { app.startActivity(intent) }
            .onSuccess { AppLogger.i(TAG, "overlay activity launched for local call") }
            .onFailure {
                // BAL block (should not happen once the overlay permission is granted — that IS
                // the exemption). Roll the state back so nothing dangles.
                AppLogger.e(TAG, "overlay launch FAILED: ${it.message} — resetting takeover state")
                LogBeacon.sendNow { /* diagnostics: the field test needs to see this */ }
                TakeoverState.setCurrent(null)
                TakeoverState.setPhase(TakeoverPhase.IDLE)
            }

        // 2) Best-effort process warmth: the monitor's persistent notification keeps the app
        // alive for the 45 s window even if the system would otherwise reclaim it. Not a
        // dependency — the overlay is already on screen by the time this runs.
        runCatching { RingingMonitorService.raiseLocalRinging(app, number) }
            .onFailure { AppLogger.w(TAG, "monitor warm-start skipped (blocked): ${it.message}") }
    }
}
