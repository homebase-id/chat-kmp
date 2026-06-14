# Plan 007: Save the sync cursor once per batch in performBaseUpsert, not once per file

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt homebase-api/src/commonTest/kotlin/id/homebase/api/sync/database/MainIndexMetaTest.kt`. If either in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P2
- Effort: S
- Risk: LOW
- Depends on: none
- Category: bug/perf
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`performBaseUpsert` lands a batch of up to ~1000 incoming files into `DriveMainIndex` inside one single-writer `withWriteTransaction`. The cursor-save call sits **inside** the `fileHeaders.forEach` loop, so a 1000-file batch issues 1000 identical `KeyValue` cursor upserts (and allocates 1000 `CursorStorage` instances, each serializing the cursor to JSON) within that one hot write transaction. The writes are idempotent — the `KeyValue` table has `key` as PRIMARY KEY with `ON CONFLICT DO UPDATE`, so only one row ever results — but the wasted `INSERT … ON CONFLICT` executions, JSON serializations, and object allocations all pile onto the app's single DB writer, which is the contended resource during sync (see DatabaseManager single-writer architecture). Moving the save out of the loop keeps the cursor commit atomic with the row writes while collapsing 1000 redundant upserts to 1.

## Current state

### File to modify — `homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt`
`performBaseUpsert` (signature at line 180) runs all work inside `databaseManager.withWriteTransaction { db -> … }` opened at line 191. The cursor save is the LAST thing inside the `fileHeaders.forEach { fileHeader -> … }` loop body (loop opens at line 193, closes with the cursor block at line 255):

```kotlin
185        ): List<HomebaseFile> {
…
190            val written = ArrayList<HomebaseFile>(fileHeaders.size)
191            databaseManager.withWriteTransaction { db ->
192
193                fileHeaders.forEach { fileHeader ->
…  (per-file: convert, upsertDriveMainIndex, tag/local-tag writes) …
248                    }
249
250                    // Even if we didn't update the record we advance the cursor
251                    if (cursor != null) {
252                        val cursorStorage = CursorStorage(databaseManager, driveId)
253                        cursorStorage.saveCursor(db, cursor)
254                    }
255                }
256            }
257            return written
258        }
```

The fix: cut the `if (cursor != null) { … }` block (lines 250–254) out of the `forEach` and place it once **after** the loop closes (after line 255's `}`) but **before** the `withWriteTransaction` block closes (line 256's `}`), so it still commits atomically with the row writes and tag writes. The comment "Even if we didn't update the record we advance the cursor" stays meaningful: the cursor reflects how far the batch consumed regardless of how many rows passed the timestamp guard.

`saveCursor(db, cursor)` is the synchronous, transaction-bound overload — confirmed in `homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/CursorStorage.kt`:
```kotlin
    fun saveCursor(db : OdinDatabase, cursor: QueryBatchCursor) {
        db.keyValueQueries.upsertValue(
            key = driveId,
            data_ = cursor.toJson().encodeToByteArray()
        )
    }
