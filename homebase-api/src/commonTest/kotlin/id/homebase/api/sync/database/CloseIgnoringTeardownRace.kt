package id.homebase.api.sync.database

/**
 * Closes a per-test in-memory [DatabaseManager], swallowing the teardown-time
 * race that the real-dispatcher outbox tests provoke.
 *
 * Those tests (`OutboxSyncTest.testFailureAndRetry`,
 * `OutboxSetNextRunTimeTest.failedAttemptStoresAFutureMillisecondsDeadline`)
 * keep the production real-thread DB dispatchers so the retry backoff parks
 * instead of being advanced through. `runTest` cancels `backgroundScope` (where
 * the outbox uploader/retry runs) when the body returns, but a DB op already
 * dispatched to `Dispatchers.Default`/`Dispatchers.IO` can outlive
 * `advanceUntilIdle`/`clearCheckout` and land *during* `close()`:
 *
 * - iOS-sim `NativeSqliteDriver`: connection-pool teardown throws
 *   `ConcurrentModificationException at null:-1` (occasionally a segfault).
 * - JVM `JdbcSqliteDriver`: `SQLException("database has been closed")`.
 *
 * Per the already-merged precedent (PR #390, `ChatMessageActionServiceTestFixture`),
 * swallowing is safe: the DB is private to the test, the leak is harmless, and
 * every assertion ran inside the `runTest` body before this is reached.
 */
fun DatabaseManager.closeIgnoringTeardownRace() {
    try {
        close()
    } catch (_: Throwable) {
        // Teardown race on a per-test in-memory DB — nothing to recover, and
        // propagating would mask the real assertions. (A rare native segfault
        // can't be caught here; the catchable CME is the dominant case.)
    }
}
