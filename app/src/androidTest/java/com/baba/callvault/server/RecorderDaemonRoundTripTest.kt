/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the real daemon over a real binder: launch it as shell, record, stop, and look at the file.
 *
 * **What this exists to catch.** The recorder was lifted out of `RecorderServer.main()` into
 * [RecorderServiceImpl] so a Shizuku user service can host the same code
 * (`docs/dev-notes/2026-08-24-shizuku-support-plan.md`). That refactor moved every binder method and
 * added an `onTransact` override which *every* call now passes through — a mistake there breaks
 * dispatch for the whole interface, and no JVM test can see it because there is no binder in a JVM.
 *
 * The daemon is started here the same way production starts it: `setsid app_process` over a shell
 * command, run through the instrumentation's own shell (uid 2000), which is the same uid Shizuku would
 * give a user service. So this test is equally the acceptance test for the Shizuku backend later — only
 * the launch line changes.
 *
 * **What it deliberately does not prove:** that a *call* records. There is no carrier downlink on an
 * emulator, so the audio here is whatever the microphone source yields. Real two-sided audio is still a
 * job for a phone and a pair of ears.
 *
 * ⚠️ **Run this on an emulator, not on a phone someone relies on.** It `pkill`s the recorder daemon by
 * class name, both before and after — and the daemon it finds on a real phone is the one that would have
 * recorded the next call. `-PisolateTestApp` does not protect against this: the isolated build installs
 * under its own applicationId, but the daemon's class name is identical, so the pkill still matches the
 * real app's daemon.
 */
@RunWith(AndroidJUnit4::class)
class RecorderDaemonRoundTripTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** The source most likely to exist anywhere, including an emulator with no telephony. */
    private val source = "mic-voice-communication"
    private val codec = "opus"

    private var service: IRecorderService? = null

    /** Held open for the whole test: closing it would kill the daemon we just started. */
    private var launchPipe: ParcelFileDescriptor? = null

    @After
    fun tearDown() {
        runCatching { service?.stopRecording() }
        runCatching { shell("pkill -f $DAEMON_CLASS") }
        runCatching { launchPipe?.close() }
    }

    @Test
    fun the_daemon_starts_delivers_its_binder_and_records_a_file() {
        val binder = startDaemonAndAwaitBinder()
        assertNotNull("The daemon never delivered its binder to the app", binder)
        service = binder

        // isRecording() is the cheapest possible proof that onTransact still dispatches ordinary
        // AIDL methods rather than swallowing them.
        assertTrue("A fresh daemon should not be recording", !binder!!.isRecording)

        val out = File(context.cacheDir, "daemon-roundtrip.opus").apply { delete() }
        val pfd = ParcelFileDescriptor.open(
            out,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
        )

        pfd.use { binder.startRecording(source, codec, 16_000, it) }

        // Capture setup is posted to the daemon's worker thread, so recording begins shortly after
        // the call returns rather than during it.
        val started = waitUntil(5_000) { binder.isRecording }
        assumeTrue(
            "This device has no usable $source capture (expected on some emulators) — " +
                "binder dispatch was still proven above",
            started
        )

        Thread.sleep(RECORD_MS)
        binder.stopRecording()

        assertTrue("stopRecording() should leave the daemon idle", !binder.isRecording)
        assertTrue("The daemon wrote no audio at all", out.length() > 0)
    }

    @Test
    fun the_daemon_can_make_the_grants_that_need_shell_and_says_honestly_when_it_cannot() {
        val binder = startDaemonAndAwaitBinder()
        assertNotNull("The daemon never delivered its binder to the app", binder)
        service = binder

        // Shell, not root — the whole premise. 0 would mean Shizuku was started rooted (Sui).
        assertEquals("The daemon should be running as shell", 2000, binder!!.hostUid())

        // An app op the shell user is allowed to set, read back rather than assumed: the command that
        // sets it can exit 0 while doing nothing.
        assertTrue(
            "shell could not grant a package-level app op",
            binder.grantAppOp(context.packageName, "RECORD_AUDIO", 0)
        )

        // And the honest negative. CallVault declares no android.intent.action.DIAL component, so it
        // does not QUALIFY for the dialer role — the grant must report false rather than claim success.
        // This is not a privilege problem and root would not fix it; logcat's Role tag says as much.
        assertFalse(
            "A package that does not qualify for a role must not be reported as holding it",
            binder.grantRole("android.app.role.DIALER", context.packageName, 0)
        )
    }

    /**
     * Starts the daemon as shell and waits for its binder.
     *
     * NOT RecorderServerLauncher's literal command. That one is written for an ADB shell and leans on
     * redirection, backgrounding and a trailing sleep; `UiAutomation.executeShellCommand` is not a shell
     * and silently drops all of it — the first version of this test failed with no daemon and no log
     * line at all, because app_process was never reached. `setsid env` is a pure exec chain that needs
     * no shell: setsid detaches into its own session exactly as production does, and env carries
     * CLASSPATH in without a shell assignment.
     *
     * The returned pipe is kept OPEN for the life of the test (closed in tearDown) rather than read to
     * EOF: reading would block forever on a daemon that runs a Looper, and closing early is what kills a
     * child before app_process has finished loading.
     */
    private fun startDaemonAndAwaitBinder(): IRecorderService? {
        val apk = context.applicationInfo.sourceDir

        // Start clean: a daemon left over from an earlier run would answer with old code.
        shell("pkill -f $DAEMON_CLASS")
        Thread.sleep(500)

        launchPipe = instrumentation.uiAutomation.executeShellCommand(
            "setsid env CLASSPATH=$apk app_process / $DAEMON_CLASS $apk"
        )
        return awaitBinder()
    }

    /** Polls the holder the daemon pushes its binder into; the push is asynchronous. */
    private fun awaitBinder(): IRecorderService? {
        waitUntil(BINDER_TIMEOUT_MS) { RecorderConnection.service != null }
        return RecorderConnection.service
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Runs [command] as uid 2000 — the same uid the daemon and a Shizuku user service run as. */
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }

    private companion object {
        const val DAEMON_CLASS = "com.baba.callvault.server.RecorderServer"
        const val BINDER_TIMEOUT_MS = 20_000L
        const val RECORD_MS = 2_000L
        const val POLL_MS = 200L
    }
}
