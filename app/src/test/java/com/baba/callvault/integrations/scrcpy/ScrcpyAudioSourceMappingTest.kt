/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.scrcpy

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the cliKey → AudioSource map that decides whether a source can be captured directly (daemon
 * recorder or handoff) or must go through scrcpy.
 *
 * The map used to be duplicated, and the copy in the direct recorder keyed VOICE_PERFORMANCE off a
 * cliKey that does not exist — so the source silently fell back to scrcpy. These tests pin the keys to
 * the enum itself so a typo cannot come back unnoticed.
 */
class ScrcpyAudioSourceMappingTest {

    @Test
    fun `voice call maps to the privileged call source`() {
        assertEquals(MediaRecorder.AudioSource.VOICE_CALL, ScrcpyAudioSource.VOICE_CALL.androidAudioSource)
    }

    @Test
    fun `every mapped key belongs to a real source`() {
        // Arrange: the keys the mapping claims to support.
        val mappedKeys = ScrcpyAudioSource.entries.filter { it.androidAudioSource != null }.map { it.cliKey }

        // Assert: the map is keyed off cliKey strings, so a stale key would silently map nothing.
        assertTrue("expected several directly-capturable sources", mappedKeys.size >= 5)
        mappedKeys.forEach { key ->
            assertNotNull("'$key' must resolve back to a source", androidAudioSourceForKey(key))
        }
    }

    @Test
    fun `voice performance is directly capturable`() {
        // The regression: this mapped off "mic-voice-performance", which is not a cliKey.
        assertEquals(
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            ScrcpyAudioSource.VOICE_PERFORMANCE.androidAudioSource,
        )
        assertEquals(
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            androidAudioSourceForKey("voice-performance"),
        )
    }

    @Test
    fun `playback capture sources have no direct AudioRecord source`() {
        // output/playback are scrcpy-only — an AudioRecord cannot open them.
        assertNull(ScrcpyAudioSource.OUTPUT.androidAudioSource)
        assertNull(ScrcpyAudioSource.PLAYBACK.androidAudioSource)
    }

    @Test
    fun `an unknown key resolves to no source rather than throwing`() {
        assertNull(androidAudioSourceForKey("not-a-source"))
        assertNull(androidAudioSourceForKey(""))
    }

    @Test
    fun `key lookup agrees with the enum property for every source`() {
        ScrcpyAudioSource.entries.forEach { source ->
            assertEquals(
                "lookup by cliKey must match the enum for ${source.cliKey}",
                source.androidAudioSource,
                androidAudioSourceForKey(source.cliKey),
            )
        }
    }
}
