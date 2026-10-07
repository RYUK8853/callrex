/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a log line may and may not carry once it is safe to paste into a public GitHub issue.
 *
 * The debug report is built to be attached to an issue, and a recording's `displayName` *is* its
 * filename — so every "Transcribed $displayName" line used to publish a contact's name. These tests
 * pin the replacement down on both sides: the name never survives, and everything that is *not* a
 * name (timestamp, direction, the app a VoIP call was made in) does, because a report with no way to
 * tell two calls apart is not worth collecting.
 */
class LogRedactionTest {

    private val salt = "0123456789abcdef0123456789abcdef"
    private val otherSalt = "fedcba9876543210fedcba9876543210"

    private fun redact(msg: String) = LogRedaction.redact(msg, salt)

    // -------- The contact field

    @Test
    fun `a latin contact name is replaced by a token and the rest of the filename survives`() {
        val line = "Transcribed 20260825_130207.420+0300_out_Priya Raman.ogg (14 segment(s))"

        val redacted = redact(line)

        assertFalse(redacted.contains("Priya"))
        assertFalse(redacted.contains("Raman"))
        assertTrue(redacted, redacted.startsWith("Transcribed 20260825_130207.420+0300_out_[C:"))
        assertTrue(redacted, redacted.endsWith(".ogg (14 segment(s))"))
    }

    @Test
    fun `a hebrew contact name with spaces and embedded digits is replaced whole`() {
        // A real name off the maintainer's device. RTL, two spaces, and a "2b" in the middle that a
        // number-shaped rule would be tempted to chew on.
        val line = "20260825_130207.420+0300_out_יצחק 2b לוי.ogg is already transcribed; skipping"

        val redacted = redact(line)

        assertFalse(redacted.contains("יצחק"))
        assertFalse(redacted.contains("לוי"))
        // With its spaces: a bare "2b" would also match the hex of a token that happened to contain it.
        assertFalse(redacted.contains(" 2b "))
        assertEquals(
            "20260825_130207.420+0300_out_${token("יצחק 2b לוי")}.ogg is already transcribed; skipping",
            redacted
        )
    }

    @Test
    fun `a voip filename keeps the app it was made in and loses the caller`() {
        val line = "VoIP recording catalogued: 20260824_112330.671+0300_voip-WhatsApp_Priya Raman.ogg (884736 bytes)"

        val redacted = redact(line)

        assertFalse(redacted.contains("Priya Raman"))
        // The app is not personal data and is the first thing anyone reading a VoIP bug wants to know.
        assertTrue(redacted, redacted.contains("_voip-WhatsApp_"))
        assertTrue(redacted, redacted.contains("20260824_112330.671+0300"))
    }

    @Test
    fun `a voip filename with no caller at all is left alone`() {
        val line = "VoIP recording started -> 20260824_112330.671+0300_voip-Signal.ogg"

        assertEquals(line, redact(line))
    }

    @Test
    fun `a contact name that contains digits is still a name, not a number`() {
        val line = "Could not draw 20260825_130207.420+0300_in_Bob 2 Work.m4a: no such file"

        val redacted = redact(line)

        assertFalse(redacted.contains("Bob"))
        assertEquals("Could not draw 20260825_130207.420+0300_in_${token("Bob 2 Work")}.m4a: no such file", redacted)
    }

    @Test
    fun `a contact name whose first word looks like a direction is not mistaken for one`() {
        // The "{date}_{contact_name}" template has no direction slot, and "info desk" starts with
        // "in". Reading that as a direction marker would leave "fo desk" in the log.
        val line = "20260825_130207.420+0300_info desk.ogg"

        val redacted = redact(line)

        assertFalse(redacted.contains("info"))
        assertFalse(redacted.contains("desk"))
        assertEquals("20260825_130207.420+0300_${token("info desk")}.ogg", redacted)
    }

    // -------- SAF document URIs, which is how most log sites see a recording

