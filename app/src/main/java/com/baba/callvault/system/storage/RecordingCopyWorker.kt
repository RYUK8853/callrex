/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.utils.AppLogger

/**
 * Copies a finished recording from the local SAF folder to the Drive SAF folder and, when the storage
 * target is cloud-only, deletes the local source afterwards.
 *
 * The copy is idempotent and atomic (see [SafHelper.copyFileToFolder]), which matters because this
 * worker is retried: before that was true, every retry uploaded the recording *again*, so Google Drive
 * announced "saved a call" long after the call ended and the folder collected truncated twins.
 *
 * Retries are bounded by [CloudCopyPolicy.MAX_ATTEMPTS]. Giving up is reported to the user and leaves
 * the local file untouched — a recording that is only on the device is recoverable; one that is silently
 * absent from both places is not.
 */
class RecordingCopyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val src = inputData.getString(KEY_SRC)?.toUri() ?: return Result.failure()
        val destFolder = inputData.getString(KEY_DEST_FOLDER)?.toUri() ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: DEFAULT_MIME
        val deleteLocal = inputData.getBoolean(KEY_DELETE_LOCAL, false)

        // The 2026-10-08 field bug, named: the "Drive" folder was set to the recordings folder
        // itself. Every copy then found the file "already in Drive" (it WAS the file), reported
        // success, and — in cloud-only mode — deleted the local original: the only copy of the call.
        // A copy into the folder the source sits in is a no-op by definition, and the delete that
        // follows it is destruction. Refuse the pair: keep the device copy, say so loudly, and
        // stamp the row so the listing still finds the file where it lives.
        if (SafHelper.isDocumentInTree(src, destFolder)) {
            AppLogger.e(
                TAG,
                "'$name': the Drive folder IS the recordings folder — refusing to 'copy' the file " +
                    "onto itself and to delete the only copy. The device copy is kept; pick a " +
                    "different Drive folder in Settings."
            )
            warnDriveFolderIsRecordingsFolder()
            // The row gains a Drive pointer to the file's real location, so the list, playback and
            // the transcription (which falls back to the Drive URI) all resolve to the file that is
            // there. deleteLocalAfter=false: nothing is deleted, and the next honest copy — after
            // the user fixes the folder — re-stamps this row over the real upload.
            RecordingCatalog.markDrive(applicationContext, name, src, srcSizeOf(src), deleteLocalAfter = false)
            return Result.success()
        }

        if (!SafHelper.isFolderValid(applicationContext, destFolder)) {
            return giveUpOrRetry(name, "the Drive folder is not reachable or writable")
        }

        val srcSize = SafHelper.fileSize(applicationContext, src)
        if (srcSize <= 0L) {
            // Either capture produced nothing or the source is gone. Uploading it would put an unplayable
            // file in the user's Drive and mark the recording as backed up; refusing is the honest answer.
            // This is also a terminal answer, not a retry: a source that is empty now stays empty, and
            // retrying one forever is what kept re-uploading recordings hours after the call.
            val gone = runCatching { DocumentFile.fromSingleUri(applicationContext, src)?.exists() != true }
                .getOrDefault(false)
            val what = if (gone) "the source file is gone" else "the source is empty (size=$srcSize)"
            AppLogger.e(TAG, "Not copying '$name' to Drive: $what")
            return Result.failure()
        }

        return when (val result = SafHelper.copyFileToFolder(applicationContext, src, destFolder, name, mime, srcSize)) {
            is SafHelper.CopyResult.AlreadyPresent -> {
                AppLogger.i(TAG, "'$name' is already in Drive; not uploading it again")
                finish(name, result.uri, srcSize, deleteLocal, src, destFolder)
            }

            is SafHelper.CopyResult.Copied -> {
                AppLogger.i(TAG, "Recording copied to Drive ('$name', ${result.bytes} bytes, deleteLocal=$deleteLocal)")
                finish(name, result.uri, result.bytes, deleteLocal, src, destFolder)
            }

            is SafHelper.CopyResult.Failed -> giveUpOrRetry(name, result.reason)
        }
    }

    /**
     * Records the Drive copy on the catalog row and drops the local source when the target is cloud-only.
     *
     * The drop is the one destructive step this worker performs, so it refuses and waits on both the
     * conditions under which it would destroy the only copy of a call:
     *
     *  - the "Drive" copy turned out to live in the SAME tree as the source (the 2026-10-08 field
     *    bug, re-checked here even though [doWork] already refused it up front — the settings can
     *    be re-pointed between the check and the delete); and
     *  - a transcription that still needs the local file is not settled yet (the same field log
     *    shows the delete winning the race against whisper: "Failed to transcribe …
     *    FileNotFoundException" six seconds after the call ended).
     */
    private suspend fun finish(
        name: String,
        driveUri: Uri,
        sizeBytes: Long,
        deleteLocal: Boolean,
        src: Uri,
        destFolder: Uri,
    ): Result {
        var deleteLocal = deleteLocal
        if (deleteLocal && SafHelper.isDocumentInTree(src, destFolder)) {
            AppLogger.e(
                TAG,
                "'$name': refusing to delete the device copy — the 'Drive' copy sits in the same " +
                    "folder, so nothing was actually backed up. The device copy is kept."
            )
            warnDriveFolderIsRecordingsFolder()
            deleteLocal = false
        }

        if (deleteLocal && !transcriptionSettled(name)) {
            // The transcription has not settled inside this attempt's budget. The upload above is
            // done; only the cleanup may not run yet.
            if (runAttemptCount < DELETE_WAIT_ATTEMPTS) {
                // Ask for the next attempt: the copy is idempotent, so it re-enters this exact
                // branch on the backoff cycle and re-checks the transcript state. "Settled" or
                // "spent" is decided then, not now.
                AppLogger.i(TAG, "'$name': transcription not settled yet; keeping the device copy and retrying")
                return Result.retry()
            }
            // The budget is spent and the transcription may still take its time (a charging
            // constraint can stretch it to hours). The honest terminal answer: the Drive copy is
            // done, the device copy stays, and the row keeps BOTH — the recording remains listed,
            // playable, and re-offerable to whatever transcription eventually runs against it. A
            // recording that is only on the device is recoverable; one that is absent from both
            // places is not.
            AppLogger.e(
                TAG,
                "'$name': keeping the device copy permanently — the transcription had not settled " +
                    "after $DELETE_WAIT_ATTEMPTS attempts. Both copies are kept on the recording row."
            )
            RecordingCatalog.markDrive(applicationContext, name, driveUri, sizeBytes.takeIf { it > 0L }, false)
            return Result.success()
        }

        if (deleteLocal) {
            SafHelper.deleteDocument(DocumentFile.fromSingleUri(applicationContext, src), "the device copy of '$name'")
        }
        // Stamp the Drive copy onto the catalog row (clearing the local copy when cloud-only deletes it),
        // so the Home list reflects where the file now lives without re-scanning the Drive folder.
        RecordingCatalog.markDrive(applicationContext, name, driveUri, sizeBytes.takeIf { it > 0L }, deleteLocal)
        return Result.success()
    }

    /**
     * Whether the local file of [name] may be deleted because the transcription will no longer need
     * it.
     *
     * The transcription reads ONLY the local copy ([TranscriptionRunner] decodes
     * `RecordingCatalog.localUri` and has no Drive fallback), so "settled" is a question about the
     * local file's usefulness, not the copy's:
     *
     *  - [TranscriptState.DONE] — the words are in the database; the file may go.
     *  - [TranscriptState.QUEUED] / [TranscriptState.RUNNING] — a worker may still open the file;
     *    the caller waits for it.
     *  - [TranscriptState.FAILED] — the one a user retries by tapping "Transcribe"; deleting the
     *    file under a pending retry is how a single bad decode would destroy the only audio.
     *  - No row at all — the transcription has not run (yet). In automatic mode the call-end
     *    queueing creates it within seconds; in manual mode it is created only when the user taps,
     *    possibly days later. Either way the local file is the only copy anything can transcribe,
     *    so it stays — a recording kept on the device is recoverable, one absent from both places
     *    is not.
     *
     * The wait around this is bounded per attempt and per worker's life: while the state has not
     * settled, each attempt asks WorkManager for the next one (the copy is idempotent, so every
     * retry re-enters here through [doWork] and re-checks the state). Past [DELETE_WAIT_ATTEMPTS]
     * total attempts the device copy stays for good — a transcription waiting on a charging
     * constraint may legitimately take hours, and a delete that out-waits it is the bug this
     * function exists to prevent (2026-10-08 field log: the delete won the race, whisper died on
     * FileNotFoundException, and the recording vanished from the list).
     */
    private suspend fun transcriptionSettled(name: String): Boolean {
        val state = transcriptStateOf(name)
        if (state != null) return state == TranscriptState.DONE
        AppLogger.i(TAG, "'$name': no transcript row yet — the local file is the only copy a transcription could read; keeping it on the device")
        return false
    }

    private suspend fun transcriptStateOf(name: String): TranscriptState? = runCatching {
        if (!TranscriptDatabase.exists(applicationContext)) return@runCatching null
        TranscriptDatabase.get(applicationContext).transcriptDao().findTranscript(name)?.state
    }.getOrNull()

    /**
     * A notification that the Drive folder is set to the recordings folder — the one misconfiguration
     * this version can observe (it is the only one that deletes recordings), so it is the one the
     * user is owed a name for.
     */
    private fun warnDriveFolderIsRecordingsFolder() {
        runCatching {
            RecordingNotificationHelper(applicationContext)
                .showErrorNotification(applicationContext.getString(R.string.recording_error_drive_folder_is_recordings))
        }.onFailure { AppLogger.w(TAG, "Could not warn about the Drive folder: ${it.message}") }
    }

    private fun srcSizeOf(src: Uri): Long? = runCatching { SafHelper.fileSize(applicationContext, src) }
        .getOrNull()?.takeIf { it > 0L }

    /**
     * Asks for another retry while the budget lasts, and reports an honest failure once it is spent —
     * rather than retrying until the end of time, which is what filled Drive with duplicates.
     */
    private fun giveUpOrRetry(name: String, reason: String): Result {
        if (!CloudCopyPolicy.isLastAttempt(runAttemptCount)) {
            AppLogger.w(TAG, "Copy of '$name' to Drive failed ($reason); retrying (attempt ${runAttemptCount + 1})")
            return Result.retry()
        }
        AppLogger.e(TAG, "Giving up on copying '$name' to Drive after ${runAttemptCount + 1} attempts ($reason). The device copy is kept.")
        runCatching {
            RecordingNotificationHelper(applicationContext)
                .showErrorNotification(applicationContext.getString(R.string.recording_error_drive_copy_failed))
        }.onFailure { AppLogger.w(TAG, "Could not warn about the failed Drive copy: ${it.message}") }
        return Result.failure()
    }

    companion object {
        private const val TAG = "CV:RecordingCopyWorker"
        private const val DEFAULT_MIME = "audio/ogg"
        const val KEY_SRC = "srcUri"
        const val KEY_DEST_FOLDER = "destFolderUri"
        const val KEY_NAME = "displayName"
        const val KEY_MIME = "mimeType"
        const val KEY_DELETE_LOCAL = "deleteLocalAfter"

        /**
         * Total attempts that may keep the local file waiting for a transcription to settle
         * (the copy is idempotent, so each retry re-enters the same check on the 30 s backoff).
         * Sized to [CloudCopyPolicy.MAX_ATTEMPTS] so the two budgets spend down together: the
         * worker's last copy attempt is also its last chance to delete. When the budget is spent
         * the device copy stays for good and the row keeps both copies — the transcription can
         * still run against it.
         */
        private const val DELETE_WAIT_ATTEMPTS = CloudCopyPolicy.MAX_ATTEMPTS
    }
}
