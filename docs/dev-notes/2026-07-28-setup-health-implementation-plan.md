# Setup Health in the Status Card — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Home status card report whether recording actually works, based on what real calls proved — with no button, no synthetic test, and no mic access outside a real call.

**Architecture:** Every call CallVault handles writes an outcome (verified, or a failure with a reason) to a small preference-backed store. A call-log sweep on Home refresh catches calls CallVault never observed at all — the daemon-dead case that produces no event. The card derives its state from those two sources plus a fingerprint of user-owned setup, and shows a warning line in place of the normal suggestion when something is wrong.

**Tech Stack:** Kotlin, AndroidX SharedPreferences, Jetpack Compose, JUnit4 + MockK + Robolectric 4.14 (`@Config(sdk = [35])`).

**Spec:** `docs/dev-notes/2026-07-28-setup-health-status-design.md`

## Global Constraints

- **Branch:** `feat/roadmap-v1`. Do not bump `ciVersionCode`/`ciVersionName` — the release commit does that.
- **Build/test:** `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest` and `:app:compileReleaseKotlin`. There is no JDK on `PATH`; the `JAVA_HOME` prefix is required on every Gradle command.
- **Copy rules (from the spec, verbatim):** the happy path stays plain — no "audio present", no byte counts, no step-by-step detail. A size appears only when it is zero, and only as a warning.
- **Honesty rule:** a check that could not run must never render as a failure. Every fallback path claims nothing.
- **Strings:** every new user-facing string goes in `app/src/main/res/values/strings_home.xml` **and** `app/src/main/res/values-fr/strings_home.xml`. The other eight locales are left to the existing translation pass.
- **Licence header:** every new `.kt` file starts with the 7-line GPL header copied verbatim from `app/src/main/java/com/baba/callvault/system/storage/SafHelper.kt`.
- **Immutability:** data classes + `copy()`. No mutable shared state.
- **Deferred to the follow-on plan:** the `SILENT` failure reason for carrier calls (needs `DirectAudioRecorderSession` PCM peak detection + a new `IRecorderService` AIDL method). Do not add a `SILENT` enum constant in this plan — it would be dead code.

---

### Task 1: SetupHealthStore and the setup fingerprint

**Files:**
- Create: `app/src/main/java/com/baba/callvault/data/health/SetupHealthStore.kt`
- Create: `app/src/main/java/com/baba/callvault/data/health/SetupFingerprint.kt`
- Test: `app/src/test/java/com/baba/callvault/data/health/SetupHealthStoreTest.kt`

**Interfaces:**
- Consumes: `AppPreferences` (existing, for the fingerprint inputs).
- Produces:
  - `enum class FailureReason { EMPTY_FILE, DAEMON_DIED, ONE_SIDED }`
  - `data class HealthFacts(val lastVerifiedAt: Long, val verifiedFingerprint: String?, val lastFailureAt: Long, val lastFailureReason: FailureReason?, val lastFailureLabel: String?, val sweepWatermark: Long, val observedCallEnds: List<Long>)`
  - `class SetupHealthStore(context: Context)` with `read(): HealthFacts`, `recordVerified(atMillis: Long, fingerprint: String)`, `recordFailure(atMillis: Long, reason: FailureReason, label: String?)`, `observeCall(endedAtMillis: Long)`, `setSweepWatermark(millis: Long)`
  - `object SetupFingerprint { fun of(prefs: AppPreferences): String }`

