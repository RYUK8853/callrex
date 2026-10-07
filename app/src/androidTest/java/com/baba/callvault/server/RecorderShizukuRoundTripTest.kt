/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.ParcelFileDescriptor
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The same recording round trip as [RecorderDaemonRoundTripTest], with Shizuku starting the recorder
 * instead of our ADB shell.
 *
 * That is the whole claim of Shizuku support, and this is what proves it: two hosts, one
 * [RecorderServiceImpl], the same binder arriving at the same [RecorderConnection], and a file at the
 * end. If this passes and the daemon test passes, the two backends are interchangeable as far as
 * anything downstream can tell.
 *
 * **Skips rather than fails when Shizuku is absent**, since most machines running these tests will not
 * have it. To set an emulator up:
 *
 * ```
 * adb -s emulator-5554 install -r shizuku.apk
 * adb -s emulator-5554 shell "nohup <nativeLibraryDir>/arm64/libshizuku.so >/dev/null 2>&1 &"
 * ```
 *
 * The Shizuku permission is granted by the test itself — granting it with `adb shell pm grant` cannot
 * work, because AGP installs the app for the run and uninstalls it afterwards.
 *
 * ⚠️ Emulator only, for the same reason as the daemon test: it kills recorder processes by name.
 */
@RunWith(AndroidJUnit4::class)
class RecorderShizukuRoundTripTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val source = "mic-voice-communication"
    private val codec = "opus"

    /**
     * Grants Shizuku permission the only way it can be granted: by answering Shizuku's own dialog.
     *
     * Two shortcuts were tried first and neither can work. `adb shell pm grant` cannot survive the run,
     * because AGP installs the app for `connectedAndroidTest` and uninstalls it afterwards. And
     * `UiAutomation.grantRuntimePermission` has no effect either, because **Shizuku 13 keeps its grants
     * in its own server**, not in the OS permission database — `Shizuku.checkSelfPermission()` asks the
     * server, so the OS-level permission being granted is beside the point.
     *
     * Best effort: if the dialog does not appear or the button is worded differently, the test simply
     * skips on the assumption below rather than failing for the wrong reason.
     */
    @Before
    fun grantShizukuPermission() {
        if (!ShizukuBackend.isRunning() || ShizukuBackend.hasPermission()) return

        ShizukuBackend.requestPermission()

        val device = UiDevice.getInstance(instrumentation)

        // Polled rather than waited-on-once. Shizuku's dialog can take several seconds to appear on a
        // phone that is busy freezing and unfreezing app processes, and the button's wording differs
        // between Android versions and OEM skins — an OP9 on ColorOS says "Allow all the time".
        val deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val allow = listOf("Allow all the time", "Allow", "ALLOW", "Always allow")
                .firstNotNullOfOrNull { device.findObject(By.text(it)) }
            if (allow != null) {
                allow.click()
                device.waitForIdle()
                break
            }
            device.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    @After
    fun tearDown() {
        runCatching { RecorderConnection.service?.stopRecording() }
        // remove=true: leave nothing of ours running inside Shizuku for the next test to trip over.
        runCatching { ShizukuBackend.stop(remove = true) }
    }

    @Test
    fun shizuku_starts_the_same_recorder_and_it_records() {
        assumeTrue("Shizuku is not installed on this device", ShizukuBackend.isInstalled(context))
        assumeTrue("No Shizuku server is running", ShizukuBackend.isRunning())
        assumeTrue("CallVault has not been granted Shizuku permission", ShizukuBackend.hasPermission())

        // Make sure nothing from the ADB path is alive: two recorders would fight over the same audio
        // input, and the winner would not be predictable.
        shell("pkill -f com.baba.callvault.server.RecorderServer")
        Thread.sleep(500)

        assertTrue("Shizuku refused to start the recorder service", ShizukuBackend.start())

        val binder = awaitBinder()
        assertNotNull("Shizuku never handed back a recorder binder", binder)

        // Proves ordinary AIDL dispatch survives the onTransact override on this host too.
        assertTrue("A freshly started service should not be recording", !binder!!.isRecording)

        val out = File(context.cacheDir, "shizuku-roundtrip.opus").apply { delete() }
        val pfd = ParcelFileDescriptor.open(
            out,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
        )

        pfd.use { binder.startRecording(source, codec, 16_000, it) }

        val started = waitUntil(5_000) { binder.isRecording }
        assumeTrue(
            "This device has no usable $source capture — Shizuku binding was still proven above",
            started
        )

        Thread.sleep(RECORD_MS)
        binder.stopRecording()

        assertTrue("stopRecording() should leave the service idle", !binder.isRecording)
        assertTrue("The Shizuku-hosted recorder wrote no audio at all", out.length() > 0)
    }

    /**
     * Leaving Shizuku mode must leave **nothing** of Shizuku's behind — neither the process nor the
     * binder.
     *
     * This is a regression test for a measured bug, not a hypothetical. `ShizukuBackend.stop()` only
     * asks *Shizuku* to destroy the service, and on the OP9 it did not: the process was alive 30s
     * later, so its binder never died, the teardown wait ran out, and `ensureServerRunning` then
     * "reused" that binder. The app sat in STANDALONE mode recording through a Shizuku-hosted
     * recorder, while the switch dialog said "Ready — using CallVault". Nothing was visibly broken:
     * handoff, VoIP arming and speaker attribution simply never happened again.
     */
    @Test
    fun leaving_shizuku_mode_kills_the_user_service_and_releases_the_binder() {
        assumeTrue("Shizuku is not installed on this device", ShizukuBackend.isInstalled(context))
        assumeTrue("No Shizuku server is running", ShizukuBackend.isRunning())
        assumeTrue("CallVault has not been granted Shizuku permission", ShizukuBackend.hasPermission())

        shell("pkill -f com.baba.callvault.server.RecorderServer")
        Thread.sleep(500)

        val prefs = AppPreferences(context)
        val original = prefs.getPrivilegedMode()
        try {
            prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
            assertTrue("Shizuku refused to start the recorder service", ShizukuBackend.start())
            assertNotNull("Shizuku never handed back a recorder binder", awaitBinder())
            assertTrue("The user service is not running, so this proves nothing", userServiceAlive())

            RecorderBackend.switchTo(context, PrivilegedMode.STANDALONE)

            assertTrue(
                "The Shizuku binder is still attached after leaving the mode — the next " +
                    "ensureRunning will reuse it and record through the wrong host",
                !RecorderConnection.isConnected
            )
            assertTrue(
                "The Shizuku-hosted recorder process outlived the mode switch",
                waitUntil(TEARDOWN_TIMEOUT_MS) { !userServiceAlive() }
            )
        } finally {
            prefs.setPrivilegedMode(original)
        }
    }

    /** Whether a Shizuku-hosted recorder process (`…:recorder`) exists right now. */
    private fun userServiceAlive(): Boolean =
        shellOut("ps -A -o NAME").lineSequence().any { it.trim().endsWith(":recorder") }

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

    private fun shell(command: String) {
        shellOut(command)
    }

    /**
     * Runs a command and returns its output.
     *
     * The returned pipe must be read to completion and only then closed — `executeShellCommand` is not
     * a shell, so redirection, `&` and `sleep` are silently dropped, and closing early truncates.
     */
    private fun shellOut(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { String(it.readBytes()) }
        }

    private companion object {
        const val DIALOG_TIMEOUT_MS = 20_000L
        const val BINDER_TIMEOUT_MS = 20_000L
        const val RECORD_MS = 2_000L
        const val POLL_MS = 200L

        /** Matches `RecorderBackend`'s own teardown budget; the process should be gone well inside it. */
        const val TEARDOWN_TIMEOUT_MS = 5_000L
    }
}
