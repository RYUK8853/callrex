/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Nothing checked that the chosen encoder could accept the requested bit rate. `MediaCodec` given an
 * out-of-range value does not reliably throw — it can clamp, or emit frames that decode to nothing,
 * which is a correctly-sized file that plays silent.
 *
 * The ranges below are real, read off a OnePlus 12 on 2026-07-31:
 *  - `c2.android.aac.encoder`  (software) 8000-960000
 *  - `c2.qti.aac.hw.encoder`   (hardware) 4000-192000
 */
class EncoderLimitsTest {

    @Test
    fun `leaves a supported bit rate alone`() {
        assertEquals(24_000, EncoderLimits.clampBitRate(24_000, 8_000, 960_000))
    }

    @Test
    fun `raises a bit rate below the encoder's minimum`() {
        // 8 kbps against a hardware encoder that will not go under 16 kbps.
        assertEquals(16_000, EncoderLimits.clampBitRate(8_000, 16_000, 192_000))
    }

    @Test
    fun `lowers a bit rate above the encoder's maximum`() {
        assertEquals(192_000, EncoderLimits.clampBitRate(320_000, 4_000, 192_000))
    }

    @Test
    fun `accepts the exact boundaries`() {
        assertEquals(8_000, EncoderLimits.clampBitRate(8_000, 8_000, 960_000))
        assertEquals(960_000, EncoderLimits.clampBitRate(960_000, 8_000, 960_000))
    }

    @Test
    fun `passes the request through when the range is unknown`() {
        // An encoder that reports nothing usable must not cause us to invent a limit — the request
        // stands, exactly as it did before this check existed.
        assertEquals(24_000, EncoderLimits.clampBitRate(24_000, null, null))
        assertEquals(24_000, EncoderLimits.clampBitRate(24_000, 0, 0))
    }

    @Test
    fun `an inverted range is ignored rather than obeyed`() {
        // Defensive: a malformed capability report should not silently rewrite the user's setting.
        assertEquals(24_000, EncoderLimits.clampBitRate(24_000, 96_000, 8_000))
    }
}
