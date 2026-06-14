# Plan 004: Make MomentsFeedService.byId thread-safe

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsFeedService.kt homebase-core/src/jvmTest/kotlin/id/homebase/core/moments/`. If any in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P1
- Effort: S
- Risk: LOW
- Depends on: none
- Category: bug
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`MomentsFeedService` keeps the whole in-memory moments feed in a plain `mutableMapOf<Uuid, MomentFeedItem>()` (`byId`) that is read and written from **multiple coroutines running concurrently on `Dispatchers.Default`** (a genuinely multi-threaded dispatcher). The injected `scope` is `CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)` (`homebase-api/src/commonMain/kotlin/id/homebase/api/di/ApiModule.kt:57`). At least four code paths touch `byId` without any synchronization: the initial `coldLoad()` (clears + bulk-fills), the EventBus collector's `processIncrementalBatch()` (per-file put/remove), a **nested** `coldLoad()` re-launch fired from inside that same collector on `DriveEvent.Stopped`, and the `OptimisticRollback` branch (remove). `emitSorted()` then iterates `byId.values.sortedByDescending { … }` while another thread is mutating the same map. On JVM that throws `ConcurrentModificationException` mid-sort; on Kotlin/Native (iOS) concurrent structural mutation of a `LinkedHashMap` corrupts the map's internal links — silent data loss or a hard crash. The cost is intermittent feed crashes / vanished moments that are hard to reproduce and impossible to root-cause from the symptom. The fix confines every read and write of `byId` (plus the snapshot taken by `emitSorted`) under one `kotlinx.coroutines.sync.Mutex`, the smallest change that preserves the existing structure.

## Current state

### File 1 — the only production file to modify
`homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsFeedService.kt`

The unsynchronized shared state, declared at **line 65**:

```kotlin
// Keyed by uniqueId for O(1) upsert/remove. Sorted into a list whenever
// we emit. Newest-first ordering by userDate matches the chat-message
// ordering and the spec's "vertical chronological feed."
private val byId = mutableMapOf<Uuid, MomentFeedItem>()
```

Concurrent writers / readers, all on the injected `Dispatchers.Default` scope:

1. **`coldLoad()` launch** — `start()` body, **line 101**:
   ```kotlin
   scope.launch { coldLoad() }
   ```
2. **EventBus collector launch** — `start()` body, **lines 103-147** (`scope.launch { eventBus.events.collect { event -> when (event) { … } } }`). Inside it:
   - `BatchReceived` → `processIncrementalBatch(event.batchData)` (**line 110**).
   - `DriveEvent.Stopped(totalCount > 0)` → a **nested** `scope.launch { coldLoad() }` (**line 128**).
   - `OptimisticRollback` → `byId.remove(event.uniqueId)` then `emitSorted()` (**lines 137-142**).
   - `SessionEnded` → `reset()` (**line 107**).
3. **`coldLoad()` body** mutates `byId` at **lines 171-185** (`byId.clear()` then the `for` loop `byId[item.id] = item`) and calls `emitSorted()` at **line 190**:
   ```kotlin
   byId.clear()
   …
   for (file in result.records) {
       …
       byId[item.id] = item
   }
   …
   emitSorted()
   ```
   Note: everything *above* `byId.clear()` (lines 156-169: `credentialsManager.getActiveCredentials()`, the heavy `QueryBatch(...).queryBatchAsync(...)` DB call) is I/O that must **stay outside** the lock — only the mutation section (clear + loop + the log + `emitSorted`) goes inside.
4. **`processIncrementalBatch(files)` body**, **lines 202-254** — reads `byId.size`, `byId.containsKey`, mutates `byId.remove` / `byId[uniqueId] = item`, calls `emitSorted()` at line 253. This whole function body must run under the lock.
5. **`emitSorted()`** — **lines 256-258**:
   ```kotlin
   private fun emitSorted() {
       _feed.value = byId.values.sortedByDescending { it.userDateMs }
   }
   ```
   This is the reader that crashes. The **snapshot** `byId.values.toList()` must be taken inside the lock; the sort and the `_feed.value = …` assignment may stay outside (`MutableStateFlow.value` is itself thread-safe).
6. **`reset()`** — **lines 265-268**:
   ```kotlin
   fun reset() {
       byId.clear()
       _feed.value = emptyList()
   }
   ```
   `byId.clear()` is a structural mutation and must be under the lock.

### Convention that applies (verified against this repo)
- `kotlinx.coroutines.sync.Mutex` + `withLock` is the established cross-platform mutual-exclusion primitive here. Exemplar imports already in the codebase: `homebase-api/src/commonMain/kotlin/id/homebase/api/sync/DriveSyncManager.kt:24-25` and `homebase-api/src/commonMain/kotlin/id/homebase/api/video/VideoPreloader.kt:8-9`:
  ```kotlin
  import kotlinx.coroutines.sync.Mutex
  import kotlinx.coroutines.sync.withLock
  ```
- **Re-entrancy hazard:** `kotlinx.coroutines.sync.Mutex` is **NOT** re-entrant. Calling `mutex.withLock { … }` from inside another `mutex.withLock { … }` on the same coroutine **deadlocks**. Therefore `emitSorted()` must **not** itself acquire the lock when it is already called from inside a locked section (`coldLoad`, `processIncrementalBatch`, the `OptimisticRollback` branch). The chosen design: make `emitSorted()` lock-free (it only takes a snapshot that callers already hold the lock for) — every call site to `emitSorted()` is wrapped so the snapshot read happens under the lock. See Steps for the exact shape that avoids any nested `withLock`.

### Test model to copy
- Same module, same construction pattern: `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/vault/VaultStreamTest.kt`. Its `TestScope.createStream()` (lines 141-152) builds the service-under-test with a real `CredentialsManager()` (no active credentials → the cold-load returns early without touching the DB), a real `EventBus()`, a no-op `DatabaseManager(driverProvider = { stubDriver })`, and `scope = backgroundScope`. Copy its `stubDriver` (lines 94-135) verbatim. `MomentsFeedService` takes one extra constructor arg over `VaultStream`: `userStateStore: MomentsUserStateStore` — construct a real `MomentsUserStateStore` (see Step 4 for resolving its constructor).
- `HomebaseFile` builder model: `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/vault/VaultEntryTest.kt:58-92` (`buildHomebaseFile`). Adapt it to stamp `appData.fileType = MomentsProtocol.MomentPostFileType` (= 7050, `MomentsProtocol.kt:9`) and a non-null `appData.uniqueId`, which are the two requirements for a file to land in `byId` via `processIncrementalBatch` (filtered at line 215-217, uniqueId checked at 222-223).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run FIRST) | `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsFeedService.kt homebase-core/src/jvmTest/kotlin/id/homebase/core/moments/` | empty output (no in-scope file changed); if non-empty, reconcile against Current state before proceeding |
| Compile production change (JVM) | `./gradlew :homebase-core:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile Android target | `./gradlew :homebase-core:compileAndroidMain` | `BUILD SUCCESSFUL` |
| Compile iOS target (macOS host only) | `./gradlew :homebase-core:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` (skip if not on macOS; note it as skipped) |
| Run the new + existing jvm tests | `./gradlew :homebase-core:jvmTest --rerun-tasks` | `BUILD SUCCESSFUL`, new test class green |
| Confirm no nested-lock typo | `grep -n "withLock" homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsFeedService.kt` | each `withLock` is at the top level of its function, none inside another |

