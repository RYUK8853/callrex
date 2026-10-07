/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Removing model files nothing will ever use again.
 *
 * Written because swapping the summariser to a smaller build on 2026-08-27 **stranded 3.46 GB** on the
 * maintainer's phone: `delete` only ever removed files matching a *known* model's name, so the moment a
 * model entry changed its file became invisible to the app and permanently un-deletable from inside it.
 *
 * The danger here is entirely one-sided. Failing to delete an orphan wastes space; deleting the wrong
 * file destroys a 2.6 GB download the user made over Wi-Fi, or corrupts one in progress. So the rule is
 * a **whitelist** — anything the caller names is kept, everything else goes — and every test below is
 * about what must survive.
 */
class ModelPruneTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: Int = 8): File =
        folder.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a file belonging to no known model is removed`() {
        val orphan = file("old-summariser.gguf")

        val freed = ModelRepository.pruneOrphans(folder.root, keep = setOf("current.gguf"))

        assertFalse("the orphan should be gone", orphan.exists())
        assertEquals(8L, freed)
    }

    @Test
    fun `every known model is kept`() {
        val whisper = file("ggml-large-v3-turbo-q8_0.bin")
        val summariser = file("gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf")

        ModelRepository.pruneOrphans(
            folder.root,
            keep = setOf("ggml-large-v3-turbo-q8_0.bin", "gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf"),
        )

        assertTrue("the transcription model must survive", whisper.exists())
        assertTrue("the summariser must survive", summariser.exists())
    }

    @Test
    fun `a download in progress is never touched`() {
        // A .part file belongs to a model that is by definition known — it is being fetched right now.
        // Deleting it would destroy a partial download and silently restart a multi-gigabyte fetch.
        val partial = file("gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf.part")

        ModelRepository.pruneOrphans(folder.root, keep = setOf("gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf"))

        assertTrue("an in-progress download must survive", partial.exists())
    }

    @Test
    fun `an orphaned partial download is removed`() {
        // The other half: a .part left over from a model entry that no longer exists is as dead as the
        // finished file would be.
        val stale = file("some-model-we-dropped.gguf.part")

        ModelRepository.pruneOrphans(folder.root, keep = setOf("current.gguf"))

        assertFalse(stale.exists())
    }

    @Test
    fun `an empty keep set deletes nothing`() {
        // A caller that failed to build its list must not be read as "keep nothing". This is the
        // difference between a bug and a catastrophe.
        val whisper = file("ggml-large-v3-turbo-q8_0.bin")

        val freed = ModelRepository.pruneOrphans(folder.root, keep = emptySet())

        assertTrue("an empty whitelist must be treated as unknown, not as permission", whisper.exists())
        assertEquals(0L, freed)
    }

    @Test
    fun `a directory is never removed`() {
        val sub = folder.newFolder("scratch")

        ModelRepository.pruneOrphans(folder.root, keep = setOf("current.gguf"))

        assertTrue(sub.exists())
    }

    @Test
    fun `a missing directory is not an error`() {
        val gone = File(folder.root, "not-created")

        assertEquals(0L, ModelRepository.pruneOrphans(gone, keep = setOf("current.gguf")))
    }
}
