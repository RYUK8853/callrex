/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.core.net.toUri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 2026-10-08 field bug, made checkable: the cloud-backup folder was set to the recordings
 * folder itself, so every "copy to Drive" found the file in its own folder ("already in Drive")
 * and, in cloud-only mode, deleted the only copy of the call. [SafHelper.isDocumentInTree] and
 * [SafHelper.isSameFolder] are the two predicates the fix builds on — one for the runtime backstop
 * (a source document vs the configured tree), one for the picker-level refusal (tree vs tree) —
 * and both must say YES on the field phone's real URIs and NO on a healthy cloud backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafHelperSameFolderTest {

    private val recordingsTree =
        "content://com.android.externalstorage.documents/tree/primary%3ARecordings".toUri()
    private val aDocumentInIt =
        "content://com.android.externalstorage.documents/document/primary%3ARecordings%2F20261008_205952.677%2B0530.m4a".toUri()
    private val driveTree =
        "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3Dbackup".toUri()

    // --- isDocumentInTree: a recording file vs the tree its "Drive" copy points at -------------

    @Test
    fun `a file in the recordings tree is detected inside it, which is the field-bug shape`() {
        assertTrue(SafHelper.isDocumentInTree(aDocumentInIt, recordingsTree))
    }

    @Test
    fun `a file from another tree is NOT in the recordings tree`() {
        val otherTreeDoc =
            "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fother.m4a".toUri()
        assertFalse(SafHelper.isDocumentInTree(otherTreeDoc, recordingsTree))
    }

    @Test
    fun `a cloud-provider document is not claimed by the local tree`() {
        val driveDoc =
            "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bfile%3Ddeadbeef".toUri()
        assertFalse(SafHelper.isDocumentInTree(driveDoc, recordingsTree))
    }

    @Test
    fun `a null tree never claims a document`() {
        assertFalse(SafHelper.isDocumentInTree(aDocumentInIt, null))
    }

    // --- isSameFolder: tree vs tree, the picker-level guard -------------------------------------

    @Test
    fun `the recordings tree IS the same folder as itself, percent-encoding apart`() {
        // The same tree re-addressed with a different percent-encoding is still the same folder:
        // comparison must run on the decoded document id, not the URI string.
        val reEncoded =
            "content://com.android.externalstorage.documents/tree/primary:Recordings".toUri()
        assertTrue(SafHelper.isSameFolder(recordingsTree, reEncoded))
        assertTrue(SafHelper.isSameFolder(reEncoded, recordingsTree))
    }

    @Test
    fun `a real cloud folder is a different folder from the recordings folder`() {
        assertFalse(SafHelper.isSameFolder(recordingsTree, driveTree))
        assertFalse(SafHelper.isSameFolder(driveTree, recordingsTree))
    }

    @Test
    fun `two different on-device folders are different folders`() {
        val documentsTree =
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments".toUri()
        assertFalse(SafHelper.isSameFolder(recordingsTree, documentsTree))
    }

    @Test
    fun `a null folder is never the same folder`() {
        assertFalse(SafHelper.isSameFolder(recordingsTree, null))
        assertFalse(SafHelper.isSameFolder(null, recordingsTree))
        assertFalse(SafHelper.isSameFolder(null, null))
    }
}
