package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupHealthTest {

    private val verifiedFacts = HealthFacts(lastVerifiedAt = 5_000L, verifiedFingerprint = "fp-1")

    @Test
    fun `nothing observed yet is unverified`() {
        assertEquals(SetupHealth.Unverified, SetupHealthDeriver.derive(HealthFacts(), "fp-1"))
    }

    @Test
    fun `a verified call under an unchanged fingerprint is verified`() {
        assertEquals(SetupHealth.Verified(5_000L), SetupHealthDeriver.derive(verifiedFacts, "fp-1"))
    }

    @Test
    fun `a changed fingerprint makes an earlier verification stale`() {
        assertEquals(SetupHealth.StaleAfterChange, SetupHealthDeriver.derive(verifiedFacts, "fp-2"))
    }

    @Test
    fun `an unobserved call outranks everything else`() {
        val facts = verifiedFacts.copy(
            lastFailureAt = 6_000L,
            lastFailureReason = FailureReason.EMPTY_FILE,
            lastGapAt = 9_000L,
            lastGapLabel = "Feroza"
        )
        assertEquals(SetupHealth.CallNotRecorded(9_000L, "Feroza"), SetupHealthDeriver.derive(facts, "fp-2"))
    }

    @Test
    fun `a persisted gap surfaces even with no failure on record`() {
        // This is the C1 regression case: the gap must be read back from HealthFacts, not passed in
        // as a transient "this pass" result, or it vanishes the moment the sweep's watermark moves on.
        val facts = HealthFacts(lastGapAt = 9_000L, lastGapLabel = "Feroza")
        assertEquals(SetupHealth.CallNotRecorded(9_000L, "Feroza"), SetupHealthDeriver.derive(facts, "fp-1"))
    }

    @Test
    fun `a gap at or before the last verification stays buried`() {
        // A later verified call proved things work again — the earlier gap is spent and must not
        // resurface, exactly like a stale failure.
        val facts = verifiedFacts.copy(lastGapAt = 5_000L, lastGapLabel = "Feroza")
        assertEquals(SetupHealth.Verified(5_000L), SetupHealthDeriver.derive(facts, "fp-1"))
    }

    @Test
    fun `a call missed while a prerequisite was absent surfaces as CallMissedNotReady, not the generic gap`() {
        val facts = HealthFacts(
            lastNotReadyAt = 9_000L,
            lastNotReadyLabel = "Feroza",
            lastNotReadyPrerequisite = Prerequisite.DEVELOPER_OPTIONS
        )
        assertEquals(
            SetupHealth.CallMissedNotReady(9_000L, "Feroza", Prerequisite.DEVELOPER_OPTIONS),
            SetupHealthDeriver.derive(facts, "fp-1")
        )
    }

    @Test
    fun `a missed-while-not-ready entry at or before the last verification stays buried`() {
        val facts = verifiedFacts.copy(
            lastNotReadyAt = 5_000L,
            lastNotReadyLabel = "Feroza",
            lastNotReadyPrerequisite = Prerequisite.RECORDING_FOLDER
        )
        assertEquals(SetupHealth.Verified(5_000L), SetupHealthDeriver.derive(facts, "fp-1"))
    }

    @Test
    fun `whichever of the gap and the missed-while-not-ready entry is newer wins`() {
        val notReadyNewer = HealthFacts(
            lastGapAt = 6_000L, lastGapLabel = "Older",
            lastNotReadyAt = 9_000L, lastNotReadyLabel = "Newer", lastNotReadyPrerequisite = Prerequisite.ADB_PAIRING
        )
        assertEquals(
            SetupHealth.CallMissedNotReady(9_000L, "Newer", Prerequisite.ADB_PAIRING),
            SetupHealthDeriver.derive(notReadyNewer, "fp-1")
        )

        val gapNewer = HealthFacts(
            lastGapAt = 9_000L, lastGapLabel = "Newer",
            lastNotReadyAt = 6_000L, lastNotReadyLabel = "Older", lastNotReadyPrerequisite = Prerequisite.ADB_PAIRING
        )
        assertEquals(SetupHealth.CallNotRecorded(9_000L, "Newer"), SetupHealthDeriver.derive(gapNewer, "fp-1"))
    }

    @Test
    fun `a failure outranks a setup change`() {
        val facts = verifiedFacts.copy(lastFailureAt = 6_000L, lastFailureReason = FailureReason.DAEMON_DIED)
        assertEquals(
            SetupHealth.LastCallFailed(6_000L, FailureReason.DAEMON_DIED),
            SetupHealthDeriver.derive(facts, "fp-2")
        )
    }

    @Test
    fun `a later verified call clears an older failure`() {
        // recordVerified() already removes the failure keys; this guards the derive step too.
        val facts = verifiedFacts.copy(lastVerifiedAt = 8_000L, lastFailureAt = 6_000L, lastFailureReason = FailureReason.EMPTY_FILE)
        assertEquals(SetupHealth.Verified(8_000L), SetupHealthDeriver.derive(facts, "fp-1"))
    }

    @Test
    fun `only a failure or an unobserved call are problems`() {
        assertFalse(SetupHealth.Verified(1L).isProblem)
        assertFalse(SetupHealth.Unverified.isProblem)
        // A setup change is informational, not a fault — see the isProblem doc comment.
        assertFalse(SetupHealth.StaleAfterChange.isProblem)
        assertTrue(SetupHealth.LastCallFailed(1L, FailureReason.EMPTY_FILE).isProblem)
        assertTrue(SetupHealth.CallNotRecorded(1L, null).isProblem)
        assertTrue(SetupHealth.CallMissedNotReady(1L, null, Prerequisite.RECORDING_FOLDER).isProblem)
    }
}
