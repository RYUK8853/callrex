/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.export

/**
 * The shapes a transcript can leave the app in.
 *
 * Five rather than one because the reasons people take a transcript out are genuinely different, and
 * a single format serves none of them well: [TXT] to paste into a message, [MARKDOWN] to keep in
 * notes, [SRT] and [VTT] to lay over the audio in a player or editor, [JSON] to process.
 *
 * **The labels are deliberately not translated.** They name file formats — `SRT`, `VTT`, `JSON` are
 * the same word everywhere, and `TXT` is shown rather than "plain text" so the whole menu reads as a
 * list of extensions rather than a mix of a translated phrase and four acronyms. That also keeps this
 * enum free of Android resources, which is what lets the renderer be a pure function.
 *
 * @param extension file extension, without the dot.
 * @param mimeType what the share-sheet is told, so the chooser offers apps that can actually open it.
 */
enum class TranscriptFormat(
    val extension: String,
    val mimeType: String,
    val label: String
) {
    /** The transcript as it reads on screen: `[m:ss] Speaker: words`. */
    TXT("txt", "text/plain", "TXT"),

    /** Adds the summary and the note, under headings, for somewhere that renders Markdown. */
    MARKDOWN("md", "text/markdown", "Markdown"),

    /** Subtitles, for a player or a video editor. */
    SRT("srt", "application/x-subrip", "SRT"),

    /** Subtitles again, in the format the web uses. */
    VTT("vtt", "text/vtt", "VTT"),

    /** Everything, including the timings and speaker keys, for anything that wants to process it. */
    JSON("json", "application/json", "JSON")
}
