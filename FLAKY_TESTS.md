# Flaky Tests Registry

A running log of intermittent (non-deterministic) test failures, their root
causes, the fix that stabilised them, and how to tell if they reappear.

Add an entry when you spend time chasing a test that fails intermittently —
even after you fix it. The point is twofold:

1. If the same test starts failing again, the next person finds the prior
   investigation instead of starting from zero.
2. If a "fixed" flake reappears, that's a signal the root cause was deeper than
   we thought — note the recurrence rather than just re-applying the old patch.

**Status legend:** `Stabilised` (fix landed, not seen since) ·
`Watching` (fix landed recently, still confirming) · `Open` (reproduces, no fix yet).

---

## OutboxSyncTest — `ConcurrentModificationException at null:-1` (iOS sim)

- **Status:** Stabilised — fix on branch `generic-video-compressor` (PR #623), green on the iOS leg.
- **Where:** `homebase-api/src/commonTest/.../sync/database/OutboxSyncTest.kt`
- **Target:** `iosSimulatorArm64Test` only. JVM / Android / wasm never reproduced it.
- **Symptom:** Intermittent failures such as
  ```
  testPermanentFailure_NotFoundExceptionDroppedOnFirstAttempt[iosSimulatorArm64] FAILED
      kotlin.ConcurrentModificationException at null:-1
  ```
  The failing *case* varies run to run (it's a timing race, not specific to any
  one test); occasionally it surfaced as a `DarwinGlobalQueueDispatcher` segfault
  instead of a CME. All cases passed on retry.

- **Root cause:** A Kotlin/Native test-teardown race — **not** a production bug.
  `OutboxSync`'s worker coroutines run on `runTest`'s `backgroundScope` (virtual
  time), but every DB call hops to a *real* dispatcher via `OutboxWrapper` →
  `DatabaseManager.withWriteValue`/`readValue`
  (`Dispatchers.Default.limitedParallelism(1)` for writes, `Dispatchers.IO` for
  reads). `advanceUntilIdle()` only drains the *virtual* scheduler, so a coroutine
  parked inside `withContext(realDispatcher)` looks idle and the test body
  proceeds while real-thread DB work is still in flight. When the `runTest` block
  returned and `db.close()` ran, that late work raced `NativeSqliteDriver`'s
  connection-pool teardown and surfaced as the CME. JVM never reproduced it —
  `JdbcSqliteDriver` is single-connection and serializes implicitly.

- **Prior partial mitigation (`a59a1661`):** swapped `TestUploader.uploaded` to an
  atomicfu list and added `clearCheckout()` before `db.close()`. It narrowed the
  window but didn't close it: `clearCheckout` keys off `activeThreads`, which
  `send()`'s `finally` decrements *before* its final `nextScheduled()` read +
  `Completed` emit, so a real-dispatcher read could still be in flight at close.

- **Fix that stabilised it:** `runOutboxTest` builds the `DatabaseManager` with its
  read+write dispatchers bound to `runTest`'s virtual-time `testScheduler` and owns
  `db.close()` in a `finally`. `advanceUntilIdle()` now genuinely drains all DB
  work, so the outbox is quiescent before close on every target. 11 of 13 tests
  use it. Two opt out by design (documented inline + in the class KDoc):
  - `testFailureAndRetry` — must freeze after one attempt; virtual time would
    drain the whole 20-step backoff and drop the row.
  - `testTryEnqueueDoesNotBlockOnSaturatedEventBus` — deliberately blocks the test
    thread on `Dispatchers.Default` with a real `withTimeout`; virtual-time DB
    would deadlock it.

- **If it reappears:**
  - On a test that uses `runOutboxTest`: the virtual/real-time split has leaked
    back in — check whether the failing path added a hop to a real dispatcher
    (`ioDispatcher`, `Dispatchers.Default`, a network call) that `advanceUntilIdle()`
    can't drain, or whether `DatabaseManager` gained a code path that ignores the
    injected dispatcher.
  - On `testFailureAndRetry` or the saturated-bus test (the two real-dispatcher
    opt-outs): they still carry the original teardown-race exposure. If they flake,
    the right move is to make them virtual-time-safe (e.g. drive the retry with
    `advanceTimeBy`/`runCurrent` instead of `advanceUntilIdle`) rather than to
    re-introduce a global retry/sleep.
  - A recurrence on the *fixed* tests is a signal the cause is deeper than the
    test harness (e.g. a genuine concurrency issue in `NativeSqliteDriver` teardown
    that also affects the production `db.close()`-on-shutdown path). Capture the
    full iOS test report and treat it as a product investigation, not a test patch.
