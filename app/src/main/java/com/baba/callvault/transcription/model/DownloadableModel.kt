/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

/**
 * A model file the app fetches, verifies and keeps on disk.
 *
 * Extracted so [ModelRepository] and `ModelDownloadWorker` can serve summarisation as well as
 * transcription. What the two kinds of model have in common is exactly this and nothing more — a
 * name, somewhere to get it, and the two numbers that say whether what arrived is what was asked
 * for. Everything else about them differs: whisper models are described by a real-time factor
 * against audio, a language model by memory and tokens, and forcing both into one enum would give
 * every model a set of fields that are meaningless for half of them.
 *
 * **The package is narrower than the contents, knowingly.** The download machinery grew up beside
 * whisper and still lives here. Moving it to a neutral home would touch seven files for a naming
 * improvement and no behaviour change; it is worth doing on its own one day, not folded into a
 * feature.
 *
 * [sha256] and [sizeBytes] are load-bearing rather than decorative. ggml's failure mode on a
 * truncated or corrupt model is a crash in native code with nothing useful in the log, so a file is
 * only ever used once its digest matches.
 */
interface DownloadableModel {

    /** Stable key, stored alongside whatever the model produced so a re-run is distinguishable. */
    val id: String

    /** Name on disk. */
    val fileName: String

    /** Direct download URL. */
    val url: String

    /** Lower-case hex SHA-256 of the complete file. */
    val sha256: String

    /** Exact published length, used as the cheap completeness check. */
    val sizeBytes: Long
}
