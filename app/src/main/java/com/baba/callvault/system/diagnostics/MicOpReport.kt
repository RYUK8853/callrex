/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * What is actually holding the microphone, as the green dot defines it.
 *
 * The privacy indicator is driven by **app-ops**, not by the record-activity log. SystemUI's
 * `AppOpsControllerImpl.OPS_MIC` watches `OP_RECORD_AUDIO`, `OP_PHONE_CALL_MICROPHONE` and the
 * ambient/sandbox trigger ops. So the only direct evidence of a stuck indicator is a mic app-op that
 * was **started and never finished**.
 *
 * `dumpsys appops` marks exactly that with a `Running start at:` line inside the op's block; a
 * completed access instead carries `duration=+…`. That single distinction is the whole test, and it is
 * why this section exists alongside [RecordActivityReport] rather than inside it.
 *
 * **Why it is not enough to read `dumpsys audio`.** That section's record-activity ring cannot pair
 * events for system-only sources at all, and it silently evicts old events once full — so it can
 * report "nothing open" on a phone whose microphone is genuinely held, and "still open" on a phone
 * where nothing is. See the class docs on [RecordActivityReport] for the AOSP chain.
 *
 * **`RECORD_AUDIO_OUTPUT` is deliberately excluded.** That is the op a `REMOTE_SUBMIX` capture notes
 * (`ServiceUtilities::getOpForSource`), and SystemUI does not watch it — so it can never light the
 * dot. Reporting it here would recreate the exact confusion this class was written to end.
 */
object MicOpReport {

    /** Android's own uid for the shell user; every privileged capture of ours is attributed to it. */
    const val SHELL_UID = "2000"

    /**
     * The ops SystemUI's indicator actually watches.
     *
     * Kept as names rather than numbers because that is what `dumpsys appops` prints, and the numeric
     * values are not stable API.
     */
    val MIC_OPS = setOf(
        "RECORD_AUDIO",
        "PHONE_CALL_MICROPHONE",
        "RECEIVE_AMBIENT_TRIGGER_AUDIO",
        "RECEIVE_SANDBOX_TRIGGER_AUDIO",
        "RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO",
    )

    /** One microphone app-op that has been started and not finished. */
    data class HeldOp(val uid: String, val pack: String, val op: String, val startedAt: String) {
        val isShell: Boolean get() = uid == SHELL_UID

        override fun toString(): String =
            "uid=$uid pack=$pack op=$op running since $startedAt" +
                if (isShell) "   <-- CallVault's privileged capture" else ""
    }

    private val UID = Regex("""^\s*Uid (\d+):""")
    private val PACKAGE = Regex("""^\s*Package (\S+):""")
    private val OP = Regex("""^\s*([A-Z_]+) \(""")
    private val RUNNING = Regex("""^\s*Running start at:\s*(\S+)""")

    /**
     * Every microphone op currently running, in dump order.
     *
     * Parsed as a small state machine over the indented dump rather than with one big regex: the
     * uid, the package and the op each live on their own line above the `Running start at:` that
     * matters, and only the innermost of them changes per entry.
     */
    fun heldMicOps(dumpsysAppops: String): List<HeldOp> {
        val held = mutableListOf<HeldOp>()
        var uid = "?"
        var pack = "?"
        var op: String? = null
        for (line in dumpsysAppops.lineSequence()) {
            UID.find(line)?.let { uid = it.groupValues[1]; pack = "?"; op = null; return@let }
            PACKAGE.find(line)?.let { pack = it.groupValues[1]; op = null; return@let }
            OP.find(line)?.let { op = it.groupValues[1] }
            RUNNING.find(line)?.let { m ->
                val current = op
                if (current != null && current in MIC_OPS) {
                    held += HeldOp(uid, pack, current, m.groupValues[1])
                }
            }
        }
        return held
    }

    /** The section as it appears in the report: a verdict first, then the evidence. */
    fun render(dumpsysAppops: String?): String {
        if (dumpsysAppops.isNullOrBlank()) {
            return "--- Microphone app-op (what the green dot actually follows) ---\n" +
                "Could not read `dumpsys appops` — section unavailable."
        }
        val held = heldMicOps(dumpsysAppops)
        val shell = held.filter { it.isShell }
        return buildString {
            appendLine("--- Microphone app-op (what the green dot actually follows) ---")
            when {
                held.isEmpty() ->
                    appendLine(
                        "No microphone app-op is running. Nothing on this phone is holding the " +
                            "microphone right now, so the green dot should be off."
                    )
                shell.isNotEmpty() ->
                    appendLine(
                        "MICROPHONE HELD: ${held.size} running mic app-op(s), ${shell.size} of them " +
                            "as uid $SHELL_UID (shell) — that is CallVault's privileged capture, and " +
                            "it is what puts the green microphone dot on screen."
                    )
                else ->
                    appendLine(
                        "MICROPHONE HELD: ${held.size} running mic app-op(s), none belonging to " +
                            "CallVault (uid $SHELL_UID). Another app is holding the microphone."
                    )
            }
            held.forEach { appendLine("    $it") }
        }
    }
}
