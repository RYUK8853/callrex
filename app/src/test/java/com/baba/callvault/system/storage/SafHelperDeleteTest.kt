/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [SafHelper.deleteDocument] — the guard against the silent half of `DocumentFile.delete()`,
 * which reports failure by RETURNING FALSE rather than throwing. Wrapping it in `runCatching {}` alone
 * therefore swallows a refused delete: that is how 114 zero-byte recordings accumulated unnoticed in a
 * user's folder, each one a cleanup that never happened and never said so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafHelperDeleteTest {

    @Test
    fun `a successful delete is reported as done`() {
        val doc = mockk<DocumentFile> { every { delete() } returns true }
        assertTrue(SafHelper.deleteDocument(doc, "an empty recording"))
    }

    @Test
    fun `a refused delete is reported as failed rather than swallowed`() {
        val doc = mockk<DocumentFile> { every { delete() } returns false }
        assertFalse(SafHelper.deleteDocument(doc, "an empty recording"))
    }

    @Test
    fun `a delete that throws is reported as failed`() {
        val doc = mockk<DocumentFile> { every { delete() } throws UnsupportedOperationException("nope") }
        assertFalse(SafHelper.deleteDocument(doc, "an empty recording"))
    }

    @Test
    fun `nothing to delete is not a failure`() {
        assertTrue(SafHelper.deleteDocument(null, "a document that was never created"))
    }
}
