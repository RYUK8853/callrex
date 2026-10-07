/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Choosing a codec has to bring a workable bit rate with it, wherever it is chosen from — issue #28c.
 *
 * The reporter switched from Opus to AAC and the next call recorded nothing. Settings already adopted
 * the new codec's recommended rate; the onboarding wizard did not, so a codec picked there kept Opus's
 * 24 kbps, and AAC-LC at 48 kHz mono / 24 kbps is where hardware encoders start refusing to configure.
 * One rule, one place, both callers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioCodecChoiceTest {

    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `switching codec adopts the new codec's recommended bit rate`() {
        prefs.setAudioCodec(ScrcpyAudioCodec.OPUS.cliKey)
        prefs.setAudioBitRate(ScrcpyAudioCodec.OPUS.defaultBitRate)

        prefs.chooseAudioCodec(ScrcpyAudioCodec.AAC.cliKey)

        assertEquals(ScrcpyAudioCodec.AAC.cliKey, prefs.getAudioCodec())
        assertEquals(ScrcpyAudioCodec.AAC.defaultBitRate, prefs.getAudioBitRate())
    }

    @Test
    fun `re-picking the codec already in use leaves a deliberately chosen bit rate alone`() {
        prefs.setAudioCodec(ScrcpyAudioCodec.OPUS.cliKey)
        prefs.setAudioBitRate(64_000)

        prefs.chooseAudioCodec(ScrcpyAudioCodec.OPUS.cliKey)

        assertEquals(64_000, prefs.getAudioBitRate())
    }

    @Test
    fun `an unknown codec key changes nothing`() {
        prefs.setAudioCodec(ScrcpyAudioCodec.OPUS.cliKey)
        prefs.setAudioBitRate(64_000)

        prefs.chooseAudioCodec("flac-from-the-future")

        assertEquals(ScrcpyAudioCodec.OPUS.cliKey, prefs.getAudioCodec())
        assertEquals(64_000, prefs.getAudioBitRate())
    }
}
