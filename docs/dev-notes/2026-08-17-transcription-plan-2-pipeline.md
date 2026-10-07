# Transcription Plan 2 — Storage, Models, Pipeline, Scheduling

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the verified engine from Plan 1 into a working feature — transcripts are stored
durably and searchably, models are downloaded and verified, and every un-transcribed recording is
picked up either on a schedule the user chooses or on demand.

**Architecture:** A **separate** Room database for transcripts (the recordings catalog is a
destructible cache — see the correction below), keyed by the recording's `displayName`. A
`TranscriptionWorker` under WorkManager drains a queue of un-transcribed recordings one at a time,
checkpointing after each recording so a killed job resumes instead of restarting. A
`TranscriptionScheduler` mirrors the existing `SyncScheduler` exactly.

**Tech Stack:** Kotlin, Room (+FTS4), WorkManager 2.10.0, Compose Material 3, whisper.cpp via the
`whispercv` JNI from Plan 1.

**Spec:** `docs/dev-notes/2026-08-16-on-device-transcription-design.md`
**Predecessor:** `docs/dev-notes/2026-08-16-transcription-plan-1-engine.md` (done, verified on device)

---

## ✅ PLAN 2 COMPLETE — 2026-08-17

All six tasks built and committed. **365 unit tests, 0 failures, 0 skipped**; `assembleDebug` green;
`checkTranslations` reports 10 locales at 525 strings each with consistent placeholders.

| task | commit |
|---|---|
| 1 — transcript storage + FTS + cascade | `21e0e03` |
| 2 — model catalog, verified resumable downloads | `acdc3d7` |
| 3 — the queue | `e463505` |
| 4+5 — runner, worker, Manual/Automatic scheduler | `341e92d` |
| 6 — Settings section | `cabc9c7` |

**Deviations from this plan, all deliberate:**

- **Downloads are resumable, not foreground.** WorkManager stops a worker after ~10 minutes and these
  are 190–574 MB, so an interrupted download resumes with a Range request instead of holding a
  foreground service open. Cheaper for the user and simpler than the notification plumbing.
- **`isInstalled` compares length; only `finalizeDownload` hashes.** Hashing 574 MB every time the
  settings screen drew would stall it. The full digest still gates installation.
- **The orchestration lives in `TranscriptionRunner`, not the worker.** The worker cannot be unit
  tested (native library, WorkManager), so everything worth testing was moved behind a `Transcriber`
  seam and the worker reduced to a thin wrapper.
- **Turbo's real size is 574,041,195 bytes**, not the 574,041,600 estimated here — fetched from the
  published LFS pointer rather than guessed, since a wrong constant fails every verification.

**On-device verification is still outstanding for this plan.** Everything is JVM-tested; nothing here
has yet transcribed a real call end to end through the worker. Those checks are **not** to be run
piecemeal — they are collected in
**`docs/dev-notes/2026-08-17-transcription-device-test-plan.md`**, to be worked through in one
session when a build is installed on the phone.

---

---

## Correction to the approved spec — read before Task 1

The spec says transcripts live in *"Same Room database (`RecordingDatabase`), bumped **v1 → v2**"*
with an `ON DELETE CASCADE` foreign key. **That is wrong and must not be built.** Reading the code:

```kotlin
// RecordingDatabase.kt:24-39 — the catalog is a derived, rebuildable cache
@Database(entities = [RecordingEntry::class], version = 1, exportSchema = false)
...
).fallbackToDestructiveMigration(dropAllTables = true).build()
```

The catalog is *deliberately* destructible: it can be re-seeded from the SAF folders, so schema bumps
drop it rather than carry hand-written migrations. **Transcripts are not rebuildable.** They cost
minutes of device CPU each and cannot be regenerated from anything but the audio, at the same cost
again. Putting them in that database means the next unrelated schema bump silently destroys every
transcript the user has.

There is a second, subtler problem: destructive re-seed **re-creates the recordings rows**, so
anything keyed on that table's identity must survive the rebuild. It does, but only because
`displayName` is a *natural* key:

