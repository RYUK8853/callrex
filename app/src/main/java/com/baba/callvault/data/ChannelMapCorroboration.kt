/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

/**
 * When one call's guess about the channels becomes the answer.
 *
 * A single observation is a hypothesis, not a conclusion. Ringback leaks between channels, some
 * carriers send none at all, and a far party who answers instantly leaves no window to hear it in.
 * The cost of believing a wrong one is high and silent: every transcript from then on shows the
 * user's own words as the other person's, and nothing on screen would suggest anything was wrong.
 *
 * So the rule is deliberately hard to satisfy and easy to lose.
 */
object ChannelMapCorroboration {

    /** How many agreeing calls it takes before the mapping is believed at all. */
    private const val AGREEMENT_NEEDED = 2

    /**
     * The mapping worth trusting, given what recent calls observed.
     *
     * A value wins only if at least [AGREEMENT_NEEDED] calls saw it **and** it is strictly more
     * common than anything else. An even split settles nothing however many calls there are: one
     * side is wrong and there is no way to tell which, and refusing costs only neutral labels.
     *
     * That the belief can be *lost* is the point rather than a side effect. The mapping is a
     * property of the device and should never change — so if the evidence stops agreeing, something
     * assumed here is wrong, and neutral labels are the honest answer until it settles again.
     *
     * [ChannelMap.UNKNOWN] observations are not votes. Most calls produce one: an incoming call has
     * no ringback phase of its own to learn from.
     */
    fun trusted(observations: List<ChannelMap>): ChannelMap {
        val votes = observations.filter { it != ChannelMap.UNKNOWN }.groupingBy { it }.eachCount()
        if (votes.isEmpty()) return ChannelMap.UNKNOWN

        val ranked = votes.entries.sortedByDescending { it.value }
        val (leader, leadingCount) = ranked.first()
        if (leadingCount < AGREEMENT_NEEDED) return ChannelMap.UNKNOWN

        val runnerUp = ranked.getOrNull(1)?.value ?: 0
        return if (leadingCount > runnerUp) leader else ChannelMap.UNKNOWN
    }
}