## Scope
**In scope (modify):**
- `homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsFeedService.kt` — add the `Mutex`, wrap all `byId` access.

**In scope (create):**
- `homebase-core/src/jvmTest/kotlin/id/homebase/core/moments/MomentsFeedServiceConcurrencyTest.kt` — the regression test.
- `plans/README.md` — only the plan-index row for this plan (create the file if it does not exist; see Step 7).

**Out of scope (do NOT touch):**
- `homebase-api/src/commonMain/kotlin/id/homebase/api/di/ApiModule.kt` — the multi-threaded scope is the *correct* environment; the fix is to make the service safe in it, not to single-thread the scope.
- `homebase-api/src/commonMain/kotlin/id/homebase/api/client/eventbus/EventBus.kt` / `BackendEvent.kt` — the event bus is shared infrastructure; widening its contract is unrelated.
- `MomentsUserStateStore`, `MomentCommentsService`, `MomentGroupService`, `MomentActionService` — they may have their own concurrency questions, but each is a separate change; this plan is `byId`-only.
- The `MomentFeedItem` data class and `HomebaseFile.toFeedItem()` mapper (lines 277-412) — pure, no shared mutable state.

## Steps

**Step 0 — Drift check.** Run the Drift-check command above. If it prints any in-scope file, open that file and confirm the line numbers / code in Current state still match; if they don't, STOP and report.