```
It writes directly on the `OdinDatabase` handed to the transaction block (does NOT route through `KeyValueWrapper`), so it does NOT open its own transaction — moving it out of the loop keeps it inside the enclosing `withWriteTransaction` and therefore still atomic. `CursorStorage` is a trivial holder of `(databaseManager, driveId)`; constructing it once outside the loop is equally correct.

### Test file — `homebase-api/src/commonTest/kotlin/id/homebase/api/sync/database/MainIndexMetaTest.kt`
1185 lines, `class MainIndexMetaTest` at line 19. Relevant existing tests to model after:
- `testBaseUpsertEntryZapZapWithCursor` (line 207): single-file upsert with a non-null `QueryBatchCursor`, then `CursorStorage(dbm, driveId).loadCursor()` and asserts paging/stop/next fields round-trip. This is the **cursor-persistence** exemplar.
- `performBaseUpsert_stalePush_returnsEmpty_andLeavesRowIntact` (line 906): uses the compact `makeHeader(fileId, driveId, uniqueId, updatedMs, localReactions)` helper (defined line 946) and `processor.baseUpsertEntryZapZap(identityId, driveId, header, null)`. This is the **multi-call / helper** exemplar — reuse `makeHeader` to build the batch.

Test fixtures available: `DatabaseManager({ createInMemoryDatabase() }).use { dbm -> … }`, `MainIndexMetaHelpers.HomebaseFileProcessor(dbm)`, `QueryBatchCursor(paging = TimeRowCursor(time = UnixTimeUtc(…), row = …), stop = …, next = …)`, `CursorStorage(dbm, driveId).loadCursor()`. The batch entry point is the `List<HomebaseFile>` overload: `processor.baseUpsertEntryZapZap(identityId, driveId, fileHeaders /* List */, cursor)` — see lines 166–173 of MainIndexMeta.kt; it delegates straight to `performBaseUpsert`.

### Why the strong "exactly once" assertion needs a driver spy
The `KeyValue` table (`homebase-api/src/commonMain/sqldelight/id/homebase/api/sync/database/KeyValue.sq`) upserts on a single PRIMARY KEY `key = driveId`, so **row count is always 1** whether the save runs once or 1000 times — `countAll`/row asserts CANNOT detect the regression. To prove "exactly one upsert execution per batch" you must count `execute()` calls carrying the KeyValue upsert SQL. The JVM test driver is a plain `JdbcSqliteDriver(IN_MEMORY)` (`homebase-api/src/jvmTest/.../TestDatabaseHelper.jvm.kt`), and `app.cash.sqldelight.db.SqlDriver` is a 7-method interface, so a thin counting decorator around the driver is the right seam (see Step 3). The decorator lives in **jvmTest only** because it depends on the JVM driver factory; the behavioural round-trip test (Step 2) lives in **commonTest** and runs on every target.

### Conventions that apply
- Tests use **fakes/decorators, never Mockito/MockK** (none on the classpath). The Step-3 spy is a hand-written `SqlDriver` decorator — consistent with the repo's fake-based testing.
- `commonTest` for cross-platform tests; `jvmTest` for JVM-only (the driver spy). Both run under `kotlinx.coroutines.test.runTest`.
- No user-facing strings here, so the Konsist `ArchitectureTest` does not apply to this change.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt homebase-api/src/commonTest/kotlin/id/homebase/api/sync/database/MainIndexMetaTest.kt` | empty output (no drift) |
| Compile the module (JVM) — fast gate | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile JVM test sources | `./gradlew :homebase-api:compileTestKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the module's JVM tests | `./gradlew :homebase-api:jvmTest --rerun-tasks` | `BUILD SUCCESSFUL`, all `MainIndexMetaTest` cases pass |
| Run just the new + sibling cursor tests | `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.sync.database.MainIndexMetaTest"` | the new test names appear and pass |
| Confirm cursor save left the loop | `grep -n "saveCursor" homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt` | exactly one match, and it is below the `forEach` closing brace |

(iOS compile `./gradlew :homebase-api:compileKotlinIosSimulatorArm64` is optional and macOS-only; the source change is commonMain and the JVM compile already covers it. The Step-2 test is commonTest so it also compiles for native, but do not gate on a simulator run.)

## Scope
**In scope (modify):**
- `homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt` — move the `if (cursor != null) { saveCursor }` block out of `fileHeaders.forEach`, keep it inside `withWriteTransaction`.
- `homebase-api/src/commonTest/kotlin/id/homebase/api/sync/database/MainIndexMetaTest.kt` — add the multi-file batch cursor-persistence test (Step 2).
- `homebase-api/src/jvmTest/kotlin/id/homebase/api/sync/database/MainIndexCursorSaveCountTest.kt` — NEW jvmTest file with the counting `SqlDriver` decorator + "exactly once" test (Step 3).

