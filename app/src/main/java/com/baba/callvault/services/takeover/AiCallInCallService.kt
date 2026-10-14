package com.baba.callvault.services.takeover

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.baba.callvault.BuildConfig

/**
 * Option A: Callrex as default dialer — InCallService.
 *
 * When Callrex holds ROLE_DIALER the system routes every telephony call
 * through this service. For incoming (RINGING / NEW) calls it launches
 * [AiCallActivity], the stock-look call screen that carries the floating
 * "AI Assistant" button.
 *
 * The live [Call] reference is shared via [activeRingingCall] so the
 * activity can invoke answer() / reject() / deflect() on it.
 *
 * Outgoing and already-active calls are tracked (callbacks registered)
 * but do NOT launch a UI — the system handles their lifecycle and the
 * user's outgoing-call flow is out of scope for Option A.
 */
class AiCallInCallService : InCallService() {

    companion object {
        const val TAG = "AiCallInCallService"

        /**
         * The Call that is currently ringing (or was most recently ringing).
         * Read by [AiCallActivity] on the main thread — InCallService
         * callbacks are delivered on the main looper, so there is no
         * cross-thread race for the single-call scenario.
         */
        @Volatile
        var activeRingingCall: Call? = null
    }

    private val callbackMap = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "onCallAdded handle=${call.details.handle} state=${call.getState()}")
        }

        val callback = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d(TAG, "onStateChanged handle=${c.details.handle} state=$state")
                }
                when (state) {
                    Call.STATE_RINGING, Call.STATE_NEW -> {
                        activeRingingCall = c
                        launchCallActivity(c)
                    }
                    Call.STATE_ACTIVE,
                    Call.STATE_DIALING,
                    Call.STATE_CONNECTING,
                    Call.STATE_HOLDING -> {
                        // Answered or outgoing — no longer in the "ringing window"
                        if (activeRingingCall === c) activeRingingCall = null
                    }
                    Call.STATE_DISCONNECTING,
                    Call.STATE_DISCONNECTED -> {
                        if (activeRingingCall === c) activeRingingCall = null
                    }
                }
            }
        }

        call.registerCallback(callback)
        callbackMap[call] = callback

        // onCallAdded can fire after the call has already started ringing
        // (service cold-start latency). Check current state and launch if needed.
        if (call.getState() == Call.STATE_RINGING || call.getState() == Call.STATE_NEW) {
            activeRingingCall = call
            launchCallActivity(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "onCallRemoved handle=${call.details.handle}")
        }
        callbackMap.remove(call)?.let { call.unregisterCallback(it) }
        if (activeRingingCall === call) activeRingingCall = null
    }

    private fun launchCallActivity(call: Call) {
        val number = call.details.handle?.toString().orEmpty()
        val intent = Intent(this, AiCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AiCallActivity.EXTRA_CALL_NUMBER, number)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.e(TAG, "Failed to launch AiCallActivity", e)
            }
        }
    }
}
