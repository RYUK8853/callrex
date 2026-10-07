package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seconds → millis, so the fixtures read as wall-clock. */
private fun s(seconds: Long) = seconds * 1_000L

class CallGapDetectorTest {

    private fun sweep(
        entries: List<CallLogEntry>,
        observed: List<Long> = listOf(s(0)),
        incoming: Boolean = true,
        outgoing: Boolean = true,
        watermark: Long = 0L,
        // Existing tests never think about capacity: defaulting it to the ring's own size means
        // that ring is always "at capacity", which reproduces the pre-fix floor (oldest remembered)
        // exactly — so every test written before windowStart existed keeps its old meaning.
        ringCapacity: Int = observed.size.coerceAtLeast(1),
        windowStart: Long = 0L
    ) = CallGapDetector.sweep(entries, observed, incoming, outgoing, watermark, ringCapacity, windowStart)

    @Test
    fun `a call CallVault never observed is a gap`() {
        val call = CallLogEntry(startedAt = s(1_000), durationSeconds = 60, isIncoming = true, label = "Feroza")
        val result = sweep(listOf(call), observed = listOf(s(0)))
        assertEquals(listOf(CallGap(s(1_000), "Feroza")), result.gaps)
    }

    @Test
    fun `a call whose end matches an observed end is not a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 60, isIncoming = true, label = "Feroza")
        val result = sweep(listOf(call), observed = listOf(s(0), s(1_060)))
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun `a match within the ninety-second tolerance still counts as observed`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), observed = listOf(s(0), s(1_060) + 89_000L)).gaps.isEmpty())
        assertTrue(sweep(listOf(call), observed = listOf(s(0), s(1_060) - 89_000L)).gaps.isEmpty())
    }

    @Test
    fun `a match outside the tolerance is a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 60, isIncoming = true, label = null)
        assertEquals(1, sweep(listOf(call), observed = listOf(s(0), s(1_060) + 91_000L)).gaps.size)
    }

    @Test
    fun `an unanswered call is never a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 0, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call)).gaps.isEmpty())
    }

    @Test
    fun `a call shorter than the five-second floor is never a gap`() {
        val call = CallLogEntry(s(1_000), durationSeconds = 4, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call)).gaps.isEmpty())
    }

    @Test
    fun `a direction with auto-record off is never a gap`() {
        val incoming = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        val outgoing = CallLogEntry(s(2_000), 60, isIncoming = false, label = null)
        assertTrue(sweep(listOf(incoming), incoming = false).gaps.isEmpty())
        assertTrue(sweep(listOf(outgoing), outgoing = false).gaps.isEmpty())
    }

    @Test
    fun `entries at or before the watermark are not examined again`() {
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), watermark = s(1_000)).gaps.isEmpty())
    }

    @Test
    fun `calls older than the oldest remembered end are not judged`() {
        // The ring only reaches back to s(5_000); a call before that cannot be matched, and
        // "I cannot remember" must never render as "it failed".
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), observed = listOf(s(5_000))).gaps.isEmpty())
    }

    @Test
    fun `an empty ring judges nothing`() {
        // The first-run path every existing user hits exactly once: no ring yet, so nothing is judged
        // — but the watermark must still advance, or every call log entry is re-examined forever.
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        val result = sweep(listOf(call), observed = emptyList())
        assertTrue(result.gaps.isEmpty())
        assertEquals(s(1_000), result.newWatermark)
    }

    @Test
    fun `the watermark advances to the newest entry seen, gap or not`() {
        val entries = listOf(
            CallLogEntry(s(1_000), 60, isIncoming = true, label = null),
            CallLogEntry(s(3_000), 60, isIncoming = true, label = null)
        )
        assertEquals(s(3_000), sweep(entries, observed = listOf(s(0))).newWatermark)
    }

    @Test
    fun `an empty call log leaves the watermark alone`() {
        assertEquals(s(42), sweep(emptyList(), watermark = s(42)).newWatermark)
    }

    // --- The empty-ring blind spot: a recorder broken since day one must not stay silent forever. ---
    //
    // Each case below is written so that reverting to the pre-fix floor (oldestRemembered, with no
    // concept of windowStart or capacity at all) makes at least one assertion fail — not just so
    // that it happens to produce the same "no gaps" answer either way.

    @Test
    fun `windowStart unset judges nothing, even though the same call is a gap once it is set`() {
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = "Feroza")
        val unset = sweep(listOf(call), observed = emptyList(), ringCapacity = 20, windowStart = 0L)
        val onceSet = sweep(listOf(call), observed = emptyList(), ringCapacity = 20, windowStart = s(500))

        // Under the pre-fix floor (oldestRemembered only), an empty ring always yields no gaps
        // regardless of any windowStart value — so onceSet would wrongly stay empty too if this
        // fix were reverted.
        assertTrue(unset.gaps.isEmpty())
        assertEquals(listOf(CallGap(s(1_000), "Feroza")), onceSet.gaps)
    }

    @Test
    fun `a call before windowStart is never judged, while one at or after it is`() {
        val before = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        val atOrAfter = CallLogEntry(s(3_000), 60, isIncoming = true, label = "Feroza")
        val result = sweep(
            listOf(before, atOrAfter),
            observed = emptyList(),
            ringCapacity = 20,
            windowStart = s(2_000)
        )

        // Exactly one gap: the pre-windowStart call must be excluded (else both would appear), and
        // the post-windowStart call must be included (else the list would be empty) — the pre-fix
        // floor logic, which ignores windowStart entirely on an empty ring, would return no gaps at
        // all here.
        assertEquals(listOf(CallGap(s(3_000), "Feroza")), result.gaps)
    }

    @Test
    fun `a full ring floors at the oldest remembered end, not windowStart`() {
        // Capacity 2, ring holds exactly 2 → at capacity. A call between windowStart (far older) and
        // the oldest remembered end must stay unjudged; a call after the oldest remembered end must
        // still be judged. A bug that let windowStart leak into the at-capacity case would flag both.
        val betweenWindowStartAndOldest = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        val afterOldest = CallLogEntry(s(5_000), 60, isIncoming = true, label = "Feroza")
        val result = sweep(
            listOf(betweenWindowStartAndOldest, afterOldest),
            observed = listOf(s(3_000), s(4_000)),
            ringCapacity = 2,
            windowStart = s(0)
        )
        assertEquals(listOf(CallGap(s(5_000), "Feroza")), result.gaps)
    }

    @Test
    fun `a ring below capacity floors at windowStart, reaching further back than the oldest remembered end`() {
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = "Feroza")
        val result = sweep(
            listOf(call),
            observed = listOf(s(3_000), s(4_000)), // oldest remembered is s(3_000); the call is before that
            ringCapacity = 20, // far below capacity → nothing has been evicted
            windowStart = s(500)
        )
        assertEquals(listOf(CallGap(s(1_000), "Feroza")), result.gaps)
    }
}