```kotlin
// RecordingEntry.kt:24, 40 — "displayName is the natural key: CallVault's filename template embeds a
// millisecond timestamp, so a name uniquely identifies a recording"
@PrimaryKey val displayName: String
```

**Therefore:** transcripts get their own database, `transcripts.db`, with **real migrations and no
destructive fallback**, keyed by `displayName`. The cost is that a cross-database foreign key is
impossible, so the cascade delete becomes an explicit call in the deletion path (Task 1, Step 7) —
that is a deliberate, documented trade, not an oversight.

## Global Constraints

- **License header** — every new `.kt` file starts with the GPLv3 header block used by every existing
  source file (copy verbatim from `RecordingEntry.kt:1-7`).
- **Logging** — `AppLogger.i/w/e` with a `CV:` tag prefix. Never `println`/`Log.*` directly.
- **Strings** — every user-visible string is a resource, translated to **all 9 locales**. No literals
  in Compose.
- **Immutability** — data classes + `copy()`. No in-place mutation.
- **Never** put real call audio, transcripts, or phone numbers into the repo, logs, or tests.
- **Model files are large** (190 MB / 574 MB). They live in `context.filesDir/models/`, never in the
  recordings folders, and are excluded from backup.
- Commits: conventional format, **no attribution trailers**.

---

## Task 1: Transcript storage that survives a schema bump — ✅ DONE 2026-08-17 (`21e0e03`)

> **Built and green: 10 new tests, 340 total, 0 failures.** Two things worth carrying forward:
>
> - **`exportSchema = true` exports nothing without `room.schemaLocation`.** Added to
>   `app/build.gradle.kts`; the baseline is committed at
>   `app/schemas/…TranscriptDatabase/1.json`. Without it the migration promise was hollow.
> - **The cascade needed wiring into *two* paths, not one.** `removeName` is the user deleting a
>   recording; the **retention sweep deletes per copy** via `removeCopyByUri`, which drops the row
>   through a bulk `deleteEmpty()` that cannot report what it removed — so orphaned names are captured
>   before it runs. Verified that Room does generate FTS sync triggers, so search rows follow segment
>   deletes automatically (four `room_fts_content_sync_*` triggers in the exported schema).

**Files:**
- Create: `app/src/main/java/com/baba/callvault/data/transcripts/db/TranscriptEntry.kt`
- Create: `app/src/main/java/com/baba/callvault/data/transcripts/db/TranscriptSegmentEntry.kt`
- Create: `app/src/main/java/com/baba/callvault/data/transcripts/db/TranscriptSegmentFts.kt`
- Create: `app/src/main/java/com/baba/callvault/data/transcripts/db/TranscriptDao.kt`
- Create: `app/src/main/java/com/baba/callvault/data/transcripts/db/TranscriptDatabase.kt`
- Modify: `app/src/main/java/com/baba/callvault/data/recordings/RecordingsRepository.kt` (cascade)
- Test: `app/src/test/java/com/baba/callvault/data/transcripts/TranscriptDaoTest.kt`

**Interfaces:**
- Consumes: `RecordingEntry.displayName` as the join key (a `String`).
- Produces:
  - `TranscriptEntry(displayName: String, state: TranscriptState, modelId: String?, language: String?,
    updatedAt: Long, errorMessage: String?)`
  - `TranscriptSegmentEntry(id: Long, displayName: String, startMs: Long, endMs: Long, text: String,
    speaker: String?)`
  - `TranscriptDao.upsertTranscript(...)`, `replaceSegments(displayName, segments)`,
    `observe(displayName): Flow<TranscriptWithSegments?>`, `search(query): List<TranscriptSearchHit>`,
    `deleteFor(displayName)`, `displayNamesWithState(state): List<String>`
  - `TranscriptState` enum: `NONE, QUEUED, RUNNING, DONE, FAILED`

- [ ] **Step 1: Write the failing DAO test**

