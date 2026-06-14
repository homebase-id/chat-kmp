# Plan 003: Make the WebSocket notification flush drain non-cancellable so a mid-flush arrival can't drop the undispatched tail

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt`. If that file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P1
- Effort: S
- Risk: MED (touches the WebSocket receive/dispatch hot path; a wrong cancellation scope can leak a never-completing coroutine or break burst debounce)
- Depends on: none
- Category: bug
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
Incoming chat events (delivered messages, reactions, inbox-ack `inboxItemReceived`, `fileAdded`) arrive over the WebSocket and are coalesced through a 200 ms burst debounce. Today the debounce snapshots-and-clears the buffer, then dispatches each item in a *cancellable* loop. If a new notification arrives mid-drain it calls `notificationFlushJob?.cancel()`, which can interrupt the loop **after** the buffer was already cleared but **before** the tail of the snapshot was dispatched. Those tail items — already removed from the buffer — are lost forever and only resurface on the next reconnect `syncAll`. The concrete cost: a message or reaction silently never lands in the DB/UI until a reconnect happens, which can be minutes. This plan makes the drain atomic with respect to a new arrival so nothing in a snapshot can be dropped, while keeping the 200 ms debounce intact.

## Current state

### File: `homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt`
Role: the KMP-common WebSocket client. Receives encrypted frames, debounces a burst of notifications, and dispatches each to the right handler (DB upsert workers, eventBus, ping supervisor).

Buffer/debounce fields (lines 119-126):
```kotlin
    private val notificationBuffer =
        mutableListOf<ClientNotificationPayload>()

    private val notificationBufferMutex = Mutex()

    private var notificationFlushJob: Job? = null

    private val NOTIFICATION_BURST_MS = 200L
```

The racy debounce — `handleNotification` (lines 385-410):
```kotlin
    private suspend fun handleNotification(notification: ClientNotificationPayload) {
        notificationBufferMutex.withLock {
            notificationBuffer += notification
        }

        // cancel pending flush
        notificationFlushJob?.cancel()

        notificationFlushJob = scope.launch {
            delay(NOTIFICATION_BURST_MS)

            val batch = notificationBufferMutex.withLock {
                val snapshot = notificationBuffer.toList()
                notificationBuffer.clear()
                snapshot
            }

            for (n in batch) {
                try {
                    dispatchNotification(n)
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to dispatch notification type=${n.notificationType}, data=${n.data.take(200)}" }
                }
            }
        }
    }