**Step 1 — Add the Mutex field and imports.**
In `MomentsFeedService.kt`, add the two imports next to the existing coroutine imports (around lines 19-26):
```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```
Immediately after the `byId` declaration (line 65), add:
```kotlin
// Guards every read and write of [byId]. The service's injected scope is
// Dispatchers.Default (multi-threaded), and coldLoad / processIncrementalBatch
// / the OptimisticRollback handler / reset all mutate byId concurrently while
// emitSorted iterates it. NOT re-entrant: never call one locked section from
// inside another (see emitSorted, which is deliberately lock-free).
private val byIdMutex = Mutex()
```
Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL` (the field is unused so far but valid).

**Step 2 — Make `emitSorted()` lock-free and rename it for clarity.**
The crash is in the *iteration* `byId.values.sortedByDescending`. Split it so the **snapshot** is the only `byId` read, and the caller holds the lock when calling it. Replace the body of `emitSorted()` (lines 256-258) with a helper that assumes the lock is already held:
```kotlin
/**
 * Snapshot [byId] and publish the sorted feed. CALLER MUST HOLD [byIdMutex] —
 * this reads byId.values without locking to avoid a non-re-entrant deadlock
 * when called from inside an already-locked section (coldLoad,
 * processIncrementalBatch, the OptimisticRollback handler).
 */
private fun emitSortedLocked() {
    val snapshot = byId.values.toList()
    _feed.value = snapshot.sortedByDescending { it.userDateMs }
}
```
Rename every call site of `emitSorted()` to `emitSortedLocked()` (there are three: line 141 in the `OptimisticRollback` branch, line 190 in `coldLoad`, line 253 in `processIncrementalBatch`). Each rename happens together with wrapping its enclosing region in `withLock` in the next steps, so after this step the call sites read `emitSortedLocked()` but are not yet all inside a lock — that is fine for compilation; do not run tests between Step 2 and Step 5, only compile.
Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

**Step 3 — Lock the mutation section of `coldLoad()`.**
`coldLoad()` is already `suspend`. Wrap only the in-memory mutation region (the current lines 171-190: `byId.clear()` through `emitSortedLocked()`), leaving the credentials fetch and `queryBatchAsync` (lines 156-169) outside the lock. Result shape:
```kotlin
val result = QueryBatch(identityId).queryBatchAsync( … )   // unchanged, OUTSIDE lock