Robolectric + an in-memory database. This test is the point of the whole task: it asserts that
segments come back in order, that FTS finds Hebrew, and that deleting cascades.

```kotlin
@RunWith(RobolectricTestRunner::class)
class TranscriptDaoTest {

    private lateinit var db: TranscriptDatabase
    private lateinit var dao: TranscriptDao

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.transcriptDao()
    }

    @After fun tearDown() = db.close()

    @Test
    fun `stores segments and returns them in start order`() = runTest {
        // Arrange
        dao.upsertTranscript(entry("call-a.ogg", TranscriptState.DONE))
        dao.replaceSegments("call-a.ogg", listOf(
            segment("call-a.ogg", 1000, 2000, "second"),
            segment("call-a.ogg", 0, 1000, "first"),
        ))

        // Act
        val stored = dao.observe("call-a.ogg").first()

        // Assert
        assertEquals(listOf("first", "second"), stored!!.segments.map { it.text })
    }

    @Test
    fun `full text search finds a Hebrew word inside a segment`() = runTest {
        // Arrange — Hebrew matters: the porter tokenizer would stem this into nothing useful.
        dao.upsertTranscript(entry("call-b.ogg", TranscriptState.DONE))
        dao.replaceSegments("call-b.ogg", listOf(
            segment("call-b.ogg", 0, 1000, "נתראה בפגישה מחר בבוקר")
        ))

        // Act
        val hits = dao.search("בפגישה")

        // Assert
        assertEquals(listOf("call-b.ogg"), hits.map { it.displayName })
    }

    @Test
    fun `deleting a transcript removes its segments and its search rows`() = runTest {
        // Arrange
        dao.upsertTranscript(entry("call-c.ogg", TranscriptState.DONE))
        dao.replaceSegments("call-c.ogg", listOf(segment("call-c.ogg", 0, 1, "ephemeral")))

        // Act
        dao.deleteFor("call-c.ogg")

        // Assert
        assertNull(dao.observe("call-c.ogg").first())
        assertTrue("search still returned a deleted transcript", dao.search("ephemeral").isEmpty())
    }

    @Test
    fun `replacing segments does not accumulate duplicates on re-transcription`() = runTest {
        // Arrange — re-running transcription on the same recording must overwrite, not append.
        dao.upsertTranscript(entry("call-d.ogg", TranscriptState.DONE))
        dao.replaceSegments("call-d.ogg", listOf(segment("call-d.ogg", 0, 1, "old")))

        // Act
        dao.replaceSegments("call-d.ogg", listOf(segment("call-d.ogg", 0, 1, "new")))

        // Assert
        assertEquals(listOf("new"), dao.observe("call-d.ogg").first()!!.segments.map { it.text })
    }

    private fun entry(name: String, state: TranscriptState) = TranscriptEntry(
        displayName = name, state = state, modelId = "small-q5_1",
        language = "he", updatedAt = 0L, errorMessage = null
    )

    private fun segment(name: String, start: Long, end: Long, text: String) =
        TranscriptSegmentEntry(displayName = name, startMs = start, endMs = end,
            text = text, speaker = null)
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew testDebugUnitTest --tests "*TranscriptDaoTest*"`
Expected: FAIL — unresolved reference `TranscriptDatabase`.

- [ ] **Step 3: Write the entities**

`speaker` is nullable and populated by nothing yet — it is reserved so speaker recognition needs no
migration later (see Plan 4).

```kotlin
/** One transcript per recording, keyed by the recording's natural key ([RecordingEntry.displayName]). */
@Entity(tableName = "transcripts")
data class TranscriptEntry(
    @PrimaryKey val displayName: String,
    val state: TranscriptState,
    val modelId: String?,
    val language: String?,
    val updatedAt: Long,
    val errorMessage: String? = null
)

/**
 * One recognised segment. [speaker] is reserved for diarization (Plan 4) and is null today; the
 * column exists now so adding speakers later is a data change, not a migration.
 */
@Entity(
    tableName = "transcript_segments",
    indices = [Index("displayName")]
)
data class TranscriptSegmentEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null
)

/**
 * FTS mirror of [TranscriptSegmentEntry.text] — the real payoff of storing transcripts at all: it
 * makes years of calls searchable.
 *
 * Tokenizer is **unicode61**, NOT porter. Porter stems English and would mangle the Hebrew these
 * calls are actually in. unicode61 splits on non-alphanumerics, which is correct for Hebrew script.
 */
@Fts4(contentEntity = TranscriptSegmentEntry::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "transcript_segments_fts")
data class TranscriptSegmentFts(val text: String)
```