**Out of scope (do NOT touch):**
- `homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/CursorStorage.kt` — `saveCursor`/`loadCursor`/`CursorStorage` internals are unchanged; the fix only changes WHERE the existing call runs.
- `KeyValueWrapper.kt`, `KeyValue.sq`, `DatabaseManager.kt` — the storage layer is correct; do not add count APIs to production code (the spy is test-only).
- Any other caller of `saveCursor` (e.g. `DriveSync` / `CursorStorage.saveCursor(cursor)` suspend overload) — only `performBaseUpsert`'s call site moves.
- `baseUpsertEntryZapZap` overloads (lines 166–173, 269–276) — they only delegate; leave them.

## Steps

### Step 0 — drift check
Run the Drift check command above. If it prints any file, open the live `MainIndexMeta.kt` lines 190–256 and compare to the Current state excerpt. If the `if (cursor != null) { … saveCursor … }` block is no longer inside `fileHeaders.forEach` (already fixed), STOP and report "already fixed". Otherwise continue.

### Step 1 — move the cursor save out of the loop
In `homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt`, inside `performBaseUpsert`:

1. Delete these five lines from the END of the `fileHeaders.forEach { fileHeader -> … }` body (currently lines 250–254):
```kotlin
                    // Even if we didn't update the record we advance the cursor
                    if (cursor != null) {
                        val cursorStorage = CursorStorage(databaseManager, driveId)
                        cursorStorage.saveCursor(db, cursor)
                    }
```
After deletion the `forEach` body's last statement is the `if (n > 0L) { … }` block (its closing `}` currently at line 248), immediately followed by the `forEach` closing `}` (currently line 255).

2. Insert the cursor save ONCE, after the `forEach` closing brace and before the `withWriteTransaction` closing brace (i.e. between current lines 255 and 256). Construct `CursorStorage` once outside the loop:
```kotlin
                }
                // Advance the cursor once per batch — the loop writes all rows,
                // then we persist how far this batch consumed (independent of how
                // many rows passed the DriveMainIndex timestamp guard). Still inside
                // withWriteTransaction, so it commits atomically with the row writes.
                if (cursor != null) {
                    CursorStorage(databaseManager, driveId).saveCursor(db, cursor)
                }
            }
            return written
```
Match the surrounding indentation exactly (the `forEach` body was indented one level deeper than the `withWriteTransaction` body; the new `if` sits at the `withWriteTransaction`-body indentation, same level as `fileHeaders.forEach`).

Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
Verify: `grep -n "saveCursor" homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt` -> exactly ONE match, located below the `forEach`'s closing brace (manually confirm it is no longer inside the loop).

### Step 2 — add the multi-file batch cursor-persistence test (commonTest)
This is the behavioural regression guard: a batch of N>1 files with a non-null cursor must still persist that cursor with its final value (and must persist even when some files are rejected by the guard). Append a new `@Test` to `MainIndexMetaTest` in `homebase-api/src/commonTest/kotlin/id/homebase/api/sync/database/MainIndexMetaTest.kt`, modelled on `testBaseUpsertEntryZapZapWithCursor` (cursor round-trip) and reusing the existing `makeHeader(...)` helper (line 946). Add it just below `performBaseUpsert_stalePush_returnsEmpty_andLeavesRowIntact` (after line 939) so `makeHeader` is in scope.