    @Test
    fun `a contact name inside a SAF document uri is redacted`() {
        // Fifteen log sites print a document URI rather than a filename. The name is in there with its
        // spaces as %20 and the timestamp's "+" as %2B.
        val line = "Failed to start playback for content://com.android.externalstorage.documents/" +
            "document/primary%3ARecordings%2F20260816_120000.000%2B0300_in_John%20Doe.ogg: boom"

        val redacted = redact(line)

        assertFalse(redacted, redacted.contains("John"))
        assertFalse(redacted, redacted.contains("Doe"))
        assertTrue(redacted, redacted.contains("20260816_120000.000%2B0300_in_"))
        assertTrue(redacted, redacted.endsWith(".ogg: boom"))
    }

    @Test
    fun `the uri form and the filename form of one contact give the same token`() {
        // Otherwise a delete failure and a transcription failure about the same call read as two
        // different people, which is the exact opposite of what a stable token is for.
        val fromName = redact("Transcribed 20260816_120000.000+0300_in_John Doe.ogg")
        val fromUri = redact("Failed to delete content://x/document/y%2F20260816_120000.000%2B0300_in_John%20Doe.ogg")

        assertEquals(tokenIn(fromName), tokenIn(fromUri))
    }

    @Test
    fun `a percent encoded hebrew name decodes to the same token as the plain one`() {
        // Hebrew is a run of multi-byte escapes; decoding them one byte at a time would give a
        // different token for the same person.
        assertEquals(token("יצחק לוי"), token("%D7%99%D7%A6%D7%97%D7%A7%20%D7%9C%D7%95%D7%99"))
    }

    @Test
    fun `a malformed percent escape still yields a token rather than a name`() {
        val redacted = redact("Failed to delete content://x/y%2F20260816_120000.000%2B0300_in_John%2.ogg")

        assertFalse(redacted, redacted.contains("John"))
        assertTrue(redacted, redacted.contains("[C:"))
    }

    // -------- The no-contact case, and the interaction with the phone rule

    @Test
    fun `a call with no contact carries the number instead and it is still redacted as a number`() {
        val line = "Transcribed 20260825_130207.420+0300_out_+972544557278.ogg (14 segment(s))"

        val redacted = redact(line)

        assertFalse(redacted.contains("972544557278"))
        assertFalse(redacted, redacted.contains("[C:"))
        assertEquals("Transcribed 20260825_130207.420+0300_out_[PHONE_REDACTED].ogg (14 segment(s))", redacted)
    }

    @Test
    fun `a local-format number in the contact slot is redacted as a number too`() {
        val redacted = redact("20260825_130207.420+0300_out_0544557278.ogg")

        assertEquals("20260825_130207.420+0300_out_[PHONE_REDACTED].ogg", redacted)
    }

    @Test
    fun `a bare phone number outside a filename is still redacted`() {
        // The old rule has to keep working on everything that is not a filename.
        assertEquals("Incoming call from [PHONE_REDACTED]", redact("Incoming call from +972544557278"))
    }

    @Test
    fun `a number and a filename on the same line are each handled by the right rule`() {
        val line = "Call from +972544557278 recorded as 20260825_130207.420+0300_out_Priya Raman.ogg"

        val redacted = redact(line)

        assertEquals(
            "Call from [PHONE_REDACTED] recorded as 20260825_130207.420+0300_out_${token("Priya Raman")}.ogg",
            redacted
        )
    }

    @Test
    fun `the filename timestamp survives the phone rule`() {
        // Before this change the phone rule ate it — "20260825_130207.420+0300" came out as
        // "[PHONE_REDACTED]_[PHONE_REDACTED]+0300", which left two lines about two different calls
        // indistinguishable except by the contact name they were leaking.
        val redacted = redact("Drew 20260825_130207.420+0300_out_Priya Raman.ogg ahead of time")

        assertTrue(redacted, redacted.contains("20260825_130207.420+0300"))
        assertFalse(redacted, redacted.contains("[PHONE_REDACTED]"))
    }

    @Test
    fun `two filenames on one line are both redacted`() {
        val line = "Replacing 20260825_130207.420+0300_out_Ann.ogg with 20260826_090000.000+0300_in_Bob.ogg"

        val redacted = redact(line)

        assertFalse(redacted.contains("Ann"))
        assertFalse(redacted.contains("Bob"))
        assertEquals(
            "Replacing 20260825_130207.420+0300_out_${token("Ann")}.ogg " +
                "with 20260826_090000.000+0300_in_${token("Bob")}.ogg",
            redacted
        )
    }

