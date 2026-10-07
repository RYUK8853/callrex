/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import androidx.annotation.WorkerThread
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * The Silero voice-activity model that whisper uses to trim dead air, unpacked from the APK.
 *
 * **Bundled rather than downloaded.** The speech models are hundreds of megabytes and have to be
 * fetched; this one is 864 KB, which is smaller than several of the PNGs already in the APK. A
 * download step for it would add a failure mode — no network, a half-written file, a user who
 * declined — to something that has no reason to ever be absent.
 *
 * Extracted rather than read in place because whisper.cpp takes a filesystem path
 * (`whisper_vad_init_from_file_with_params` calls `fopen`); an asset inside the APK has no path.
 * Into `filesDir`, which is private to the app and survives an update but not an uninstall.
 *
 * Licensing: Silero VAD is MIT (snakers4/silero-vad), and the ggml conversion is MIT
 * (huggingface.co/ggml-org/whisper-vad). Both permit redistribution provided the copyright and
 * permission notice travel with it — see NOTICE.md.
 */
object VadModel {

    private const val TAG = "CV:Transcribe"

    /** Name in `assets/` and, unchanged, on disk. The version is in the name deliberately. */
    private const val ASSET_NAME = "ggml-silero-v5.1.2.bin"

    /**
     * Absolute path to the unpacked model, or null if it could not be produced.
     *
     * Null is a real outcome and callers must treat it as one: transcription still works without
     * VAD, it just works the way it did before. Never let this fail a run.
     *
     * Written to a temporary file and then renamed, so the destination path either does not exist or
     * holds a complete model — never a prefix of one. A process killed mid-extract leaves the
     * temporary behind and the next call overwrites it, which is why the check below can be a bare
     * existence test rather than a size or hash comparison.
     *
     * The asset's version is in its filename, so an updated model is a different path and extracts
     * on its own rather than needing to be detected. Note the size of the packaged asset cannot be
     * used for this: `assets.openFd` throws for anything aapt compressed, and `.bin` is not on
     * aapt's no-compress list.
     */
    @WorkerThread
    fun ensureExtracted(context: Context): String? {
        val destination = File(context.filesDir, ASSET_NAME)
        if (destination.isFile && destination.length() > 0) return destination.absolutePath

        return try {
            val partial = File(context.filesDir, "$ASSET_NAME.partial")
            context.assets.open(ASSET_NAME).use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            check(partial.renameTo(destination)) { "could not rename ${partial.name}" }
            AppLogger.i(TAG, "Extracted VAD model: ${destination.length()} bytes")
            destination.absolutePath
        } catch (e: Exception) {
            // Warn, never throw. Losing VAD costs quality; failing the transcription costs the
            // transcript, and the caller has no way to make this succeed on retry.
            AppLogger.w(TAG, "VAD model unavailable, decoding without it: ${e.message}")
            null
        }
    }
}
