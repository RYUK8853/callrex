/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * Which recordings the sweep's *untracked* pass has just deleted the last copy of, and whose transcript,
 * note and summary must therefore go with them.
 *
 * **Why this exists.** [com.baba.callvault.data.transcripts.TranscriptCascade] is called wherever a
 * catalog row is dropped, which covers every delete the user makes and the catalogued half of the sweep.
 * The untracked half deletes *files* the catalog has no row for, so nothing was ever dropped and nothing
 * ever cascaded — the transcript outlived the recording, searchable. That is reachable rather than
 * theoretical: [com.baba.callvault.data.recordings.db.RecordingDatabase] uses a destructive migration, so
 * one catalog schema bump turns every existing recording untracked while the transcripts database (which
 * migrates properly) keeps every word of them.
 *
 * **Why it is not simply "delete the transcript of whatever we deleted".** Untracked-ness is per folder:
 * a name can be untracked on the device and catalogued on Drive, or untracked in both folders with only
 * one copy old enough to expire. A recording that still has a copy somewhere is not deleted, and taking
 * its transcript would destroy the user's data rather than protect it. So a name is orphaned only when
 * *no* copy of it is left anywhere.
 */
object UntrackedCascade {

    /**
     * The subset of [deleted] that nothing is left of.
     *
     * @param deleted names the untracked pass removed a copy of, across both folders.
     * @param survivingUntracked names still present in either folder without a catalog row — the
     *        counterpart copy of a name whose other copy just expired.
     * @param cataloguedWithCopies names the catalog still holds a device or Drive copy for, read *after*
     *        the catalogued half of the sweep so an entry it dropped does not protect a dead recording.
     */
    fun orphanedNames(
        deleted: Collection<String>,
        survivingUntracked: Collection<String>,
        cataloguedWithCopies: Collection<String>,
    ): Set<String> = deleted.toSet() - survivingUntracked.toSet() - cataloguedWithCopies.toSet()
}
