/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.StringRes
import com.baba.callvault.R
import com.baba.callvault.data.SyncScheduleMode

/**
 * Labels and option ranges for the Drive upload schedule, shared by the setup wizard and Settings.
 *
 * The two screens render the schedule differently — the wizard uses full-width option cards, Settings
 * uses its dropdown rows — but they must agree on the wording and the offered values. Keeping the
 * mappings here means a renamed mode or a changed minute step cannot drift between them.
 *
 * The string keys stay `wizard_`-prefixed deliberately. They are already translated in all ten
 * locales, and renaming a key means re-translating it everywhere for no user-visible gain — see
 * `gradle/translation-coverage.gradle.kts`, which fails the build on a locale that has drifted.
 */
object SyncScheduleLabels {

    /** Minutes offered for the scheduled upload — quarter hours, which is granular enough for a backup. */
    val MINUTE_OPTIONS = listOf(0, 15, 30, 45)

    /** `java.util.Calendar` day-of-week constants, SUNDAY=1 .. SATURDAY=7. */
    val DAY_OF_WEEK_OPTIONS = (1..7).toList()

    @StringRes
    fun titleOf(mode: SyncScheduleMode): Int = when (mode) {
        SyncScheduleMode.IMMEDIATE -> R.string.wizard_schedule_immediate
        SyncScheduleMode.DAILY -> R.string.wizard_schedule_daily
        SyncScheduleMode.WEEKLY -> R.string.wizard_schedule_weekly
    }

    @StringRes
    fun descriptionOf(mode: SyncScheduleMode): Int = when (mode) {
        SyncScheduleMode.IMMEDIATE -> R.string.wizard_ui_schedule_immediate_desc
        SyncScheduleMode.DAILY -> R.string.wizard_ui_schedule_daily_desc
        SyncScheduleMode.WEEKLY -> R.string.wizard_ui_schedule_weekly_desc
    }

    /** Maps a `java.util.Calendar` day-of-week constant to its label. */
    @StringRes
    fun dayOfWeekOf(day: Int): Int = when (day) {
        1 -> R.string.wizard_day_sunday
        2 -> R.string.wizard_day_monday
        3 -> R.string.wizard_day_tuesday
        4 -> R.string.wizard_day_wednesday
        5 -> R.string.wizard_day_thursday
        6 -> R.string.wizard_day_friday
        else -> R.string.wizard_day_saturday
    }
}
