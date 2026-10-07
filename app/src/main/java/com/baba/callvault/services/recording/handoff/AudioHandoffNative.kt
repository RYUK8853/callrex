/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * JNI bridge to `libaudiohandoff.so`, the native half of "Resilient recording".
 *
 * Loaded in BOTH processes, by different means (see [ensureLoaded] / [ensureLoadedFromApk]):
 *  • the DAEMON extracts the `IAudioRecord` binder + cblk ashmem fd from a privileged `AudioRecord`;
 *  • the APP mmaps the delivered cblk and drains its ring, which is what lets a recording outlive the
 *    daemon.
 */
object AudioHandoffNative {
    private const val TAG = "CV:HandoffNative"
    private const val LIB_NAME = "audiohandoff"
    private const val SO_NAME = "lib$LIB_NAME.so"

    @Volatile private var loaded = false

    /** Loads the library once via the app classloader (APP process); returns availability. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return runCatching { System.loadLibrary(LIB_NAME); loaded = true; true }.getOrDefault(false)
    }

    /**
     * Loads the library in the DAEMON. app_process has no classloader library-search path, so
     * `System.loadLibrary` cannot find it — it must be loaded by absolute path from the extracted
     * native-lib dir that sits next to the APK ([apkPath] = `applicationInfo.sourceDir`).
     */
    fun ensureLoadedFromApk(apkPath: String): Boolean {
        if (loaded) return true
        val so = findExtractedLib(apkPath)
        if (so == null) {
            AppLogger.e(TAG, "$SO_NAME not found under ${File(apkPath).parentFile}/lib — handoff unavailable")
            return false
        }
        return runCatching { System.load(so.absolutePath); loaded = true; true }
            .onFailure { AppLogger.e(TAG, "System.load($so) failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Locates the extracted `libaudiohandoff.so` next to the APK, by SEARCHING `lib/` rather than
     * assuming the subdirectory's name.
     *
     * That directory is named after the **instruction set** (`arm64`), not the build ABI
     * (`arm64-v8a`) — a difference that once silently disabled the whole feature, because a failed
     * load only makes `startHandoff` return false and the engine then falls back to the daemon
     * recording path, so the call still records and nothing looks broken from outside. Searching
     * costs one directory listing, once per daemon lifetime.
     */
    internal fun findExtractedLib(apkPath: String): File? {
        val libRoot = File(File(apkPath).parentFile, "lib")
        return libRoot.listFiles()
            ?.asSequence()
            ?.map { File(it, SO_NAME) }
            ?.firstOrNull { it.isFile }
    }

    /** True if `ptr` is a native android::AudioRecord (ptr+0x190 -> +0x10 is a BpBinder). */
    external fun nativeValidateArPtr(ptr: Long): Boolean

    /** Extracts the IAudioRecord BpBinder from the native AudioRecord + wraps it as a Java IBinder. */
    external fun nativeExtractBinder(ptr: Long): android.os.IBinder?

    /** Finds + dups the cblk ashmem fd in this process, identified by its frameCount header (-1 if none). */
    external fun nativeFindCblkFd(expectedFrameCount: Int): Int

    /** ioctl ASHMEM_GET_SIZE on `fd` — the app uses this to mmap the cblk at its true (rate-dependent) size. */
    external fun nativeAshmemSize(fd: Int): Int

    /**
     * Drains the cblk ring until STOPPED, streaming ORDERED interleaved PCM-16 to `writeFd` (a pipe
     * write end whose ownership is transferred to native — closed there to signal EOF). Ring geometry:
     * `frameCount` frames of `frameSize` bytes (2 mono / 4 stereo) starting at byte `dataOff`.
     * `guardFrames` holds back the freshest N frames each cycle (avoids torn reads at the write cursor).
     * `stopFlag` is a direct ByteBuffer whose first int the caller flips non-zero to stop; `maxSeconds`
     * is a safety cap in case a stop signal is ever lost.
     */
    external fun nativeDrainToPipe(
        fd: Int, size: Int, frameCount: Int, dataOff: Int, frameSize: Int,
        guardFrames: Int, writeFd: Int, stopFlag: java.nio.ByteBuffer, maxSeconds: Int,
        keepPipeOpen: Boolean,
        statsOut: java.nio.ByteBuffer?,
    ): Int

    /** Bytes needed for the [nativeDrainToPipe] stats buffer: four int64s. */
    const val STATS_BYTES = 32

    /** What one drain segment actually did, read back from the stats buffer. */
    data class DrainStats(
        val bytesStreamed: Long,
        val droppedFrames: Long,
        val overrunEvents: Long,
        val elapsedMs: Long,
    ) {
        /** True when audio was thrown away because the drain could not keep up with the ring. */
        val hasLoss: Boolean get() = droppedFrames > 0

        override fun toString(): String =
            "streamed=${bytesStreamed}B elapsed=${elapsedMs}ms dropped=${droppedFrames}frames/${overrunEvents}overruns"

        companion object {
            fun read(buf: java.nio.ByteBuffer): DrainStats {
                val l = buf.asLongBuffer()
                return DrainStats(l.get(0), l.get(1), l.get(2), l.get(3))
            }
        }
    }

    /**
     * Why a drain ended — the single fact that says whether a recording is complete or was cut off.
     *
     * The native side has always known this and always logged it, but only to **logcat**, whose
     * 256 KiB ring rotates within minutes. A user exports a report long after the event, so the line
     * was invariably gone: five field reports in a row came back without the one thing they were
     * collected for. Returning the reason puts it in the app's own log file, which persists.
     *
     * Values must stay in sync with the `DRAIN_EXIT_*` defines in `audiohandoff.cpp`.
     */
    enum class DrainExit(val code: Int, val label: String, val isClean: Boolean) {
        STOPPED(0, "stop requested (normal end of recording)", true),
        INVALIDATED(1, "TRACK INVALIDATED by AudioFlinger (CBLK_INVALID) — capture torn down mid-call", false),
        STALLED(2, "ring stalled — the server stopped writing", false),
        MMAP_FAILED(3, "could not map the control block", false),
        MAX_SECONDS(4, "hit the safety cap without a stop signal", false),
        PIPE_BROKEN(5, "the encoder went away — nothing was reading the captured audio", false),
        UNKNOWN(-1, "unrecognised exit code", false);

        companion object {
            fun of(code: Int): DrainExit = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}