- [ ] **Step 4: Write the DAO**

```kotlin
@Dao
interface TranscriptDao {

    @Upsert suspend fun upsertTranscript(entry: TranscriptEntry)

    @Transaction @Query("SELECT * FROM transcripts WHERE displayName = :displayName")
    fun observe(displayName: String): Flow<TranscriptWithSegments?>

    @Query("SELECT displayName FROM transcripts WHERE state = :state")
    suspend fun displayNamesWithState(state: TranscriptState): List<String>

    /** Overwrites in one transaction so a re-run replaces rather than appends. */
    @Transaction
    suspend fun replaceSegments(displayName: String, segments: List<TranscriptSegmentEntry>) {
        deleteSegments(displayName)
        insertSegments(segments)
    }

    @Query("DELETE FROM transcript_segments WHERE displayName = :displayName")
    suspend fun deleteSegments(displayName: String)

    @Insert suspend fun insertSegments(segments: List<TranscriptSegmentEntry>)

    /** Deletes the transcript and its segments. FTS rows follow automatically (contentEntity). */
    @Transaction
    suspend fun deleteFor(displayName: String) {
        deleteSegments(displayName)
        deleteTranscript(displayName)
    }

    @Query("DELETE FROM transcripts WHERE displayName = :displayName")
    suspend fun deleteTranscript(displayName: String)

    /**
     * One hit per recording, with the earliest matching segment as the snippet. `MATCH` requires the
     * FTS table; joining back to the content table gives the timestamp to jump to.
     */
    @Query("""
        SELECT s.displayName AS displayName, MIN(s.startMs) AS startMs, s.text AS snippet
        FROM transcript_segments AS s
        JOIN transcript_segments_fts AS f ON f.rowid = s.id
        WHERE transcript_segments_fts MATCH :query
        GROUP BY s.displayName
    """)
    suspend fun search(query: String): List<TranscriptSearchHit>
}

data class TranscriptWithSegments(
    @Embedded val transcript: TranscriptEntry,
    @Relation(parentColumn = "displayName", entityColumn = "displayName")
    val segmentsUnordered: List<TranscriptSegmentEntry>
) {
    /** Room cannot ORDER BY inside @Relation; sort once here so every caller sees stable order. */
    val segments: List<TranscriptSegmentEntry> get() = segmentsUnordered.sortedBy { it.startMs }
}

data class TranscriptSearchHit(val displayName: String, val startMs: Long, val snippet: String)
```

- [ ] **Step 5: Write the database — with the no-destructive-fallback rule stated in code**

```kotlin
/**
 * Transcripts, deliberately in their OWN database rather than [RecordingDatabase].
 *
 * The recordings catalog is a rebuildable cache and uses fallbackToDestructiveMigration, which is
 * right for it and fatal for us: a transcript costs minutes of device CPU and cannot be regenerated
 * from anything cheaper than the audio it came from. So this database carries real migrations and
 * MUST NEVER be given a destructive fallback.
 *
 * Rows are keyed by RecordingEntry.displayName, the catalog's natural key, so transcripts survive
 * the catalog being dropped and re-seeded.
 */
@Database(
    entities = [TranscriptEntry::class, TranscriptSegmentEntry::class, TranscriptSegmentFts::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(TranscriptStateConverter::class)
abstract class TranscriptDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile private var INSTANCE: TranscriptDatabase? = null

        fun get(context: Context): TranscriptDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, TranscriptDatabase::class.java, "transcripts.db"
                ).build().also { INSTANCE = it }   // no fallbackToDestructiveMigration — see KDoc
            }
    }
}
```