**Why its own preference file:** `AppPreferences` is already the largest class in the app and `docs/dev-notes/backlog.md` records the agreed intent to split it per domain. A new domain gets its own store rather than adding seven more keys to the pile.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.baba.callvault.data.health

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupHealthStoreTest {

    private lateinit var store: SetupHealthStore

    @Before
    fun setUp() {
        store = SetupHealthStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a fresh install has never been verified and has no failure`() {
        val facts = store.read()
        assertEquals(0L, facts.lastVerifiedAt)
        assertNull(facts.verifiedFingerprint)
        assertNull(facts.lastFailureReason)
        assertEquals(emptyList<Long>(), facts.observedCallEnds)
    }

    @Test
    fun `recording a verified call clears an earlier failure`() {
        store.recordFailure(1_000L, FailureReason.EMPTY_FILE, "Feroza")
        store.recordVerified(2_000L, "fp-1")

        val facts = store.read()
        assertEquals(2_000L, facts.lastVerifiedAt)
        assertEquals("fp-1", facts.verifiedFingerprint)
        assertNull(facts.lastFailureReason)
        assertNull(facts.lastFailureLabel)
        assertEquals(0L, facts.lastFailureAt)
    }

    @Test
    fun `recording a failure keeps the earlier verification date`() {
        store.recordVerified(2_000L, "fp-1")
        store.recordFailure(3_000L, FailureReason.DAEMON_DIED, null)

        val facts = store.read()
        assertEquals(2_000L, facts.lastVerifiedAt)
        assertEquals(3_000L, facts.lastFailureAt)
        assertEquals(FailureReason.DAEMON_DIED, facts.lastFailureReason)
    }

    @Test
    fun `the observed-call ring keeps the twenty newest ends, newest first`() {
        (1L..25L).forEach { store.observeCall(it * 100L) }

        val ends = store.read().observedCallEnds
        assertEquals(20, ends.size)
        assertEquals(2_500L, ends.first())
        assertEquals(600L, ends.last())
    }

    @Test
    fun `observing the same call end twice does not consume two ring slots`() {
        store.observeCall(500L)
        store.observeCall(500L)
        assertEquals(listOf(500L), store.read().observedCallEnds)
    }

    @Test
    fun `the sweep watermark round-trips`() {
        store.setSweepWatermark(9_999L)
        assertEquals(9_999L, store.read().sweepWatermark)
    }

    @Test
    fun `an unknown persisted failure reason reads back as no failure`() {
        store.recordFailure(1_000L, FailureReason.ONE_SIDED, "x")
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("cv_setup_health", android.content.Context.MODE_PRIVATE)
            .edit().putString("last_failure_reason", "FROM_A_FUTURE_VERSION").commit()

        assertNull(store.read().lastFailureReason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*SetupHealthStoreTest*"`
Expected: FAIL — `Unresolved reference 'SetupHealthStore'`

- [ ] **Step 3: Write the store**

```kotlin
package com.baba.callvault.data.health

import android.content.Context
import androidx.core.content.edit

/** Why a call failed to produce a usable recording. Persisted by name; unknown names read as null. */
enum class FailureReason { EMPTY_FILE, DAEMON_DIED, ONE_SIDED }

/**
 * Everything the status card knows about whether recording actually works. All timestamps are epoch
 * millis; 0 means "never".
 */
data class HealthFacts(
    val lastVerifiedAt: Long = 0L,
    val verifiedFingerprint: String? = null,
    val lastFailureAt: Long = 0L,
    val lastFailureReason: FailureReason? = null,
    val lastFailureLabel: String? = null,
    val sweepWatermark: Long = 0L,
    val observedCallEnds: List<Long> = emptyList()
)

/**
 * Persists what real calls proved. Its own preference file rather than more keys on AppPreferences,
 * which is already oversized (see the agreed split in docs/dev-notes/backlog.md).
 *
 * [observeCall] records that a call was seen AT ALL, whatever the outcome — it answers the sweep's
 * "did we observe this call", never "did it work". A call that failed loudly is still one the sweep
 * must not report as unseen.
 */
class SetupHealthStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): HealthFacts = HealthFacts(
        lastVerifiedAt = prefs.getLong(KEY_VERIFIED_AT, 0L),
        verifiedFingerprint = prefs.getString(KEY_VERIFIED_FINGERPRINT, null),
        lastFailureAt = prefs.getLong(KEY_FAILURE_AT, 0L),
        lastFailureReason = prefs.getString(KEY_FAILURE_REASON, null)?.let { name ->
            FailureReason.entries.firstOrNull { it.name == name }
        },
        lastFailureLabel = prefs.getString(KEY_FAILURE_LABEL, null),
        sweepWatermark = prefs.getLong(KEY_SWEEP_WATERMARK, 0L),
        observedCallEnds = readRing()
    )

    /** A call produced a usable recording: the setup is proven as of [atMillis], clearing any failure. */
    fun recordVerified(atMillis: Long, fingerprint: String) = prefs.edit {
        putLong(KEY_VERIFIED_AT, atMillis)
        putString(KEY_VERIFIED_FINGERPRINT, fingerprint)
        remove(KEY_FAILURE_AT); remove(KEY_FAILURE_REASON); remove(KEY_FAILURE_LABEL)
    }

    /** A call was handled but produced nothing usable. The last verification date is left intact. */
    fun recordFailure(atMillis: Long, reason: FailureReason, label: String?) = prefs.edit {
        putLong(KEY_FAILURE_AT, atMillis)
        putString(KEY_FAILURE_REASON, reason.name)
        putString(KEY_FAILURE_LABEL, label)
    }

    /** Remembers that a call ended at [endedAtMillis], whatever came of it. Keeps the newest [RING_SIZE]. */
    fun observeCall(endedAtMillis: Long) {
        val ends = (listOf(endedAtMillis) + readRing()).distinct().sortedDescending().take(RING_SIZE)
        prefs.edit { putString(KEY_OBSERVED_ENDS, ends.joinToString(",")) }
    }

    fun setSweepWatermark(millis: Long) = prefs.edit { putLong(KEY_SWEEP_WATERMARK, millis) }

    private fun readRing(): List<Long> =
        prefs.getString(KEY_OBSERVED_ENDS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.sortedDescending()
            .orEmpty()

    private companion object {
        const val FILE_NAME = "cv_setup_health"
        const val RING_SIZE = 20
        const val KEY_VERIFIED_AT = "last_verified_at"
        const val KEY_VERIFIED_FINGERPRINT = "verified_fingerprint"
        const val KEY_FAILURE_AT = "last_failure_at"
        const val KEY_FAILURE_REASON = "last_failure_reason"
        const val KEY_FAILURE_LABEL = "last_failure_label"
        const val KEY_SWEEP_WATERMARK = "sweep_watermark"
        const val KEY_OBSERVED_ENDS = "observed_call_ends"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*SetupHealthStoreTest*"`
Expected: PASS (7 tests)

- [ ] **Step 5: Write the failing fingerprint test**

Append to `SetupHealthStoreTest.kt` — a second class in the same file is fine, or create `SetupFingerprintTest.kt` with the same header/annotations:

```kotlin
package com.baba.callvault.data.health

import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupFingerprintTest {

    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `the same setup always hashes the same`() {
        prefs.setRecordingFolderUri("content://tree/a".toUri())
        assertEquals(SetupFingerprint.of(prefs), SetupFingerprint.of(prefs))
    }

    @Test
    fun `changing the recording folder changes the fingerprint`() {
        prefs.setRecordingFolderUri("content://tree/a".toUri())
        val before = SetupFingerprint.of(prefs)
        prefs.setRecordingFolderUri("content://tree/b".toUri())
        assertNotEquals(before, SetupFingerprint.of(prefs))
    }

    @Test
    fun `changing the storage target changes the fingerprint`() {
        prefs.setStorageTarget(StorageTarget.LOCAL)
        val before = SetupFingerprint.of(prefs)
        prefs.setStorageTarget(StorageTarget.BOTH)
        assertNotEquals(before, SetupFingerprint.of(prefs))
    }

    @Test
    fun `vibration is not setup and does not change the fingerprint`() {
        prefs.setRecordingFolderUri("content://tree/a".toUri())
        val before = SetupFingerprint.of(prefs)
        prefs.setVibrationEnabled(!prefs.isVibrationEnabled())
        assertEquals(before, SetupFingerprint.of(prefs))
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*SetupFingerprintTest*"`
Expected: FAIL — `Unresolved reference 'SetupFingerprint'`

If `setVibrationEnabled`/`isVibrationEnabled` do not exist under those exact names, substitute any other non-setup boolean preference that does; the point of the test is that a non-setup preference leaves the hash alone.

- [ ] **Step 7: Write the fingerprint**

```kotlin
package com.baba.callvault.data.health

import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences

/**
 * A stable hash of the setup a user owns. When it changes, an earlier verification no longer speaks
 * for the current configuration.
 *
 * Wireless and USB debugging state are deliberately absent: CallVault toggles those itself as normal
 * behaviour, so including them would invalidate verification through the app's own actions.
 */
object SetupFingerprint {

    fun of(prefs: AppPreferences): String = listOf(
        prefs.getRecordingFolderUri()?.toString().orEmpty(),
        prefs.getDriveFolderUri()?.toString().orEmpty(),
        prefs.getStorageTarget().key,
        prefs.isAdbPaired().toString(),
        BuildConfig.VERSION_CODE.toString()
    ).joinToString("|").hashCode().toString(RADIX_HEX)

    private const val RADIX_HEX = 16
}
```

- [ ] **Step 8: Run both test classes to verify they pass**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*SetupHealthStoreTest*" --tests "*SetupFingerprintTest*"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/baba/callvault/data/health app/src/test/java/com/baba/callvault/data/health
git commit -m "feat(health): persist what real calls proved about the setup"
```

---

### Task 2: Record the outcome at every call end

**Files:**
- Create: `app/src/main/java/com/baba/callvault/data/health/CallOutcome.kt`
- Create: `app/src/test/java/com/baba/callvault/data/health/CallOutcomeTest.kt`
- Modify: `app/src/main/java/com/baba/callvault/services/recording/RecordingForegroundService.kt` (`routeFinalRecording`, the 0-byte branch and the success path)
- Modify: `app/src/main/java/com/baba/callvault/services/recording/VoipRecordingCoordinator.kt` (`onCallEnded`)

**Interfaces:**
- Consumes: `SetupHealthStore`, `SetupFingerprint`, `FailureReason` (Task 1).
- Produces: `sealed interface CallOutcome { data object Verified; data class Failed(val reason: FailureReason) }` and `object CallOutcomes { fun of(sizeBytes: Long, daemonDied: Boolean, farPartyHeard: Boolean?): CallOutcome }`, plus `fun SetupHealthStore.record(outcome: CallOutcome, atMillis: Long, label: String?, fingerprint: String)`.

**Why a pure function:** the two call sites are an Android `Service` and a `@Synchronized` coordinator — neither is unit-testable. The decision they make is, so it lives on its own.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Test

class CallOutcomeTest {

    @Test
    fun `a recording with bytes and a heard far party is verified`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = true))
    }

    @Test
    fun `a carrier recording with bytes is verified when there is no far-party signal`() {
        assertEquals(CallOutcome.Verified, CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = null))
    }

    @Test
    fun `an empty recording is an empty-file failure`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.EMPTY_FILE),
            CallOutcomes.of(0L, daemonDied = false, farPartyHeard = true)
        )
    }

    @Test
    fun `a dead daemon outranks the empty file it caused`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.DAEMON_DIED),
            CallOutcomes.of(0L, daemonDied = true, farPartyHeard = null)
        )
    }

    @Test
    fun `bytes but an unheard far party is one-sided`() {
        assertEquals(
            CallOutcome.Failed(FailureReason.ONE_SIDED),
            CallOutcomes.of(43_971L, daemonDied = false, farPartyHeard = false)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*CallOutcomeTest*"`
Expected: FAIL — `Unresolved reference 'CallOutcome'`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.baba.callvault.data.health

/** What a finished call proved. */
sealed interface CallOutcome {
    data object Verified : CallOutcome
    data class Failed(val reason: FailureReason) : CallOutcome
}

object CallOutcomes {

    /**
     * Judges a finished call from what the recording path already knows.
     *
     * @param sizeBytes     bytes actually captured (the trusted count, not a cloud provider's length).
     * @param daemonDied    true when the recorder was lost mid-call; it outranks the empty file it caused,
     *                      because "the recorder stopped" is the actionable message.
     * @param farPartyHeard true/false where observable, null where the capture path cannot tell — which
     *                      must not be read as silence. Carrier capture passes null until the follow-on
     *                      plan adds PCM peak detection.
     */
    fun of(sizeBytes: Long, daemonDied: Boolean, farPartyHeard: Boolean?): CallOutcome = when {
        daemonDied -> CallOutcome.Failed(FailureReason.DAEMON_DIED)
        sizeBytes <= 0L -> CallOutcome.Failed(FailureReason.EMPTY_FILE)
        farPartyHeard == false -> CallOutcome.Failed(FailureReason.ONE_SIDED)
        else -> CallOutcome.Verified
    }
}

/** Persists [outcome] and remembers that this call was observed at all. */
fun SetupHealthStore.record(outcome: CallOutcome, atMillis: Long, label: String?, fingerprint: String) {
    observeCall(atMillis)
    when (outcome) {
        is CallOutcome.Verified -> recordVerified(atMillis, fingerprint)
        is CallOutcome.Failed -> recordFailure(atMillis, outcome.reason, label)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*CallOutcomeTest*"`
Expected: PASS (5 tests)

- [ ] **Step 5: Wire the carrier path**

In `RecordingForegroundService.kt`, add the imports:

```kotlin
import com.baba.callvault.data.health.CallOutcomes
import com.baba.callvault.data.health.SetupFingerprint
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.data.health.record
```

Inside `routeFinalRecording`, in the `if (sizeBytes <= 0L)` branch, immediately before its `return`:

```kotlin
            recordHealth(sizeBytes, name)
```

And on the success path, immediately before the existing `StorageRouter.route(...)` line:

```kotlin
        recordHealth(sizeBytes, name)
```

Then add this private method to the class, directly below `routeFinalRecording`:

```kotlin
    /**
     * Records what this call proved, for the Home status card. Best-effort: a store that cannot be
     * written leaves the previous state rather than corrupting it. `farPartyHeard` is null because the
     * carrier capture path cannot observe silence — see the follow-on plan.
     */
    private fun recordHealth(sizeBytes: Long, name: String) {
        runCatching {
            val outcome = CallOutcomes.of(sizeBytes, daemonLossNotified, farPartyHeard = null)
            SetupHealthStore(applicationContext)
                .record(outcome, System.currentTimeMillis(), name, SetupFingerprint.of(appPreferences))
        }.onFailure { AppLogger.w(TAG, "Could not record setup health for '$name': ${it.message}") }
    }
```

- [ ] **Step 6: Wire the VoIP path**

In `VoipRecordingCoordinator.kt`, add the same four imports plus `com.baba.callvault.data.AppPreferences` if absent. Inside `onCallEnded`, in the `if (saf != null)` block, immediately after the existing `RecordingCatalog.recordLocal(...)` line:

```kotlin
                    runCatching {
                        val outcome = CallOutcomes.of(size, daemonDied = false, farPartyHeard = farHeard)
                        SetupHealthStore(context).record(
                            outcome, System.currentTimeMillis(), name, SetupFingerprint.of(AppPreferences(context))
                        )
                    }.onFailure { AppLogger.w(TAG, "Could not record setup health for '$name': ${it.message}") }
```

`farHeard` is the existing local computed earlier in the method from `voipFarPartyHeard()`; `size` and `name` are the existing locals. Do not recompute any of them.

- [ ] **Step 7: Verify it compiles and the suite is green**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest :app:compileReleaseKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/baba/callvault app/src/test/java/com/baba/callvault
git commit -m "feat(health): record what each call proved at both call-end sites"
```

---

### Task 3: The call-gap sweep, as a pure function

**Files:**
- Create: `app/src/main/java/com/baba/callvault/data/health/CallGapDetector.kt`
- Test: `app/src/test/java/com/baba/callvault/data/health/CallGapDetectorTest.kt`

**Interfaces:**
- Consumes: nothing (deliberately free of Android types).
- Produces:
  - `data class CallLogEntry(val startedAt: Long, val durationSeconds: Long, val isIncoming: Boolean, val label: String?)`
  - `data class CallGap(val startedAt: Long, val label: String?)`
  - `data class SweepResult(val gaps: List<CallGap>, val newWatermark: Long)`
  - `object CallGapDetector { fun sweep(entries: List<CallLogEntry>, observedCallEnds: List<Long>, autoRecordIncoming: Boolean, autoRecordOutgoing: Boolean, watermark: Long): SweepResult }`

- [ ] **Step 1: Write the failing test**

```kotlin
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
        watermark: Long = 0L
    ) = CallGapDetector.sweep(entries, observed, incoming, outgoing, watermark)

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
        val call = CallLogEntry(s(1_000), 60, isIncoming = true, label = null)
        assertTrue(sweep(listOf(call), observed = emptyList()).gaps.isEmpty())
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*CallGapDetectorTest*"`
Expected: FAIL — `Unresolved reference 'CallGapDetector'`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.baba.callvault.data.health

/** One call as the system call log reports it. Android types deliberately absent so this stays testable. */
data class CallLogEntry(
    val startedAt: Long,
    val durationSeconds: Long,
    val isIncoming: Boolean,
    val label: String?
)

/** A call that should have been recorded and that CallVault never observed. */
data class CallGap(val startedAt: Long, val label: String?)

data class SweepResult(val gaps: List<CallGap>, val newWatermark: Long)

/**
 * Finds calls CallVault never saw — the daemon-dead case, which produces no end-of-call event and so
 * cannot be caught by outcome recording alone.
 *
 * The join is on "did we observe this call", never "does a file exist": a user who deletes recordings
 * must not manufacture failures. Two refusals matter more than the matching itself — a call the ring
 * is too short to remember is not judged, and neither is one below the answer floor.
 */
object CallGapDetector {

    /** Half-width of the window in which an observed end counts as the same call. */
    private const val TOLERANCE_MILLIS = 90_000L

    /** Answered calls shorter than this are ignored: the largest false-alarm source, and least useful. */
    private const val MIN_ANSWERED_SECONDS = 5L

    private const val MILLIS_PER_SECOND = 1_000L

    fun sweep(
        entries: List<CallLogEntry>,
        observedCallEnds: List<Long>,
        autoRecordIncoming: Boolean,
        autoRecordOutgoing: Boolean,
        watermark: Long
    ): SweepResult {
        val newWatermark = entries.maxOfOrNull { it.startedAt }?.coerceAtLeast(watermark) ?: watermark
        val oldestRemembered = observedCallEnds.minOrNull()
            ?: return SweepResult(emptyList(), newWatermark) // nothing remembered → judge nothing

        val gaps = entries
            .filter { it.startedAt > watermark }
            .filter { it.startedAt >= oldestRemembered }
            .filter { it.durationSeconds >= MIN_ANSWERED_SECONDS }
            .filter { if (it.isIncoming) autoRecordIncoming else autoRecordOutgoing }
            .filterNot { entry ->
                val expectedEnd = entry.startedAt + entry.durationSeconds * MILLIS_PER_SECOND
                observedCallEnds.any { end -> kotlin.math.abs(end - expectedEnd) <= TOLERANCE_MILLIS }
            }
            .map { CallGap(it.startedAt, it.label) }

        return SweepResult(gaps, newWatermark)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*CallGapDetectorTest*"`
Expected: PASS (12 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/data/health/CallGapDetector.kt app/src/test/java/com/baba/callvault/data/health/CallGapDetectorTest.kt
git commit -m "feat(health): detect calls CallVault never observed"
```

---

### Task 4: Read the call log safely

**Files:**
- Create: `app/src/main/java/com/baba/callvault/data/health/CallLogReader.kt`
- Test: `app/src/test/java/com/baba/callvault/data/health/CallLogReaderTest.kt`

**Interfaces:**
- Consumes: `CallLogEntry` (Task 3).
- Produces: `object CallLogReader { fun entriesSince(context: Context, watermark: Long): List<CallLogEntry> }` — returns an empty list on any failure, never throws.

**The rule this task exists to enforce:** a sweep that cannot run claims nothing. Missing `READ_CALL_LOG`, a revoked permission, a throwing provider and a null cursor all produce an empty list, which `CallGapDetector` turns into zero gaps.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.baba.callvault.data.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallLogReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `without the call log permission it reads nothing rather than throwing`() {
        shadowOf(context.packageManager).denyPermissions(
            context.packageName, android.Manifest.permission.READ_CALL_LOG
        )
        assertEquals(emptyList<CallLogEntry>(), CallLogReader.entriesSince(context, 0L))
    }

    @Test
    fun `an unreadable provider reads nothing rather than throwing`() {
        // Robolectric serves no call-log rows by default; the query returns an empty cursor or null.
        assertEquals(emptyList<CallLogEntry>(), CallLogReader.entriesSince(context, 0L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*CallLogReaderTest*"`
Expected: FAIL — `Unresolved reference 'CallLogReader'`

If `denyPermissions` is unavailable on this Robolectric version, drop that first test and keep the second; the production guard is still exercised through `PermissionChecks`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.baba.callvault.data.health

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.baba.callvault.utils.AppLogger

/**
 * Reads answered calls out of the system call log for [CallGapDetector].
 *
 * Never throws and never partially reports: any failure — missing permission, a provider that throws,
 * a null cursor — yields an empty list, which the detector turns into zero gaps. A check that could not
 * run must not render as a failure.
 */
object CallLogReader {

    private const val TAG = "CV:CallLogReader"

    fun entriesSince(context: Context, watermark: Long): List<CallLogEntry> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AppLogger.i(TAG, "No call-log permission; the setup sweep claims nothing this time")
            return emptyList()
        }

        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(watermark.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val type = cursor.getInt(2)
                        if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                        add(
                            CallLogEntry(
                                startedAt = cursor.getLong(0),
                                durationSeconds = cursor.getLong(1),
                                isIncoming = type == CallLog.Calls.INCOMING_TYPE,
                                label = cursor.getString(3)?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse { e ->
            AppLogger.w(TAG, "Call log unreadable (${e.message}); the setup sweep claims nothing this time")
            emptyList()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*CallLogReaderTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/data/health/CallLogReader.kt app/src/test/java/com/baba/callvault/data/health/CallLogReaderTest.kt
git commit -m "feat(health): read the call log without ever claiming a failure it cannot prove"
```

---

### Task 5: Derive the card state

**Files:**
- Create: `app/src/main/java/com/baba/callvault/data/health/SetupHealth.kt`
- Test: `app/src/test/java/com/baba/callvault/data/health/SetupHealthTest.kt`

**Interfaces:**
- Consumes: `HealthFacts`, `FailureReason` (Task 1), `CallGap` (Task 3).
- Produces:
  - `sealed interface SetupHealth { data object Unverified; data class Verified(val atMillis: Long); data object StaleAfterChange; data class LastCallFailed(val atMillis: Long, val reason: FailureReason); data class CallNotRecorded(val atMillis: Long, val label: String?) }`
  - `val SetupHealth.isProblem: Boolean`
  - `object SetupHealthDeriver { fun derive(facts: HealthFacts, currentFingerprint: String, newestGap: CallGap?): SetupHealth }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.baba.callvault.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupHealthTest {

    private val verifiedFacts = HealthFacts(lastVerifiedAt = 5_000L, verifiedFingerprint = "fp-1")

    @Test
    fun `nothing observed yet is unverified`() {
        assertEquals(SetupHealth.Unverified, SetupHealthDeriver.derive(HealthFacts(), "fp-1", null))
    }

    @Test
    fun `a verified call under an unchanged fingerprint is verified`() {
        assertEquals(SetupHealth.Verified(5_000L), SetupHealthDeriver.derive(verifiedFacts, "fp-1", null))
    }

    @Test
    fun `a changed fingerprint makes an earlier verification stale`() {
        assertEquals(SetupHealth.StaleAfterChange, SetupHealthDeriver.derive(verifiedFacts, "fp-2", null))
    }

    @Test
    fun `an unobserved call outranks everything else`() {
        val gap = CallGap(9_000L, "Feroza")
        val facts = verifiedFacts.copy(lastFailureAt = 6_000L, lastFailureReason = FailureReason.EMPTY_FILE)
        assertEquals(SetupHealth.CallNotRecorded(9_000L, "Feroza"), SetupHealthDeriver.derive(facts, "fp-2", gap))
    }

    @Test
    fun `a failure outranks a setup change`() {
        val facts = verifiedFacts.copy(lastFailureAt = 6_000L, lastFailureReason = FailureReason.DAEMON_DIED)
        assertEquals(
            SetupHealth.LastCallFailed(6_000L, FailureReason.DAEMON_DIED),
            SetupHealthDeriver.derive(facts, "fp-2", null)
        )
    }

    @Test
    fun `a later verified call clears an older failure`() {
        // recordVerified() already removes the failure keys; this guards the derive step too.
        val facts = verifiedFacts.copy(lastVerifiedAt = 8_000L, lastFailureAt = 6_000L, lastFailureReason = FailureReason.EMPTY_FILE)
        assertEquals(SetupHealth.Verified(8_000L), SetupHealthDeriver.derive(facts, "fp-1", null))
    }

    @Test
    fun `only the good states are not problems`() {
        assertFalse(SetupHealth.Verified(1L).isProblem)
        assertFalse(SetupHealth.Unverified.isProblem)
        assertTrue(SetupHealth.StaleAfterChange.isProblem)
        assertTrue(SetupHealth.LastCallFailed(1L, FailureReason.EMPTY_FILE).isProblem)
        assertTrue(SetupHealth.CallNotRecorded(1L, null).isProblem)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*SetupHealthTest*"`
Expected: FAIL — `Unresolved reference 'SetupHealth'`

- [ ] **Step 3: Write the implementation**

```kotlin
package com.baba.callvault.data.health

/** What the status card has to say about whether recording actually works. */
sealed interface SetupHealth {
    /** No call has proved anything yet — a fresh install, or nothing since the setup changed. */
    data object Unverified : SetupHealth

    data class Verified(val atMillis: Long) : SetupHealth

    /** It worked, but the setup has changed since; the next call will confirm it still does. */
    data object StaleAfterChange : SetupHealth

    data class LastCallFailed(val atMillis: Long, val reason: FailureReason) : SetupHealth

    /** A call happened that CallVault never saw at all. */
    data class CallNotRecorded(val atMillis: Long, val label: String?) : SetupHealth
}

/** True for the states that should flip the card to a warning. */
val SetupHealth.isProblem: Boolean
    get() = this is SetupHealth.StaleAfterChange ||
        this is SetupHealth.LastCallFailed ||
        this is SetupHealth.CallNotRecorded

object SetupHealthDeriver {

    /**
     * First match wins, most urgent first: a call that vanished entirely, then a call that failed,
     * then a setup change that makes an old verification stop speaking for the current configuration.
     *
     * A failure older than the last verification is spent — a later call proved things work again.
     */
    fun derive(facts: HealthFacts, currentFingerprint: String, newestGap: CallGap?): SetupHealth {
        if (newestGap != null) return SetupHealth.CallNotRecorded(newestGap.startedAt, newestGap.label)

        val reason = facts.lastFailureReason
        if (reason != null && facts.lastFailureAt > facts.lastVerifiedAt) {
            return SetupHealth.LastCallFailed(facts.lastFailureAt, reason)
        }
        if (facts.lastVerifiedAt <= 0L) return SetupHealth.Unverified
        if (facts.verifiedFingerprint != currentFingerprint) return SetupHealth.StaleAfterChange
        return SetupHealth.Verified(facts.lastVerifiedAt)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest --tests "*SetupHealthTest*"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/baba/callvault/data/health/SetupHealth.kt app/src/test/java/com/baba/callvault/data/health/SetupHealthTest.kt
git commit -m "feat(health): derive the card state from facts and gaps"
```

---

### Task 6: Show it on the card

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/ui/viewmodels/HomeViewModel.kt` (`HomeUiState`, `refresh`)
- Modify: `app/src/main/java/com/baba/callvault/ui/screens/HomeScreen.kt` (`HeroStatusCard` and its call site at line ~197)
- Modify: `app/src/main/res/values/strings_home.xml`, `app/src/main/res/values-fr/strings_home.xml`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: `HomeUiState.setupHealth: SetupHealth`.

- [ ] **Step 1: Add the strings**

`app/src/main/res/values/strings_home.xml`:

```xml
    <string name="home_health_verified">Last verified %1$s.</string>
    <string name="home_health_unverified">Your next call will confirm recording works.</string>
    <string name="home_health_setup_changed">Setup changed since your last call — your next call will confirm it still works.</string>
    <string name="home_health_call_not_recorded">A call was not recorded — %1$s, %2$s.</string>
    <string name="home_health_call_not_recorded_unnamed">A call was not recorded — %1$s.</string>
    <string name="home_health_failed_empty">Your last call produced an empty recording (0 bytes).</string>
    <string name="home_health_failed_daemon">The recorder stopped during your last call.</string>
    <string name="home_health_failed_one_sided">Only your side was recorded on your last app call.</string>
```

`app/src/main/res/values-fr/strings_home.xml`:

```xml
    <string name="home_health_verified">Dernière vérification %1$s.</string>
    <string name="home_health_unverified">Votre prochain appel confirmera que l\'enregistrement fonctionne.</string>
    <string name="home_health_setup_changed">La configuration a changé depuis votre dernier appel — le prochain confirmera qu\'elle fonctionne toujours.</string>
    <string name="home_health_call_not_recorded">Un appel n\'a pas été enregistré — %1$s, %2$s.</string>
    <string name="home_health_call_not_recorded_unnamed">Un appel n\'a pas été enregistré — %1$s.</string>
    <string name="home_health_failed_empty">Votre dernier appel a produit un enregistrement vide (0 octet).</string>
    <string name="home_health_failed_daemon">L\'enregistreur s\'est arrêté pendant votre dernier appel.</string>
    <string name="home_health_failed_one_sided">Seul votre côté a été enregistré lors de votre dernier appel via une application.</string>
```

- [ ] **Step 2: Add the state to HomeUiState**

In `HomeViewModel.HomeUiState`, after the `deletingUris` property:

```kotlin
        /** What real calls have proved about this setup — drives the status card's second line. */
        val setupHealth: SetupHealth = SetupHealth.Unverified
```

Add the imports:

```kotlin
import com.baba.callvault.data.health.CallGapDetector
import com.baba.callvault.data.health.CallLogReader
import com.baba.callvault.data.health.SetupFingerprint
import com.baba.callvault.data.health.SetupHealth
import com.baba.callvault.data.health.SetupHealthDeriver
import com.baba.callvault.data.health.SetupHealthStore
```

- [ ] **Step 3: Run the sweep on refresh**

In `HomeViewModel.refresh()`, inside the existing `viewModelScope.launch { ... }` that loads recordings, immediately before the `withContext(Dispatchers.IO) { RecordingsRepository.listRecordings(appContext) }` line:

```kotlin
            val health = withContext(Dispatchers.IO) { sweepSetupHealth() }
            _uiState.update { it.copy(setupHealth = health) }
```

Add the private method below `computeStatus()`:

```kotlin
    /**
     * Reconciles the call log against the calls CallVault observed, then derives what the card says.
     * IO-bound (a content-provider query), so callers must be off the main thread. Best-effort
     * throughout: anything unreadable yields Unverified rather than an invented failure.
     */
    private fun sweepSetupHealth(): SetupHealth = runCatching {
        val store = SetupHealthStore(appContext)
        val facts = store.read()
        val result = CallGapDetector.sweep(
            entries = CallLogReader.entriesSince(appContext, facts.sweepWatermark),
            observedCallEnds = facts.observedCallEnds,
            autoRecordIncoming = preferences.isAutoRecordIncomingEnabled(),
            autoRecordOutgoing = preferences.isAutoRecordOutgoingEnabled(),
            watermark = facts.sweepWatermark
        )
        if (result.newWatermark != facts.sweepWatermark) store.setSweepWatermark(result.newWatermark)
        SetupHealthDeriver.derive(facts, SetupFingerprint.of(preferences), result.gaps.maxByOrNull { it.startedAt })
    }.getOrElse { e ->
        AppLogger.w(TAG, "Setup-health sweep failed (${e.message}); claiming nothing")
        SetupHealth.Unverified
    }
```

If `HomeViewModel` has no `TAG` constant, use the class's existing logging tag; if it has none, add `private const val TAG = "CV:HomeViewModel"` to its companion object.

- [ ] **Step 4: Render it on the card**

In `HomeScreen.kt`, change the `HeroStatusCard` signature and the two derived values:

```kotlin
@Composable
private fun HeroStatusCard(
    status: HomeViewModel.HomeStatus,
    health: SetupHealth,
    onAction: (() -> Unit)? = null,
) {
    val brand = LocalCvBrand.current
    // A healthy setup that has never been proved still reads as ready; only a real problem flips the card.
    val showsProblem = !status.isReady || health.isProblem
    val accent: Color = if (showsProblem) brand.warning else MaterialTheme.colorScheme.primary
    val icon: ImageVector = if (showsProblem) Icons.Filled.WarningAmber else Icons.Filled.CheckCircle
```

Leave `tone` and `pillText` exactly as they are — they describe the blocking status, which has not changed.

Then replace the suggestion `Text(...)` (the one using `status.suggestionResId`) with:

```kotlin
        Text(
            text = if (status.isReady) healthMessage(health) else stringResource(status.suggestionResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
```

Add this composable directly below `HeroStatusCard`:

```kotlin
/**
 * The card's second line when nothing is blocking: what real calls proved. The happy path stays plain —
 * a byte count appears only in the empty-recording failure, never as reassurance.
 */
@Composable
private fun healthMessage(health: SetupHealth): String = when (health) {
    is SetupHealth.Verified -> stringResource(R.string.home_health_verified, relativeTime(health.atMillis))
    is SetupHealth.Unverified -> stringResource(R.string.home_health_unverified)
    is SetupHealth.StaleAfterChange -> stringResource(R.string.home_health_setup_changed)
    is SetupHealth.CallNotRecorded -> health.label?.let {
        stringResource(R.string.home_health_call_not_recorded, relativeTime(health.atMillis), it)
    } ?: stringResource(R.string.home_health_call_not_recorded_unnamed, relativeTime(health.atMillis))
    is SetupHealth.LastCallFailed -> stringResource(
        when (health.reason) {
            FailureReason.EMPTY_FILE -> R.string.home_health_failed_empty
            FailureReason.DAEMON_DIED -> R.string.home_health_failed_daemon
            FailureReason.ONE_SIDED -> R.string.home_health_failed_one_sided
        }
    )
}

/** "2 hours ago", "Yesterday 18:44" — the platform's own phrasing, so it is localised for free. */
@Composable
private fun relativeTime(atMillis: Long): String = DateUtils.getRelativeDateTimeString(
    LocalContext.current, atMillis, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0
).toString()
```

Add the imports `android.text.format.DateUtils`, `androidx.compose.ui.platform.LocalContext`, `com.baba.callvault.data.health.FailureReason`, `com.baba.callvault.data.health.SetupHealth`, `com.baba.callvault.data.health.isProblem`.

Finally, at the `HeroStatusCard(` call site (~line 197), pass the new argument:

```kotlin
                    health = uiState.setupHealth,
```

- [ ] **Step 5: Verify the build and the whole suite**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:testDebugUnitTest :app:compileReleaseKotlin`
Expected: BUILD SUCCESSFUL, 0 failures

- [ ] **Step 6: Verify on the device**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease -PversionCode=10621 -PversionName=1.5.3-health
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.baba.callvault android.permission.WRITE_SECURE_SETTINGS
```

Then: open Home and confirm the card reads "Ready to record" with "Your next call will confirm recording works." Make one call, reopen Home, and confirm it reads "Last verified <time>." with no byte count and no mention of audio. `adb shell run-as` is unavailable (release build is not debuggable) — read state through the UI, not the preference file.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/baba/callvault/ui app/src/main/res/values/strings_home.xml app/src/main/res/values-fr/strings_home.xml
git commit -m "feat(health): report what real calls proved on the status card"
```

---

## Self-review

**Spec coverage.** Outcome recording → Task 2. Persisted state → Task 1. Fingerprint (and its deliberate exclusion of WD/USB state) → Task 1. Derived states → Task 5. The sweep, both refusals, the duration floor and tolerance → Task 3. Call-log safety → Task 4. Card priority, copy rules and per-reason failure lines → Task 6. VoIP invisibility to the sweep is structural (no call-log entry) and needs no code.

**Deferred, deliberately:** the `SILENT` reason and carrier PCM peak detection. `CallOutcomes.of` already accepts `farPartyHeard: Boolean?` and treats null as "cannot tell", so the follow-on plan changes one argument at one call site rather than reworking the chain.

**Known judgement calls, flagged in the spec and worth revisiting after real use:** the 5-second answer floor and the ±90 s match tolerance. Both are guesses tuned to avoid false alarms, not measurements. If the card ever cries wolf, these are the two numbers to look at first.
