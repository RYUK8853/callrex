/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the daily [RetentionSweepWorker] that auto-deletes recordings older than the
 * configured retention period. Idempotent: [apply] reconciles the current prefs with WorkManager using
 * [ExistingPeriodicWorkPolicy.UPDATE], so it is safe to call repeatedly (settings change, app start).
 *
 * Retention OFF (both periods 0) -> no periodic work. Otherwise a single daily sweep handles BOTH the
 * device and Drive periods (the worker applies each per copy), anchored at [SWEEP_HOUR].
 */
object RetentionScheduler {

    /** Unique WorkManager name for the periodic retention sweep. */
    const val WORK_NAME = "cv_retention_sweep"

    private const val TAG = "CV:RetentionScheduler"
    private const val PERIOD_HOURS = 24L

    /**
     * Reconciles the periodic sweep with the current retention prefs.
     *
     * "Retention off" now means BOTH the age periods and the size cap are off. It used to mean only
     * the periods, which was correct while they were the sole reason to sweep and became a silent
     * bug the moment the storage cap was added: a user with a cap set and no age period would have
     * had the daily job cancelled out from under them, and the setting would simply never have done
     * anything, with the UI happily showing it enabled.
     */
    fun apply(context: Context) {
        val prefs = AppPreferences(context)
        val maxDays = maxOf(prefs.getRetentionLocalDays(), prefs.getRetentionDriveDays())
        val capBytes = prefs.getStorageCapBytes()
        val workManager = WorkManager.getInstance(context)

        if (maxDays <= 0 && capBytes <= 0L) {
            workManager.cancelUniqueWork(WORK_NAME)
            AppLogger.i(TAG, "Retention sweep cancelled (no age period and no size cap).")
            return
        }

        // Anchored to the device's LOCAL time zone (Calendar default TZ), so the chosen HH:mm fires at
        // local time wherever the user is. Re-anchored on app start and on TIMEZONE_CHANGED.
        val hour = prefs.getRetentionTimeHour()
        val minute = prefs.getRetentionTimeMinute()
        val request = PeriodicWorkRequestBuilder<RetentionSweepWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setInitialDelay(nextDailyDelayMillis(hour, minute), TimeUnit.MILLISECONDS)
            .build()

        // CANCEL_AND_REENQUEUE, not UPDATE. UPDATE keeps an already-enqueued periodic job's existing
        // schedule and ignores the new initial delay, so changing "Run at" changed nothing until the
        // current 24-hour period happened to roll over. Measured on 2026-08-04: the sweep time was moved
        // from 03:30 to 14:00, the job was re-enqueued under a new id — and its next run stayed pinned to
        // the old anchor, ~12h41m away. A user who moves the time and watches the old one pass has been
        // told something untrue by the UI.
        //
        // Re-enqueueing re-anchors it to the next occurrence, which is a fixed wall-clock target, so
        // frequent app starts cannot push the sweep past it. The cost is that a start landing exactly on
        // a running sweep cancels that run; the sweep is idempotent and runs daily, so it simply happens
        // the next day.
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        AppLogger.i(
            TAG,
            "Retention sweep scheduled at $hour:$minute local (localDays=${prefs.getRetentionLocalDays()} driveDays=${prefs.getRetentionDriveDays()})."
        )
    }

    /** Millis from now until the next occurrence of [hour]:[minute] (today if still ahead, else tomorrow). */
    private fun nextDailyDelayMillis(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis - now
    }
}