```kotlin
    /**
     * Regression guard for Plan 007: the batch upsert persists the cursor ONCE
     * for the whole batch (the save was moved out of the per-file loop). A
     * multi-file batch with a non-null cursor must still leave the cursor
     * round-tripping field-for-field — proving the move didn't drop the save.
     */
    @Test
    fun batchUpsert_persistsCursorWithFinalValue() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

            // A batch of distinct files — this is the case that previously issued
            // one cursor upsert PER file.
            val batch = (1..5).map {
                makeHeader(
                    fileId = Uuid.random(),
                    driveId = driveId,
                    uniqueId = Uuid.random(),
                    updatedMs = 1_000L + it,
                    localReactions = null,
                )
            }

            val cursor = QueryBatchCursor(
                paging = TimeRowCursor(time = UnixTimeUtc(1704067200000L), row = 12345L),
                stop = TimeRowCursor(time = UnixTimeUtc(1704153600000L), row = 67890L),
                next = TimeRowCursor(time = UnixTimeUtc(1704240000000L), row = 11111L),
            )

            val written = processor.baseUpsertEntryZapZap(identityId, driveId, batch, cursor)
            assertEquals(5, written.size, "all five distinct files should be written")

            val loaded = CursorStorage(dbm, driveId).loadCursor()
            assertNotNull(loaded, "cursor must persist after a multi-file batch")
            assertEquals(cursor.paging!!.time, loaded.paging!!.time)
            assertEquals(cursor.paging.row, loaded.paging.row)
            assertEquals(cursor.stop!!.time, loaded.stop!!.time)
            assertEquals(cursor.stop.row, loaded.stop.row)
            assertEquals(cursor.next!!.time, loaded.next!!.time)
            assertEquals(cursor.next.row, loaded.next.row)
        }
    }
```
No new imports needed: `QueryBatchCursor`, `TimeRowCursor`, `UnixTimeUtc`, `CursorStorage`, `assertEquals`, `assertNotNull`, `Uuid`, `runTest` are all already imported (lines 5–17 of the test file).

Verify: `./gradlew :homebase-api:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.
Verify: `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.sync.database.MainIndexMetaTest"` -> `batchUpsert_persistsCursorWithFinalValue` passes.

### Step 3 — add the "exactly once" execution-count test (jvmTest, with a counting driver decorator)
This is the test that would FAIL against the pre-fix code (5 files → 5 KeyValue upserts) and PASS after (5 files → 1). Create a NEW file `homebase-api/src/jvmTest/kotlin/id/homebase/api/sync/database/MainIndexCursorSaveCountTest.kt`. The decorator wraps `JdbcSqliteDriver(IN_MEMORY)` and counts `execute(...)` calls whose SQL is the KeyValue upsert. All other `SqlDriver` methods delegate verbatim.

```kotlin
package id.homebase.api.sync.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.client.drives.HomebaseFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Plan 007 — proves the sync cursor is upserted into KeyValue exactly ONCE per
 * batch, not once per file. The KeyValue table upserts on a single PRIMARY KEY,
 * so row count can't catch the regression; we count execute() calls carrying the
 * KeyValue upsert SQL instead. Against the pre-fix code this counted N (one per
 * file); after the fix it counts 1.
 */
class MainIndexCursorSaveCountTest {

    /** Delegates every SqlDriver call; counts executes whose SQL inserts into KeyValue. */
    private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var keyValueUpsertCount = 0
            private set

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            if (sql.contains("INSERT INTO KeyValue", ignoreCase = true)) {
                keyValueUpsertCount++
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    @Test
    fun cursorSavedExactlyOncePerBatch() = runTest {
        val counting = CountingDriver(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OdinDatabase.Schema.create(it) }
        )
        DatabaseManager({ counting }).use { dbm ->
            val identityId = Uuid.random()
            val driveId = Uuid.random()
            val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)

            val batch = (1..5).map { i ->
                makeHeader(Uuid.random(), driveId, Uuid.random(), updatedMs = 1_000L + i)
            }
            val cursor = QueryBatchCursor(
                paging = TimeRowCursor(time = UnixTimeUtc(1704067200000L), row = 1L),
                stop = TimeRowCursor(time = UnixTimeUtc(1704153600000L), row = 2L),
                next = TimeRowCursor(time = UnixTimeUtc(1704240000000L), row = 3L),
            )

            processor.baseUpsertEntryZapZap(identityId, driveId, batch, cursor)

            assertEquals(
                1,
                counting.keyValueUpsertCount,
                "cursor must be upserted once per batch, not once per file",
            )
        }
    }

    private fun makeHeader(
        fileId: Uuid,
        driveId: Uuid,
        uniqueId: Uuid,
        updatedMs: Long,
    ): HomebaseFile {
        val json = """{
            "fileId": "${fileId}",
            "driveId": "${driveId}",
            "fileState": "active",
            "fileSystemType": "standard",
            "serverFileIsEncrypted":"true",
            "keyHeader" : {
                "iv" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ],
                "aesKey" : { "bytes" : [ 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 ] }
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": $updatedMs,
                "updated": $updatedMs,
                "transitCreated": 0,
                "transitUpdated": 0,
                "serverFileIsEncrypted": true,
                "senderOdinId": "test.sender",
                "originalAuthor": "test.sender",
                "appData": {
                    "uniqueId": "${uniqueId}",
                    "tags": null,
                    "fileType": 1,
                    "dataType": 1,
                    "groupId": null,
                    "userDate": $updatedMs,
                    "content": "test content",
                    "archivalStatus": 1
                },
                "localAppData": null,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "1355aa19-2031-d800-403d-e8696a8be494",
                "payloads": [],
                "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {
                    "requiredSecurityGroup": "owner",
                    "circleIdList": null,
                    "odinIdList": null
                },
                "doNotIndex": false,
                "allowDistribution": false,
                "fileSystemType": "standard",
                "fileByteCount": 1000,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 1000
        }"""
        return OdinSystemSerializer.deserialize<HomebaseFile>(json)
    }
}
```

