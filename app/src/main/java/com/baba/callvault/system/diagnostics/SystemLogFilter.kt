/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Deciding which logcat lines may go into a shareable report.
 *
 * **This is a privacy filter first and a noise filter second.** A raw logcat dump carries other
 * applications' logs and telephony lines containing phone numbers, and these reports get posted
 * publicly on GitHub issues. Everything not on the whitelist is dropped, so an unfamiliar tag is
 * excluded by default rather than included by default — the safe direction when the cost of being
 * wrong is a stranger's contacts in a public thread.
 *
 * Kept pure so the whitelist can be tested exhaustively without a device.
 */
object SystemLogFilter {

    /**
     * Tags allowed through, beyond our own `CV:` prefix.
     *
     * Each earns its place by having answered, or being able to answer, a real question:
     *  - the audio stack is where "recorded but silent" is decided, and where One UI logged the
     *    `rec update … silenced` line that explained the Samsung VoIP bug
     *  - `avc`/`SELinux` explain a denial that looks like a logic bug from our side
     *  - `ActivityManager`, `lowmemorykiller`, `libc`, `DEBUG` say how and why we or the daemon died
     *  - `adbd` transport churn has cost recordings before, more than once
     */
    private val ALLOWED_TAGS = setOf(
        "AudioFlinger", "AudioPolicyService", "AudioPolicyManager", "AudioRecord", "AudioTrack",
        // Distinguishes the TWO ways the microphone app-op can be left started, which look identical
        // from outside and need opposite fixes. This tag carries AudioPolicyService::releaseInput's
        //     ALOGW("%s releasing active client portId %d")
        // — an input released while still active, i.e. the path that skips finishRecording() because
        // only stopInput() calls it. If that line is present when the green dot is stuck, it is the
        // release-without-stop path (what the handoff teardown fix addresses). If the dot is stuck and
        // this line is ABSENT, it is instead the `if (!client->silenced)` guard inside stopInput
        // swallowing the finish — a different bug that our fix does not touch.
        "AudioPolicyInterfaceImpl",
        "avc", "SELinux", "auditd",
        "ActivityManager", "lowmemorykiller", "libc", "DEBUG", "tombstoned",
        "adbd", "app_process",
    )

    /**
     * Tags whose lines are kept only when they are **about us**.
     *
     * Found by reading a real report rather than by reasoning: `ActivityManager` is on the whitelist
     * because it says how and why our process died, but it narrates every process on the device, so
     * the file filled with lines like `Process com.paypal.android.p2pmobile has died` and
     * `pkg=com.alibaba.aliexpresshd`. A whitelisted *tag* is not a whitelisted *line* — that is an
     * inventory of the user's installed apps, going into a public issue.
     *
     * The audio tags are deliberately NOT in here: the line that explained the Samsung VoIP bug named
     * `com.android.shell` and no CallVault identifier at all, so a mentions-us rule would have dropped
     * the single most valuable line we have ever collected.
     */
    private val ABOUT_US_ONLY = setOf(
        "ActivityManager", "lowmemorykiller", "libc", "DEBUG", "tombstoned",
        "avc", "SELinux", "auditd",
    )

    /** How a line says it concerns CallVault: the app package, or the daemon's process and shell uid. */
    private val OURS = listOf("com.baba.callvault", "app_process", "com.android.shell", "uid:2000")

    /** Our own lines, app and daemon alike. */
    private const val OWN_TAG_PREFIX = "CV:"

    /**
     * Whether [line] — one line of `logcat -v threadtime` — belongs in the report.
     *
     * A line whose tag cannot be found is dropped rather than passed through. Malformed input is the
     * case most likely to smuggle something unexpected into a public issue.
     */
    fun keep(line: String): Boolean {
        val tag = tagOf(line) ?: return false
        if (tag.startsWith(OWN_TAG_PREFIX)) return true
        if (tag !in ALLOWED_TAGS) return false
        if (tag in ABOUT_US_ONLY) return OURS.any { line.contains(it) }
        return true
    }

    /**
     * The tag from a `threadtime` line, or null when the line does not have that shape.
     *
     * Format: `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message`.
     *
     * The tag ends at the first colon **followed by a space**, not at the first colon: our own tags
     * are `CV:RecorderConn` and the like, so stopping at the first colon truncates every one of them
     * to `CV` — which then fails to match the whitelist it was supposed to satisfy.
     */
    fun tagOf(line: String): String? {
        val match = THREADTIME.find(line) ?: return null
        return match.groupValues[1].trim().ifEmpty { null }
    }

    /**
     * Trims [lines] to the last [maxLines], and then to [maxBytes], always keeping the newest.
     *
     * Truncating from the oldest end is the whole point: a report is read to find out what happened
     * just before the failure, so the end of the log is the part worth its bytes.
     */
    fun capToNewest(lines: List<String>, maxLines: Int, maxBytes: Int): List<String> {
        var kept = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        var bytes = kept.sumOf { it.toByteArray().size + 1 }
        if (bytes <= maxBytes) return kept
        // Drop from the front until it fits. Counting down rather than re-summing each time keeps
        // this linear on a report that can run to tens of thousands of lines.
        var from = 0
        while (from < kept.size && bytes > maxBytes) {
            bytes -= kept[from].toByteArray().size + 1
            from++
        }
        kept = kept.drop(from)
        return kept
    }

    private val THREADTIME =
        Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEFS]\s+(\S.{0,63}?):\s""")
}
