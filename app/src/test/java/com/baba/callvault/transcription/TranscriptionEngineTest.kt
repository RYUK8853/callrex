/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionEngineTest {

    @Test
    fun `uses every available core`() {
        // Measured on the OP12 (SM8650: 6 performance + 2 efficiency cores) with
        // large-v3-turbo-q5_0 -- 4 threads 140.4 s, 6 threads 99.2 s, 8 threads 95.9 s.
        // All-cores won, so the "prefer performance cores" heuristic this was originally
        // designed with was rejected on evidence. Do not reinstate it without new numbers.
        assertEquals(6, TranscriptionEngine.threadCountFor(availableProcessors = 8))
    }

    @Test
    fun `never returns fewer than one thread`() {
        assertEquals(1, TranscriptionEngine.threadCountFor(availableProcessors = 1))
        assertEquals(2, TranscriptionEngine.threadCountFor(availableProcessors = 4))
        // Capped: a 12-core desktop-class chip still gets six, not ten.
        assertEquals(6, TranscriptionEngine.threadCountFor(availableProcessors = 12))
    }

    @Test
    fun `a nonsensical processor count still yields a usable thread count`() {
        // availableProcessors() is documented as able to change between calls, and whisper.cpp
        // would divide by whatever it is handed. Zero threads must never reach the native layer.
        assertEquals(1, TranscriptionEngine.threadCountFor(availableProcessors = 0))
        assertEquals(1, TranscriptionEngine.threadCountFor(availableProcessors = -4))
    }
}