Notes for the executor:
- The `by delegate` interface-delegation handles `executeQuery`, `newTransaction`, `currentTransaction`, `addListener`, `removeListener`, `notifyListeners`; only `execute` is overridden. If the Kotlin compiler reports a clash because `execute` is both delegated and overridden, that is expected and fine — the explicit `override` wins. If instead it errors that `execute` cannot be both inherited-by-delegation and overridden, remove `: SqlDriver by delegate` and implement all 7 methods by forwarding to `delegate` (signatures are in the SqlDriver interface; each just calls `delegate.<same method>(...)`). Try the `by delegate` form first.
- `DatabaseManager({ counting })` reuses the same constructor shape as the other tests (`DatabaseManager({ createInMemoryDatabase() })`), passing the already-decorated driver. Do NOT call `OdinDatabase.Schema.create` twice — it is created once on the raw driver before wrapping (as shown).
- The SQL match string `"INSERT INTO KeyValue"` is the exact text emitted by the `upsertValue` query in `KeyValue.sq`. If the compiled SQL differs (e.g. quoting), relax to `sql.contains("KeyValue", ignoreCase = true) && sql.contains("INSERT", ignoreCase = true)`.

Verify: `./gradlew :homebase-api:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.
Verify: `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.sync.database.MainIndexCursorSaveCountTest"` -> `cursorSavedExactlyOncePerBatch` passes.
Optional confidence check (do NOT commit this): temporarily revert Step 1 (put the save back in the loop) and rerun this test — it must FAIL with `expected: 1 but was: 5`. Re-apply Step 1 before continuing.

### Step 4 — full module test pass
Verify: `./gradlew :homebase-api:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL`, all `MainIndexMetaTest` cases plus the new `MainIndexCursorSaveCountTest` pass, nothing else regressed.

### Step 5 — update plans/README.md
If `plans/README.md` does not exist, create it with a table header, then add this plan's row. If it exists, append/update the row for plan 007. Row content:
`| 007 | Save sync cursor once per batch in performBaseUpsert (was once per file) | P2 | S | LOW | Done |`
Header (only if creating the file):
`| Plan | Title | Priority | Effort | Risk | Status |`
`|---|---|---|---|---|---|`
Verify: `grep -n "007" plans/README.md` -> the row is present.

