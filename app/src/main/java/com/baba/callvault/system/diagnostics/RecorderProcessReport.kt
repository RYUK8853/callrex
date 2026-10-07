/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * How many privileged recorder processes are alive, from `ps`.
 *
 * Exactly one is correct. More than one means a previous daemon did not die when it was told to, and
 * a daemon that is still alive is still holding whatever it held — including a microphone, which no
 * ledger of ours can see because the ledger lives inside the process that is talking to us, not
 * inside the orphan.
 *
 * **Why this cannot be inferred from the log.** `RecorderServiceImpl` clears its siblings with
 * `Process.killProcess(pid)`, which returns void, throws nothing when the signal cannot be delivered,
 * and never checks that the victim actually went away. So "Clearing 3 other recorder process(es)" is
 * printed whether three processes died or none did. On the reporting device's log of 2026-09-01 the
 * same pid appeared in three consecutive clearing rounds inside 20 ms, which is what a process that
 * survived being killed looks like.
 */
object RecorderProcessReport {

    /**
     * Command lines that belong to a **privileged** recorder.
     *
     * `app_process` is the marker because that is how every privileged host is launched — our daemon,
     * a scrcpy server, a Shizuku user service — and the app's own process does not use it. Matching
     * "callvault" instead was tried first and counted the app itself, which reported two recorders on
     * a perfectly healthy phone: the exact false alarm this section exists to avoid raising.
     */
    private val RECORDER_MARKERS = listOf("app_process")

    /** One live process, as `ps` reported it. */
    data class Proc(val user: String, val pid: String, val args: String)

    /**
     * Parses `ps -A -o USER,PID,ARGS` output down to the recorder processes.
     *
     * Matching is on the command line rather than the user, because a recorder can be hosted by the
     * shell user or by Shizuku, and both are worth counting.
     */
    fun parse(psOutput: String): List<Proc> =
        psOutput.lineSequence()
            .map { it.trim() }
            .filter { line -> RECORDER_MARKERS.any { it in line.lowercase() } }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 3)
                if (parts.size < 3) null else Proc(parts[0], parts[1], parts[2])
            }
            .filterNot { it.args.contains("grep", ignoreCase = true) }
            .toList()

    /** The section as it appears in the report: the count is the finding, the list is the evidence. */
    fun render(psOutput: String?): String {
        if (psOutput.isNullOrBlank()) {
            return "Recorder processes: could not read `ps` — section unavailable."
        }
        val procs = parse(psOutput)
        return buildString {
            appendLine("--- Recorder processes ---")
            when (procs.size) {
                0 -> appendLine("None running. The daemon is not up.")
                1 -> appendLine("1 running, which is correct.")
                else -> appendLine(
                    "${procs.size} running — only ONE should be. A daemon that outlived its " +
                        "replacement still holds whatever it held, including the microphone, and " +
                        "nothing in CallVault's own logs can see inside it."
                )
            }
            procs.forEach { appendLine("    ${it.user} ${it.pid} ${it.args}") }
        }
    }
}