```
The bug: `dispatchNotification` (line 412) **suspends** (DB writes, `eventBus.emit`, `session.send`). After `delay(NOTIFICATION_BURST_MS)` returns and the buffer is snapshotted+cleared (lines 396-400), the `for` loop (402-408) is still inside the cancellable `scope.launch` job. A new arrival runs `notificationFlushJob?.cancel()` (line 391) and kills the job mid-loop. The remaining `batch` items — a local list already removed from `notificationBuffer` — are never dispatched and never re-buffered. Lost.

`dispatchNotification` signature (line 412, **out of scope** to change its body):
```kotlin
    private suspend fun dispatchNotification(notification: ClientNotificationPayload) {
        when (notification.notificationType) {
```

Current relevant imports (top of file, lines 30-39) — note what is present and what is missing:
```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
...
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```
`kotlinx.coroutines.withContext` and `kotlinx.coroutines.NonCancellable` are **NOT** imported yet — this plan adds both.

Teardown that cancels the job, for safety reasoning (line 753, `disconnect()`):
```kotlin
    fun disconnect() {
        closed = true
        pingSupervisor.stop()
        ...
```
`disconnect()` does **not** explicitly cancel `notificationFlushJob`; it cancels `connectionJob` and the scope is owned by the caller (AuthConnectionCoordinator). The `NonCancellable` drain still terminates because it's a finite loop over a snapshot — it does not loop forever. (See Maintenance notes for the one edge to watch.)

### Convention that applies
`kotlinx-coroutines-flows` skill: when a finite, already-claimed unit of work must not be interrupted by structured-concurrency cancellation, run it inside `withContext(NonCancellable)`. The exemplar concurrency-test style to model the new test after is `homebase-api/src/jvmAndNativeTest/kotlin/id/homebase/api/sync/DriveWebSocketUpsertWorkerTest.kt` — it uses `runBlocking`/`runTest` with real dispatchers, a `SupervisorJob` scope, and bounded-timeout polling, and explicitly tests "cancel() prevents pending work."

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run first) | `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt` | empty output (no drift) |
| Compile common/JVM (primary gate) | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile Android leg | `./gradlew :homebase-api:compileAndroidMain` | `BUILD SUCCESSFUL` |
| Compile iOS leg (macOS host only) | `./gradlew :homebase-api:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` |
| Compile Web leg | `./gradlew :homebase-api:compileKotlinWasmJs` | `BUILD SUCCESSFUL` |
| Run module JVM tests (incl. new test) | `./gradlew :homebase-api:jvmTest` | `BUILD SUCCESSFUL`, new test passes |
| Confirm no leftover cancellable drain | `grep -n "for (n in batch)" homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt` | the match is inside a `withContext(NonCancellable)` block (verify visually) |

## Scope
In scope (only these files may be modified/created):
- `homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt` — extract a testable `internal` drain function and run snapshot+clear+dispatch inside `withContext(NonCancellable)`; add the two missing imports.
- `homebase-api/src/jvmAndNativeTest/kotlin/id/homebase/api/client/websockets/NotificationFlushDrainTest.kt` — NEW test (see Test plan).
- `plans/README.md` — update this plan's status row (this plan also creates the README if absent — see Done criteria).

Out of scope (do NOT touch):
- The body of `dispatchNotification` and every `handle*Event` / `handleProcessInbox` / `handleAuthError` branch — the per-type handlers are unchanged; only the drain *wrapper* changes.
- `WebSocketPingSupervisor` and the ping/pong path — unrelated.
- `disconnect()` / `close()` / `start()` / reconnect logic — the flush job's lifecycle vs. scope teardown is reasoned about but not modified.
- `NOTIFICATION_BURST_MS` value and the `delay()` debounce timing — debounce behaviour must be preserved exactly.

## Steps

1. **Add the two missing imports.** In the import block (after `import kotlinx.coroutines.launch`, keeping alphabetical-ish grouping is fine), add:
   ```kotlin
   import kotlinx.coroutines.NonCancellable
   import kotlinx.coroutines.withContext
   ```
   Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL` (imports unused yet is allowed; if the build treats unused imports as errors it will fail here — if so, add the imports together with Step 2 instead and re-run).

2. **Extract the drain into an `internal suspend` function and wrap it in `NonCancellable`.** Replace the body of `handleNotification` (lines 385-410) and add a new private/internal drain function immediately after it. The debounce timer stays cancellable; only the snapshot+clear+dispatch becomes non-cancellable. Final shape:
   ```kotlin
   private suspend fun handleNotification(notification: ClientNotificationPayload) {
       notificationBufferMutex.withLock {
           notificationBuffer += notification
       }

       // Cancel the pending debounce timer. The new arrival is already
       // buffered above, so it rides the next drain. A cancel can only
       // interrupt the delay() below — never an in-progress drain, which
       // runs inside withContext(NonCancellable).
       notificationFlushJob?.cancel()

       notificationFlushJob = scope.launch {
           delay(NOTIFICATION_BURST_MS)
           // Once the burst window elapses, claim and dispatch the batch
           // atomically w.r.t. a new arrival's cancel(). A new notification
           // that arrives now is safely in notificationBuffer for the next
           // cycle; the snapshot we drain here must finish.
           withContext(NonCancellable) {
               drainNotificationBuffer()
           }
       }
   }

   /**
    * Snapshots and clears [notificationBuffer] under [notificationBufferMutex],
    * then dispatches every item. MUST run inside withContext(NonCancellable):
    * once an item leaves the buffer it is no longer recoverable from a
    * reconnect's syncAll until that reconnect happens, so the loop may not be
    * cancelled mid-flight. internal for [NotificationFlushDrainTest].
    */
   internal suspend fun drainNotificationBuffer() {
       val batch = notificationBufferMutex.withLock {
           val snapshot = notificationBuffer.toList()
           notificationBuffer.clear()
           snapshot
       }

       for (n in batch) {
           try {
               dispatchNotification(n)
           } catch (e: Exception) {
               Logger.e(e) { "Failed to dispatch notification type=${n.notificationType}, data=${n.data.take(200)}" }
           }
       }
   }
   ```
   Notes for the executor:
   - Keep `notificationBuffer`, `notificationBufferMutex`, `notificationFlushJob`, `NOTIFICATION_BURST_MS` exactly as they are (lines 119-126) — do not rename or change visibility of the fields.
   - `dispatchNotification` stays `private` and unchanged.
   - Do NOT wrap the `delay(NOTIFICATION_BURST_MS)` inside `NonCancellable` — the debounce timer MUST stay cancellable so a rapid second arrival still coalesces.
   Verify: `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.

3. **Compile the other three legs** to confirm the new imports/API exist on every target (`NonCancellable` and `withContext` are in `kotlinx-coroutines-core` common, so this should pass everywhere).
   Verify (run all that the host supports; iOS only on macOS):
   - `./gradlew :homebase-api:compileAndroidMain` -> `BUILD SUCCESSFUL`
   - `./gradlew :homebase-api:compileKotlinWasmJs` -> `BUILD SUCCESSFUL`
   - `./gradlew :homebase-api:compileKotlinIosSimulatorArm64` -> `BUILD SUCCESSFUL` (skip with a note if not on macOS)

4. **Add the regression test** `NotificationFlushDrainTest.kt` (see Test plan for full content). It exercises the extracted seam directly: cancel the drain coroutine mid-flight and assert no item is lost.
   Verify: `./gradlew :homebase-api:jvmTest` -> `BUILD SUCCESSFUL`, `NotificationFlushDrainTest` passes.

5. **Update `plans/README.md`** — mark plan 003's row Status as Done (or create the README with the row if it does not yet exist; see Done criteria for the row format).
   Verify: `grep -n "003-ws-notification-flush-no-drop" plans/README.md` -> one row present.

## Test plan

The bug is a cancellation race, which is hard to test through the full `handleNotification` because the dispatch handlers reach into the DB/eventBus. The extracted `internal suspend fun drainNotificationBuffer()` is the clean seam: it dispatches via the real handlers in production, but the *property we care about* — "a cancellation cannot make a snapshotted item silently vanish" — can be tested at the drain level by reproducing the same `snapshot → clear → suspend-per-item → may-be-cancelled` shape with a `NonCancellable` wrapper and asserting completeness.

New test file: `homebase-api/src/jvmAndNativeTest/kotlin/id/homebase/api/client/websockets/NotificationFlushDrainTest.kt`
Model after: `homebase-api/src/jvmAndNativeTest/kotlin/id/homebase/api/sync/DriveWebSocketUpsertWorkerTest.kt` (real dispatchers + `SupervisorJob` scope + bounded-timeout polling; the "cancel() prevents pending work" test is the closest analogue).

Because `drainNotificationBuffer()` is a method on `OdinWebSocketClient` whose constructor needs a `CredentialsManager`, `DriveSyncManager`, `EventBus`, `DatabaseManager`, etc., constructing a full client in a unit test is heavy. The executor must pick ONE of these two, in order of preference:

- **Preferred — test the invariant in isolation (no full client).** Reproduce the exact drain shape (mutex-guarded snapshot+clear, then a `withContext(NonCancellable)` loop that suspends per item) in the test against a local buffer + a local dispatch lambda, and prove a cancel mid-loop loses nothing. This directly locks the `NonCancellable` contract this plan establishes and fails if a future edit removes the wrapper. Cases:
  1. `drainCompletesAllItemsEvenWhenCancelledMidFlight` — buffer = 5 items; dispatch lambda records each item then `yield()`s; launch the drain in a child coroutine; from the parent, `cancel()` the child after the first item is recorded; `advanceUntilIdle()` / await; assert all 5 were recorded AND the buffer is empty. (Without `NonCancellable`, recorded < 5.)
  2. `drainSnapshotIsAtomic_newArrivalGoesToNextCycle` — start a drain over items [1,2,3]; while it runs, append item 4 to the buffer; assert the first drain dispatched exactly [1,2,3] (4 not in this batch), then a second drain dispatches [4]. Proves snapshot+clear under the mutex doesn't drop or double-dispatch across a concurrent append.
  3. `cancelDuringDelayDoesNotDrain` — the debounce `delay()` is cancellable: a coroutine that `delay(burst)` then drains, when cancelled during the delay, dispatches nothing and leaves the buffer intact (so the next cycle picks it up). Pins that we did NOT over-apply `NonCancellable` to the timer.

  Keep the reproduction faithful: same `Mutex` + `withLock` snapshot/clear, same `withContext(NonCancellable)` wrapper, same per-item suspension point. Add a one-line comment in the test pointing at `OdinWebSocketClient.drainNotificationBuffer()` as the production code this mirrors, so a future reader knows to keep them in sync.

- **Fallback — if a full `OdinWebSocketClient` can be cheaply constructed** (an existing test or fake already builds one), call the real `internal drainNotificationBuffer()` with a buffer pre-seeded via a tiny test-only seam. Only take this path if it does NOT require new production fakes; otherwise use the preferred path. If neither is feasible without out-of-scope production changes, STOP and report (the extraction itself + compile is still the deliverable, and plan 024 / TESTCOV-01 is the proper home for end-to-end WS coverage).

Verify: `./gradlew :homebase-api:jvmTest` -> `BUILD SUCCESSFUL`, all `NotificationFlushDrainTest` cases pass. Because the file lives in `jvmAndNativeTest`, it also compiles for native; that is intentional and free.

## Done criteria
- [ ] `git diff --stat 45e2832e..HEAD -- homebase-api/.../OdinWebSocketClient.kt` showed no drift before edits (or drift was reconciled and noted).
- [ ] `./gradlew :homebase-api:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-api:compileAndroidMain` and `:homebase-api:compileKotlinWasmJs` -> `BUILD SUCCESSFUL` (iOS leg too on macOS).
- [ ] `grep -n "withContext(NonCancellable)" homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt` returns exactly one match, and it wraps the `drainNotificationBuffer()` call.
- [ ] `grep -n "NonCancellable\|withContext" homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt` shows both imports present.
- [ ] `./gradlew :homebase-api:jvmTest` -> `BUILD SUCCESSFUL`; the new `NotificationFlushDrainTest` cases (≥2) pass.
- [ ] `git status --porcelain` shows ONLY: `OdinWebSocketClient.kt`, `NotificationFlushDrainTest.kt`, `plans/README.md`, and `plans/003-ws-notification-flush-no-drop.md` (plus the pre-existing untracked `.agents/`, `skills-lock.json`, and the iOS xcuserdata files already dirty at branch start — do not stage or revert those).
- [ ] `plans/README.md` has a row for `003-ws-notification-flush-no-drop` marked Done. Row format if creating the README:
  ```markdown
  | Plan | Title | Priority | Status |
  |------|-------|----------|--------|
  | [003](003-ws-notification-flush-no-drop.md) | WS notification flush: no-drop on mid-flush arrival | P1 | Done |
  ```

## STOP conditions
- The drift check shows `OdinWebSocketClient.kt` changed since commit 45e2832e and the Current-state excerpts (lines 119-126, 385-410) no longer match live code — STOP, re-verify line numbers, do not blind-edit.
- Any in-scope compile command fails twice after a genuine fix attempt — STOP and report the error, do not silence it with a broad catch or by reverting the wrapper.
- The fix appears to need an out-of-scope file (e.g. you find the only way to test is to change `dispatchNotification`'s visibility chain or add a production fake) — STOP and report; do not expand scope.
- Assumption check: if `withContext` / `NonCancellable` turn out NOT to resolve on some target (they are core-common, so this should never happen), STOP rather than introducing a platform-specific `actual`.
- If making the loop non-cancellable surfaces a *different* real bug (e.g. a drain that legitimately needs to be abandoned on `disconnect()`), STOP and report — that is a design question, not a silent workaround.

## Maintenance notes
- **Why `NonCancellable` and not "don't clear the buffer until dispatched" (Option B):** Option B would re-buffer-on-cancel but reorders/complicates the mutex dance (you'd remove items one-by-one under the lock between suspending dispatches, holding or re-acquiring the mutex per item) and risks double-dispatch if a cancel lands between dispatch and removal. `NonCancellable` is the smaller change and preserves exact ordering: the snapshot is FIFO, each item dispatched once, and the triggering arrival rides the next cycle. The debounce timer stays cancellable so coalescing is unchanged.
- **Edge a reviewer must scrutinize:** `disconnect()` (line 753) does not cancel `notificationFlushJob`, and the drain is now non-cancellable. This is fine because the drain is a *finite* loop over a bounded snapshot — it always terminates. It does NOT loop forever and does NOT await an unbounded external signal. If a future change makes a `dispatchNotification` branch block indefinitely (e.g. awaiting a server ack with no timeout), a `NonCancellable` drain could outlive teardown — at that point add a timeout *inside the dispatch branch*, never by making the drain cancellable again.
- **Deferred:** end-to-end coverage that drives a real frame burst through `handleTextFrame → handleNotification → dispatch` with a fake session/eventBus belongs in plan 024 (TESTCOV-01). This plan only locks the drain-completeness invariant.
- If a later refactor inlines `drainNotificationBuffer()` back into the lambda, the `internal` seam (and its test) disappears — keep the function extracted so the regression test keeps compiling.