    // -------- Stability, which is the whole point of a token rather than a blank

    @Test
    fun `the same contact yields the same token every time`() {
        val first = redact("Transcribed 20260825_130207.420+0300_out_Priya Raman.ogg")
        val second = redact("Could not draw 20260826_090000.000+0300_in_Priya Raman.m4a")

        assertEquals(token("Priya Raman"), tokenIn(first))
        assertEquals(tokenIn(first), tokenIn(second))
    }

    @Test
    fun `different contacts yield different tokens`() {
        assertNotEquals(token("Priya Raman"), token("Ann Kelly"))
    }

    @Test
    fun `case and surrounding whitespace do not split one person into two tokens`() {
        assertEquals(token("Priya Raman"), token("  priya raman "))
    }

    @Test
    fun `a different install salt yields a different token for the same contact`() {
        // Without this, "Mum" hashes to the same value on every phone on earth and a public tracker
        // full of reports becomes one big rainbow table.
        assertNotEquals(LogRedaction.contactToken("Mum", salt), LogRedaction.contactToken("Mum", otherSalt))
    }

    @Test
    fun `a token is short and readable`() {
        val t = token("Priya Raman")

        assertTrue(t, Regex("\\[C:[0-9a-f]{4}]").matches(t))
    }

    // -------- Idempotence

    @Test
    fun `redacting an already redacted line does not move the token`() {
        // This happens on every report: the daemon redacts its own lines, the app redacts them again
        // when it drains them, and logcat lines were already redacted when the app wrote them. A second
        // token for the same contact in one report would destroy the correlation the token exists for.
        val once = redact("Transcribed 20260825_130207.420+0300_out_Priya Raman.ogg")

        assertEquals(once, redact(once))
        assertEquals(once, redact(redact(once)))
    }

    @Test
    fun `redacting an already redacted number in a filename does not turn it into a contact`() {
        val once = redact("Transcribed 20260825_130207.420+0300_out_+972544557278.ogg")

        assertEquals(once, redact(once))
        assertFalse(redact(once).contains("[C:"))
    }

    // -------- Lines that are not filenames

    @Test
    fun `an ordinary line without an underscore is untouched`() {
        val line = "Daemon connected; recorder backend is STANDALONE"

        assertEquals(line, redact(line))
    }

    @Test
    fun `a line with an underscore but no filename is untouched`() {
        val line = "Preference AUDIO_BIT_RATE changed to 24000 bps"

        assertEquals(line, redact(line))
    }

    private fun token(name: String) = LogRedaction.contactToken(name, salt)

    private fun tokenIn(line: String) = Regex("\\[C:[0-9a-f]+]").find(line)?.value

    // --- Measurements are not people ----------------------------------------------------------

    /**
     * The regression that prompted these: this line exists only to report how much audio was
     * captured, and the count was the whole content of it. It had been redacted away for months,
     * invisibly, because the number is a bare seven-digit run exactly like a mobile number.
     */
    @Test
    fun `a frame count is not a phone number`() {
        assertEquals(
            "HandoffEncoder finished: 4685000 frames (97.64s)",
            redact("HandoffEncoder finished: 4685000 frames (97.64s)")
        )
    }

    @Test
    fun `the other units we log survive too`() {
        assertEquals("Decoded 4685000 samples", redact("Decoded 4685000 samples"))
        assertEquals("Wrote 1234567 bytes", redact("Wrote 1234567 bytes"))
        assertEquals("Elapsed 1234567 ms", redact("Elapsed 1234567 ms"))
    }

    /** The exception is an exception to a privacy rule, so it must not become a way to leak one. */
    @Test
    fun `a real number is still redacted when a unit word merely appears later`() {
        assertEquals(
            "Called [PHONE_REDACTED] and it took 97 ms",
            redact("Called 0544557278 and it took 97 ms")
        )
    }

    @Test
    fun `a bare number with no unit is still redacted`() {
        assertEquals("Number: [PHONE_REDACTED]", redact("Number: 0544557278"))
    }
}