- [ ] **Step 6: Run the tests to green**

Run: `./gradlew testDebugUnitTest --tests "*TranscriptDaoTest*"`
Expected: PASS (4 tests).

- [ ] **Step 7: Wire the cascade delete by hand**

Find every place a recording row is removed in `RecordingsRepository.kt` (deletion, retention sweep)
and call `TranscriptDatabase.get(context).transcriptDao().deleteFor(displayName)` alongside it. Add a
test asserting a deleted recording leaves no transcript behind.

> Do not skip this. A cross-database foreign key is impossible, so this call **is** the cascade. If
> it is missed, deleted calls keep searchable transcripts — a privacy bug, not a tidiness one.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/baba/callvault/data/transcripts app/src/test/java/com/baba/callvault/data/transcripts app/src/main/java/com/baba/callvault/data/recordings/RecordingsRepository.kt
git commit -m "feat(transcripts): durable transcript storage with FTS search"
```

---

## Task 2: Model catalog, download and verification

**Files:**
- Create: `app/src/main/java/com/baba/callvault/transcription/model/TranscriptionModel.kt`
- Create: `app/src/main/java/com/baba/callvault/transcription/model/ModelRepository.kt`
- Create: `app/src/main/java/com/baba/callvault/transcription/model/ModelDownloadWorker.kt`
- Test: `app/src/test/java/com/baba/callvault/transcription/model/ModelRepositoryTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `TranscriptionModel` enum: `SMALL_Q5_1` (190 MB), `LARGE_V3_TURBO_Q5_0` (574 MB), each with
    `id`, `url`, `sha256`, `sizeBytes`, `displayNameRes`
  - `ModelRepository.installedModels(): List<TranscriptionModel>`, `pathFor(model): File?`,
    `verify(file, model): Boolean`, `delete(model)`, `enqueueDownload(model)`

- [ ] **Step 1: Write the failing verification test**

The one thing that must not be got wrong: a truncated or corrupted 190 MB download must be rejected
before whisper ever opens it, because ggml's failure mode on a bad file is a crash in native code.

```kotlin
@Test
fun `rejects a file whose digest does not match the expected model`() {
    // Arrange
    val file = tempFolder.newFile("model.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    // Act
    val ok = ModelRepository.verify(file, expectedSha256 = "deadbeef".repeat(8))

    // Assert
    assertFalse("a corrupt model must never be accepted", ok)
}

@Test
fun `accepts a file whose digest matches`() {
    // Arrange
    val bytes = "hello".toByteArray()
    val file = tempFolder.newFile("model.bin").apply { writeBytes(bytes) }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    // Act / Assert
    assertTrue(ModelRepository.verify(file, expectedSha256 = digest))
}

@Test
fun `reports a model as installed only when the file verifies`() {
    // Arrange — a half-written file left by a killed download must not read as installed.
    val dir = tempFolder.newFolder("models")
    File(dir, "small-q5_1.bin").writeBytes(byteArrayOf(0))

    // Act / Assert
    assertFalse(ModelRepository.isInstalled(dir, TranscriptionModel.SMALL_Q5_1))
}
```

- [ ] **Step 2: Run it, expect FAIL** (`ModelRepository` unresolved).

- [ ] **Step 3: Implement `TranscriptionModel` and `ModelRepository`**

Digests must be the real published ones — fetch them once from the Hugging Face repo and paste them
in. Do not invent placeholders; a wrong constant makes every download fail verification.

```kotlin
/**
 * The two tiers the spec settled on. `medium` is dominated and `tiny`/`base` are unusable for
 * Hebrew, so neither is offered.
 */
enum class TranscriptionModel(
    val id: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    SMALL_Q5_1(
        id = "small-q5_1",
        fileName = "ggml-small-q5_1.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sha256 = "<paste real digest>",
        sizeBytes = 190_085_487L,
    ),
    LARGE_V3_TURBO_Q5_0(
        id = "large-v3-turbo-q5_0",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        sha256 = "<paste real digest>",
        sizeBytes = 574_041_600L,
    );
}
```