byIdMutex.withLock {
    byId.clear()
    var skippedSoftDeleted = 0
    var skippedUnparseable = 0
    for (file in result.records) {
        …                       // unchanged loop body
        byId[item.id] = item
    }
    Logger.i(tag = TAG) { … }   // the existing coldLoad log, references byId.size — keep INSIDE
    emitSortedLocked()
}
```
Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

**Step 4 — Lock `processIncrementalBatch()` and the `OptimisticRollback` handler.**
`processIncrementalBatch` is currently a non-suspend `private fun` (line 202). Change it to `private suspend fun processIncrementalBatch(files: List<HomebaseFile>)` and wrap its **entire body** (lines 203-253) in `byIdMutex.withLock { … }`. The call site at line 110 is already inside the suspend `collect { }` lambda, so calling a suspend function there compiles unchanged.
For the `OptimisticRollback` branch (lines 136-142), wrap the `byId.remove` + log + `emitSortedLocked()` in the lock:
```kotlin
is BackendEvent.OutboxEvent.OptimisticRollback -> {
    if (event.driveId != drive) return@collect
    byIdMutex.withLock {
        if (byId.remove(event.uniqueId) != null) {
            Logger.d(tag = TAG) { "OptimisticRollback: removed moment=${event.uniqueId}" }
            emitSortedLocked()
        }
    }
}
```
The `collect { }` lambda is a suspend context, so `withLock` is legal here.
Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

**Step 5 — Lock `reset()`.**
`reset()` is currently a non-suspend `fun` (line 265) called from the `SessionEnded` branch (line 107, a suspend context) AND potentially synchronously elsewhere. Check call sites first:
`grep -rn "\.reset()" homebase-core/src/commonMain --include=*.kt | grep -i moment` and confirm every caller of `MomentsFeedService.reset()` is in a coroutine/suspend context. The only in-repo caller is the `SessionEnded` branch at line 107 (inside `collect`). Make `reset()` a `private suspend fun` (it is only called internally from the collector) OR, if any non-suspend caller exists, keep `reset()` non-suspend and have it do a non-blocking lock acquisition. Default: make it `private suspend fun reset()` and wrap the body:
```kotlin
private suspend fun reset() {
    byIdMutex.withLock {
        byId.clear()
        _feed.value = emptyList()
    }
}
```
If the grep reveals a non-suspend external caller (STOP condition — out of scope), do not change the signature; instead report it.
Verify: `./gradlew :homebase-core:compileKotlinJvm` then `:compileAndroidMain` → both `BUILD SUCCESSFUL`. On a macOS host also run `:compileKotlinIosSimulatorArm64`.
Then run the nested-lock grep from the Commands table and eyeball that no `withLock` appears inside another `withLock`.

**Step 6 — Add the regression test.**
Create `homebase-core/src/jvmTest/kotlin/id/homebase/core/moments/MomentsFeedServiceConcurrencyTest.kt`. Model the service construction on `VaultStreamTest.createStream()` and the `HomebaseFile` builder on `VaultEntryTest.buildHomebaseFile`. The test must:
1. Build the service with a real `CredentialsManager()`, real `EventBus()`, `DatabaseManager(driverProvider = { stubDriver })` (copy `stubDriver` from `VaultStreamTest`), a real `MomentsUserStateStore` (resolve its constructor via `grep -n "class MomentsUserStateStore(" homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsUserStateStore.kt` and supply real/fake collaborators the same way `createStream` does; if it needs more than `CredentialsManager`/`DatabaseManager`/`EventBus`, construct those minimally — none of them are exercised because no credentials are active), and `scope = backgroundScope`.
2. Call `service.start()` (this launches `coldLoad` — which returns early because `CredentialsManager()` has no active credentials — plus the collector).
3. Drive the two concurrent writers HARD: from many `launch(Dispatchers.Default)` (use the real dispatcher, not the test dispatcher, to surface the cross-thread race — `kotlinx.coroutines.Dispatchers`), repeatedly `eventBus.emit(BackendEvent.DataEvent.BatchReceived(driveId = momentsLabeledDrive.drive.alias, batchData = listOf(buildMomentFile(uniqueId = …))))`. Interleave: half the coroutines emit `BatchReceived` with fresh uniqueIds (adds) and the other half emit `BatchReceived` carrying a soft-deleted file with a previously-used uniqueId (removes), plus a few `OptimisticRollback` events. Use e.g. 8 coroutines × 200 iterations.
4. `advanceUntilIdle()` then `awaitAll`/`joinAll` the launches, and collect `service.feed` via `service.feed.value`.
5. Assert: **no exception was thrown** (the test simply completing without `ConcurrentModificationException` is the core assertion), and the final `service.feed.value` is internally consistent — its size equals the number of distinct still-present uniqueIds, and it is sorted descending by `userDateMs` (`assertEquals(feed, feed.sortedByDescending { it.userDateMs })`).

`buildMomentFile` helper requirements (so the file actually flows into `byId`):
- `fileMetadata.appData.fileType = MomentsProtocol.MomentPostFileType` (import `id.homebase.core.moments.services.MomentsProtocol`).
- `fileMetadata.appData.uniqueId = <the test uniqueId>` (non-null — required at line 222-223).
- `fileMetadata.appData.content = OdinSystemSerializer.serialize(MomentPostContent(version = 1, description = "m"))` (or leave `content = null`; `toFeedItem` tolerates null content, defaulting description to ""). `MomentPostContent`'s only required args are `version: Int` and `description: String`.
- For a "remove" file: set `fileState = FileState.Deleted` so `isSoftDeleted()` is true (line 39-41) and the same `uniqueId` so `processIncrementalBatch` removes it (line 229-235).
- `driveId` of the `BatchReceived` event must equal `momentsLabeledDrive.drive.alias` (import `id.homebase.core.config.momentsLabeledDrive`), or the collector early-returns at line 109. The `HomebaseFile.driveId` field itself is not checked by `processIncrementalBatch`, only the event's `driveId`.

Use `@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)` and `runTest { … }`, mirroring `VaultStreamTest:1`.
Verify: `./gradlew :homebase-core:jvmTest --rerun-tasks` → `BUILD SUCCESSFUL`, the new class reported as passed.

**Step 7 — Update the plan index.**
If `plans/README.md` does not exist, create it with a markdown table header `| Plan | Title | Priority | Status |` and a row for plan 001 (`[001](001-crypto-csprng.md) | Route all crypto random bytes through a CSPRNG | P1 | planned`) and this plan. If it exists, add/update only this plan's row:
`| [004](004-momentsfeed-thread-confinement.md) | Make MomentsFeedService.byId thread-safe | P1 | done |`
Verify: `git status --short plans/` shows `plans/004-momentsfeed-thread-confinement.md` and `plans/README.md` only.

## Test plan
- **New test file:** `homebase-core/src/jvmTest/kotlin/id/homebase/core/moments/MomentsFeedServiceConcurrencyTest.kt`.
- **Cases:**
  1. `concurrentBatchesAndRollbacks_doNotCrashOrCorrupt` — the core regression: 8 coroutines on `Dispatchers.Default` interleaving `BatchReceived` adds, soft-delete removes, and `OptimisticRollback`s against a single started service; asserts the run completes with **no `ConcurrentModificationException`** and the final feed is consistent + sorted. This is the exact failure mode (concurrent mutation during `emitSorted`'s `sortedByDescending`) the fix removes; before the fix this test fails intermittently with CME, after it passes deterministically.
  2. `feedRemainsSortedDescendingByUserDate` — after a mixed concurrent run, `feed.value == feed.value.sortedByDescending { it.userDateMs }`.
  3. (optional, cheap) `addThenSoftDeleteSameMoment_leavesFeedEmpty` — single-threaded sanity that a `BatchReceived(add)` followed by a `BatchReceived(soft-deleted, same uniqueId)` nets to an empty feed, proving the helper files actually flow through `processIncrementalBatch`.
- **Model after:** `VaultStreamTest` (construction + `stubDriver`) and `VaultEntryTest` (`HomebaseFile` builder).
- **Verify command:** `./gradlew :homebase-core:jvmTest --rerun-tasks`.

## Done criteria
- [ ] `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-core:compileAndroidMain` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-core:compileKotlinIosSimulatorArm64` → `BUILD SUCCESSFUL` (macOS host) — or recorded as skipped with reason if not on macOS.
- [ ] `./gradlew :homebase-core:jvmTest --rerun-tasks` → `BUILD SUCCESSFUL`; `MomentsFeedServiceConcurrencyTest` runs with ≥2 passing test methods.
- [ ] `grep -c "byIdMutex.withLock" homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsFeedService.kt` returns `4` (coldLoad mutation region, processIncrementalBatch, OptimisticRollback branch, reset).
- [ ] `grep -n "private fun emitSorted\b" homebase-core/src/commonMain/kotlin/id/homebase/core/moments/services/MomentsFeedService.kt` returns nothing (old name gone; only `emitSortedLocked` remains).
- [ ] No `withLock` is lexically nested inside another `withLock` (manual confirm via the nested-lock grep).
- [ ] `git status --short` shows only: `MomentsFeedService.kt`, `MomentsFeedServiceConcurrencyTest.kt`, `plans/004-momentsfeed-thread-confinement.md`, `plans/README.md` (plus any pre-existing unrelated dirty files already present at plan start — do NOT stage those).
- [ ] plans/README.md row for plan 004 present and reads `done`.