## Test plan
- **New (commonTest):** `MainIndexMetaTest.batchUpsert_persistsCursorWithFinalValue` — a 5-file batch + non-null cursor; asserts all 5 written and the cursor round-trips field-for-field via `CursorStorage.loadCursor()`. Guards against the move dropping the save entirely. Modelled after `testBaseUpsertEntryZapZapWithCursor` + reuses `makeHeader`.
- **New (jvmTest):** `MainIndexCursorSaveCountTest.cursorSavedExactlyOncePerBatch` — a 5-file batch + non-null cursor through a counting `SqlDriver` decorator; asserts exactly **1** KeyValue upsert execution. This is THE regression test: it returns 5 against the buggy per-file code and 1 after the fix.
- **Existing coverage retained:** `testBaseUpsertEntryZapZapWithCursor` (single-file persistence) and `testBaseUpsertEntryZapZapWithNullCursor` (null cursor → no save) still pass, confirming the single-file and null-cursor paths are unchanged.
- Verify command: `./gradlew :homebase-api:jvmTest --rerun-tasks`.

## Done criteria
- [ ] `git diff --stat 45e2832e..HEAD -- <in-scope paths>` at Step 0 showed no pre-existing drift (or drift was reconciled).
- [ ] `grep -n "saveCursor" homebase-api/src/commonMain/kotlin/id/homebase/api/sync/database/MainIndexMeta.kt` returns exactly one match, and it sits AFTER the `fileHeaders.forEach` closing brace (not inside the loop).
- [ ] `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:compileTestKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:jvmTest --rerun-tasks` -> `BUILD SUCCESSFUL`; the 2 new tests pass and no existing `MainIndexMetaTest` case regresses.
- [ ] `git status` shows ONLY: `MainIndexMeta.kt`, `MainIndexMetaTest.kt`, new `MainIndexCursorSaveCountTest.kt`, and `plans/README.md` (+ this plan file) — no other source files modified.
- [ ] `plans/README.md` contains the 007 row.

## STOP conditions
- Drift check at Step 0 lists either in-scope file and the live `performBaseUpsert` no longer matches the Current state excerpt — STOP and report (likely already fixed, or refactored).
- The cursor save block is NOT found inside `fileHeaders.forEach` (already moved) — STOP, report "already fixed", do not duplicate.
- Any verification command fails twice in a row after a genuine retry — STOP and report the exact failure output.
- The `SqlDriver by delegate` decorator cannot compile even after falling back to forwarding all 7 methods (e.g. the SqlDriver interface signature differs from this plan) — STOP and report; do NOT add a counting hook to production `KeyValueWrapper`/`DatabaseManager` (that is out of scope).
- The fix would require touching `CursorStorage`, `KeyValueWrapper`, `DatabaseManager`, or `KeyValue.sq` to work — STOP; the move must be self-contained in `performBaseUpsert`.

## Maintenance notes
- A future change that introduces a per-file cursor (e.g. advancing the cursor incrementally so a crash mid-batch resumes partway) would intentionally reintroduce a per-file save — at which point `cursorSavedExactlyOncePerBatch` should be updated/removed deliberately, not silenced. The current contract is "one batch = one cursor position," which matches how `DriveWebSocketUpsertWorker`/sync consume `BatchReceived`.
- Reviewer scrutiny: confirm the moved save is still INSIDE `withWriteTransaction` (atomic with row writes) — if it accidentally lands after the closing `}` of `withWriteTransaction`, the cursor would commit in a separate transaction, breaking the all-or-nothing guarantee. The `grep` + the `batchUpsert_persistsCursorWithFinalValue` test catch a dropped save, but only a manual brace check catches "moved outside the transaction."
- The Step-3 decorator's SQL match (`INSERT INTO KeyValue`) is coupled to `KeyValue.sq`'s `upsertValue` text; if that query is renamed/rewritten the match string must follow. Keep the fallback substring match noted in Step 3 in mind.
- Deferred follow-up (out of scope here): the same per-file vs per-batch shape should be audited in any other batch-upsert path that writes a single progress key inside a `forEach`; this plan only fixes `performBaseUpsert`.