`ModelRepository` writes to `context.filesDir/models/`, downloads to a `.part` file and renames only
**after** the digest matches, so a killed download can never present itself as installed.

- [ ] **Step 4: Run tests to green.**

- [ ] **Step 5: Write `ModelDownloadWorker`**

A `CoroutineWorker` with `NetworkType.UNMETERED` and `setForeground` progress, because a 574 MB
download needs to survive the app going to background and must not run on the user's mobile data.
Report progress via `setProgress(workDataOf(KEY_PERCENT to pct))`.

- [ ] **Step 6: Commit** — `feat(transcription): model catalog with verified downloads`

---

## Task 3: The queue — what counts as "un-transcribed"

**Files:**
- Create: `app/src/main/java/com/baba/callvault/transcription/TranscriptionQueue.kt`
- Test: `app/src/test/java/com/baba/callvault/transcription/TranscriptionQueueTest.kt`

**Interfaces:**
- Consumes: `RecordingDao` (catalog), `TranscriptDao` (Task 1).
- Produces: `TranscriptionQueue.pending(limit: Int): List<String>` — display names, oldest first.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `pending excludes recordings that already have a transcript`() { /* ... */ }

@Test
fun `pending excludes recordings whose last attempt failed`() {
    // Otherwise a permanently undecodable file is retried on every scheduled run, forever, and the
    // user's battery pays for it nightly. FAILED is retried only by an explicit manual tap.
}

@Test
fun `pending returns oldest first so a backlog drains in call order`() { /* ... */ }

@Test
fun `pending skips recordings with no local copy`() {
    // A DRIVE-only recording has no local file to decode; queuing it guarantees a failure.
}
```

- [ ] **Step 2: Run, expect FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit.**

---

## Task 4: `TranscriptionWorker` — resumable, one recording at a time

**Files:**
- Create: `app/src/main/java/com/baba/callvault/transcription/TranscriptionWorker.kt`
- Test: `app/src/test/java/com/baba/callvault/transcription/TranscriptionWorkerTest.kt`

**Interfaces:**
- Consumes: `TranscriptionQueue`, `ModelRepository`, `TranscriptionEngine` (Plan 1), `TranscriptDao`.
- Produces: unique work `cv_transcription`, `Result.success()/retry()/failure()`.

The spec's constraint, restated because it drives the whole design: **a 30-minute call takes ~30 min
(small) or ~65 min (turbo)**. Work must therefore be checkpointed per recording and must never
restart a finished one.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `marks a recording DONE and stores its segments`() { /* ... */ }

@Test
fun `a recording already DONE is not transcribed again after a restart`() {
    // The checkpoint. A killed 25-minute job must resume at recording N+1, not at recording 1.
}

@Test
fun `returns retry when the model is not installed yet`() { /* ... */ }

@Test
fun `marks the recording FAILED and continues to the next one`() {
    // One undecodable file must not stall the whole backlog.
}

@Test
fun `sets state RUNNING before starting so the UI can show a spinner`() { /* ... */ }
```

- [ ] **Step 2: FAIL. Step 3: Implement as a `CoroutineWorker`:**

- runs in a foreground service (`setForeground`) with a progress notification — an hour of CPU is not
  something to do invisibly
- `Constraints`: `setRequiresCharging(true)` and `setRequiresBatteryNotLow(true)` when scheduled
  automatically; **no constraints** for a manual single-recording run
- per recording: set `RUNNING` → decode → transcribe → `replaceSegments` → set `DONE`; on throw, set
  `FAILED` with the message and move on
- honour `isStopped` between recordings and return `Result.retry()`

- [ ] **Step 4: Green. Step 5: Commit.**

---

## Task 5: `TranscriptionScheduler` — Manual vs Automatic

