package com.baba.callvault.services.takeover

import android.app.role.RoleManager
import android.content.Context
import com.baba.callvault.utils.AppLogger

/**
 * Single source of truth for "is Callrex the phone's default dialer?" — read from
 * [RoleManager] on every call, because the answer is a device-level fact (the user can change
 * it in system Settings at any time), not an app preference.
 *
 * Why it matters: while the dialer role is held, [AiCallInCallService] receives every telephony
 * call and [AiCallActivity] is the call UI (stock look + the floating "AI Assistant" button).
 * [NormalCallTrigger] must NOT raise the old 3-button takeover overlay at the same time —
 * that would put two call UIs on top of each other. The guard lives in
 * [NormalCallTrigger.onPhoneState] and reads through here, so both paths agree on the same fact.
 */
object DialerRole {

    private const val TAG = "CV:DialerRole"

    /** True when Callrex currently holds the system dialer role (Android 7+; minSdk is 30). */
    fun isHeld(context: Context): Boolean {
        val rm = runCatching { context.getSystemService(RoleManager::class.java) }
            .getOrNull()
            ?: return false
        val held = runCatching { rm.isRoleHeld(RoleManager.ROLE_DIALER) }.getOrDefault(false)
        if (held) {
            AppLogger.d(TAG, "dialer role held — InCallService path owns the call UI")
        }
        return held
    }

    /** The system role-assignment dialog intent (shown from Settings). Null if unavailable. */
    fun requestIntent(context: Context): android.content.Intent? {
        val rm = runCatching { context.getSystemService(RoleManager::class.java) }.getOrNull()
        return rm?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }
}