## STOP conditions
- **Drift:** Step 0 shows an in-scope file changed since commit 45e2832e and the Current state excerpts no longer match live code.
- **Non-suspend external caller of `reset()`:** Step 5's grep finds a caller of `MomentsFeedService.reset()` outside a coroutine/suspend context — making `reset()` suspend would break that caller (out-of-scope file). STOP and report; the fallback (non-blocking lock in a non-suspend `reset`) needs a design decision.
- **`MomentsUserStateStore` constructor needs a heavy collaborator** that cannot be built with the same throwaway fakes `VaultStreamTest` uses (e.g. it requires a configured Ktor client or a non-trivial outbox) — STOP and report rather than expanding the fake surface; the test can instead drive concurrency through a narrower seam if so.
- **Any verification command fails twice** after a genuine fix attempt (not a typo you can immediately see).
- **The fix appears to need an out-of-scope file** (ApiModule, EventBus, BackendEvent) to compile — STOP; that means the chosen seam is wrong.

## Maintenance notes
- **Future writers to `byId` must go through `byIdMutex`.** Anyone adding a new EventBus branch that touches `byId` (or a new public mutator) has to wrap it in `byIdMutex.withLock` and call `emitSortedLocked()` (not a fresh lock-free read). A reviewer should grep for every `byId` reference and confirm each is inside a `withLock` block.
- **Do not reintroduce a re-entrant call.** `emitSortedLocked()` must stay lock-free; if someone "tidies" it by adding `byIdMutex.withLock` inside it, every caller deadlocks. The doc comment on `emitSortedLocked` is the guardrail — keep it.
- **`unseenCount` (lines 77-81) reads `feed`, not `byId`,** so it is already safe (it derives from the published `StateFlow`). No lock needed there.
- **Sibling services** (`MomentCommentsService`, `MomentGroupService`) follow the same cold-load + EventBus pattern over their own plain maps and almost certainly have the identical race. They are deliberately out of scope here; file follow-up plans to apply the same `Mutex` confinement once this lands and the pattern is proven.
- **Test flakiness signal:** the concurrency test uses the *real* `Dispatchers.Default` to surface cross-thread ordering. If it ever flakes *after* this fix, that indicates a missed `byId` access path — investigate the new path, do not add retries or `@Ignore`.