**Files:**
- Create: `app/src/main/java/com/baba/callvault/transcription/TranscriptionScheduler.kt`
- Create: `app/src/main/java/com/baba/callvault/data/TranscriptionMode.kt`
- Modify: `app/src/main/java/com/baba/callvault/data/AppPreferences.kt`
- Test: `app/src/test/java/com/baba/callvault/transcription/TranscriptionSchedulerTest.kt`

**Interfaces:**
- Produces: `TranscriptionMode` enum `MANUAL("manual"), AUTOMATIC("automatic")` with `fromKey`,
  matching `SyncScheduleMode`'s shape exactly; `TranscriptionScheduler.apply(context)`;
  `TranscriptionScheduler.runNow(context, displayName)` for the manual button in Plan 3.

**This task mirrors `SyncScheduler` deliberately.** Copy its structure (`WORK_NAME`, `apply()`
reconciling prefs with `ExistingPeriodicWorkPolicy.UPDATE`, `nextDailyDelayMillis`) rather than
inventing a second scheduling idiom — see `system/storage/SyncScheduler.kt:33-113`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `MANUAL cancels any periodic work`() { /* ... */ }

@Test
fun `AUTOMATIC schedules a daily run anchored to the chosen time`() { /* ... */ }

@Test
fun `apply is idempotent so calling it twice leaves one scheduled job`() { /* ... */ }
```

- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit.**

New preferences: `transcriptionMode`, `transcriptionHour`, `transcriptionMinute`, `transcriptionModelId`,
`transcriptionLanguage`, `transcriptionRequiresCharging` (default true), `transcriptionWifiOnly`
(default true, download only).

---

## Task 6: Settings — a dropdown that reveals its own options

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/baba/callvault/ui/viewmodels/SettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml` (+ all 9 locales)
- Test: `app/src/test/java/com/baba/callvault/ui/TranscriptionSettingsTest.kt`

**Interfaces:**
- Consumes: `TranscriptionMode`, `ModelRepository`, `SettingsActions`.
- Produces: a `TranscriptionSubSection` composable.

**Shape (explicitly requested):** a **Manual / Automatic dropdown**, and the additional settings
appear **only when Automatic is chosen**. This is exactly what `UploadScheduleSubSection`
(`SettingsScreen.kt:709-779`) already does for sync, so reuse `M3DropdownField` + `DropdownRow` and
the `if (mode == ...)` disclosure pattern rather than inventing a new one.

```
Transcription
├── Mode                [ Manual ▾ ]      ← always visible
│                       [ Automatic ]
│
└── when Automatic:                        ← revealed
    ├── Run at              [ 02 ] : [ 00 ]
    ├── Only while charging [x]
    └── (note: "Transcribing a 30-minute call takes about 30 minutes.")

Always visible, below:
    ├── Model            [ Small (190 MB) ▾ / Best (574 MB) ]  + Download / Delete
    └── Language         [ Hebrew ▾ / English / Auto-detect ]
```

- [ ] **Step 1: Write the failing test** — assert the extra rows are absent for `MANUAL` and present
  for `AUTOMATIC` (Robolectric + Compose test rule, mirroring the existing settings tests).
- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: Green.**
- [ ] **Step 5: Onboarding decision.** Per the standing rule that the wizard cannot be re-run, decide
  explicitly whether transcription belongs in onboarding. **Recommendation: no** — it needs a large
  download and is meaningless before any calls exist. Record the decision in the commit message.
- [ ] **Step 6: Commit.**

---

## Definition of done

- `./gradlew testDebugUnitTest` green, including the new Task 1–6 tests
- `./gradlew assembleDebug` succeeds
- Choosing Automatic in Settings schedules exactly one periodic job; choosing Manual cancels it
- Deleting a recording leaves no transcript and no FTS row
- Killing the app mid-backlog resumes on the next recording, not the first

**Not in this plan:** the per-recording button, the transcript modal and search UI (Plan 3), and
speaker recognition (Plan 4).
