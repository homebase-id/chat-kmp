# Plan 024: Characterize the WebSocket receive / notification-routing path with tests

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md (create the file with a one-row table if it does not yet exist).
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/ClientNotificationType.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/ClientNotificationPayload.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/ClientDriveNotification.kt homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/InboxItemReceivedNotification.kt`. If this prints ANY changed-file lines, the source has moved since this plan was written — STOP and re-read the cited files below, re-confirming the line numbers in "Current state" before proceeding.

## Status
Priority P3; Effort L; Risk LOW; Depends on: none (underpins plan 003 — see Maintenance notes); Category tests; Planned at: commit 45e2832e, 2026-06-14.

## Why this matters
`OdinWebSocketClient.dispatchNotification` (OdinWebSocketClient.kt:412) is a 20+-branch `when` on `notification.notificationType` that routes **every** inbound server event — inbox arrivals, file added/modified/deleted, statistics changes, reaction add/delete, introductions, connection requests, new follower, app notification, handshake, pong, auth error. It is the entire inbound half of the app and it has **zero** tests. By contrast the **send** path is exhaustively covered: `OutboxSyncTest.kt` has ~20 tests pinning every permanent-failure classifier, dependency-chain, and cancellation branch. `PeerWebSocketClient` (341 lines) is also untested.

The concrete cost: a regression that mis-routes a `notificationType` (e.g. `fileModified` falling into the `else {}` no-op at line 561, or `pong` no longer reaching the ping supervisor so the connection is declared dead) ships green. A change to the JSON envelope/payload shape (a renamed field, a non-nullable field the server stops sending) breaks deserialization silently — `handleTextFrame` swallows it in a `catch (e: Exception)` at line 380 and logs, so the only signal is "notifications stopped working in the field." This plan installs the instrumentation the CLAUDE.md debugging rule demands: a characterization harness that proves what each branch does today, so future edits to the routing table or the wire format fail a test instead of a user.

This plan is staged (deserialization first, then per-branch) and adds at most **one behavior-preserving seam** to the source.

## Current state

### `OdinWebSocketClient.kt` — the untested router
`homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt`

The receive pipeline, top to bottom:

- **handleTextFrame** (line 365): `readText()` → `decryptData(text)` → `OdinSystemSerializer.deserialize<ClientNotificationPayload>(decryptedJson)`; null payload is logged-and-dropped (line 373); else `handleNotification(notification)`. The whole body is wrapped in `catch (e: Exception)` (line 380) — **deserialization failures are swallowed**.
- **decryptData** (line 787): deserializes the outer `WebSocketClientNotificationPayload` envelope; if `!isEncrypted` returns `envelope.payload` verbatim (line 793–795) — this is the testable un-encrypted path; otherwise AES-CBC decrypts with `sharedSecret`.
- **handleNotification** (line 385): appends to `notificationBuffer` under a mutex, debounces `NOTIFICATION_BURST_MS = 200L` (line 126), then drains the batch and calls `dispatchNotification(n)` per item inside a per-item `try/catch` (line 405).
- **dispatchNotification** (line 412) — the router. The full `when (notification.notificationType)`:

```
414  deviceHandshakeSuccess -> onHandshakeSuccess()
418  pong                   -> pingSupervisor.notifyPongReceived()
422  authenticationError    -> handleAuthError(notification)
426  inboxItemReceived      -> handleProcessInbox(notification)   // sends "processInbox" over the socket
442  fileAdded              -> handleFileEvent(notification)
446  fileDeleted            -> handleFileEvent(notification)
450  fileModified           -> handleFileEvent(notification)
454  statisticsChanged      -> handleFileEvent(notification)
458  reactionContentAdded   -> handleReactionEvent(notification, false)   // intentional no-op (KDoc 596)
462  reactionContentDeleted -> handleReactionEvent(notification, true)    // intentional no-op
466  allReactionsByFileDeleted -> handleAllReactionsDeletedEvent(...)     // intentional no-op (KDoc 616)
470  introductionsReceived  -> deserialize<IntroductionReceivedNotification> → eventBus.emit(CircleNetworkEvent.IntroductionsReceived)
482  introductionAccepted   -> ... eventBus.emit(CircleNetworkEvent.IntroductionAccepted)
495  connectionRequestReceived -> ... eventBus.emit(CircleNetworkEvent.ConnectionRequestReceived)
507  connectionRequestAccepted -> ... eventBus.emit(CircleNetworkEvent.ConnectionRequestAccepted)
519  connectionFinalized    -> ... eventBus.emit(CircleNetworkEvent.ConnectionRequestFinalized)
532  newFollower            -> ... eventBus.emit(CircleNetworkEvent.NewFollower)
545  appNotificationAdded   -> {}     // empty
549  deviceConnected        -> {}     // comment-only
553  deviceDisconnected     -> {}     // comment-only
557  error                  -> Logger.e(...)
561  else                   -> {}     // empty
```

What each branch closes over (this is why direct testing is hard):
- **`onHandshakeSuccess`** (line 733) touches `pingSupervisor.start()`, `onConnected()`, and `eventBus.emit(ConnectionOnline)`.
- **`handleProcessInbox`** (line 581) deserializes `InboxItemReceivedNotification` then calls `notify("processInbox", …)` which **needs a live `session`** (line 828: `session == null` → log-and-return). With no socket this is a no-op-with-warning.
- **`handleFileEvent`** (line 666) deserializes `ClientDriveNotification`, and if `header != null` calls `getOrCreateWorker(driveId)` (needs `credentialsManager.getActiveCredentials()` + `databaseManager`) then `header.asHomebaseFile(SecureByteArray(sharedSecret))` (needs `sharedSecret`, set only inside `connectOnce`). If header is null or the worker is unavailable it falls back to `driveSyncManager.syncDrive(driveId)`.
- **`handleAuthError`** (line 714) parses a drive UUID out of the message string via regex and `eventBus.emit(DriveAuthorizationFailed(message))`.
- **CircleNetwork branches** (470–542) are the **easy** ones: deserialize a small payload and `eventBus.emit`. No DB, no socket, no credentials.
- **Reaction branches** (458–466) are **pure no-ops** by design (KDoc at 596 and 616 explains the parallel `statisticsChanged` fan-out does the real work).

### Constructor coupling (why we cannot just `new` the client in a test)
`OdinWebSocketClient(...)` (line 46) requires `credentialsManager: CredentialsManager`, `driveSyncManager: DriveSyncManager`, `eventBus: EventBus`, `databaseManager: DatabaseManager`, `drives: List<TargetDrive>`. `CredentialsManager` (CredentialsManager.kt:11) and `DriveSyncManager` (DriveSyncManager.kt:29) are **concrete classes, not interfaces** — `DriveSyncManager`'s constructor alone pulls in `DriveQueryProvider`, `mandatoryDrives`, etc. The `init` block also constructs a real Ktor `HttpClient` (line 74). So standing up a full client purely to drive the router is heavy and brittle. The seam in Step 3 sidesteps this.

### The payload types (the deserialization contract, Step 1 targets)
All in `homebase-api/src/commonMain/.../client/websockets/`, all `@Serializable`:
- `ClientNotificationPayload` (`notificationType: ClientNotificationType = unused`, `data: String = ""`) — defaults make it lenient.
- `ClientNotificationType` enum — 23 values incl. `unused`, `error`, `authenticationError`.
- `WebSocketClientNotificationPayload` (`isEncrypted: Boolean`, `payload: String`) — the outer envelope.
- `ClientDriveNotification` (`targetDrive: TargetDrive?`, `header: ServerFile?`, `previousServerFileHeader: ServerFile?`, `isDeleteNotification: Boolean`).
- `InboxItemReceivedNotification` (`targetDrive: TargetDrive` — **non-nullable**).
- `IntroductionReceivedNotification` (`introducerOdinId: OdinId`, `introduction: Introduction`).
- `IntroductionAcceptedNotification` (`introducerOdinId: OdinId`, `recipient: OdinId`).
- `ConnectionRequestReceivedNotification` (`sender: OdinId`, `recipient: OdinId`).
- `ConnectionRequestAcceptedNotification` (`sender: OdinId`, `recipient: OdinId`).
- `ConnectionRequestFinalizedNotification` (`identity: OdinId`).
- `NewFollowerNotification` (`sender: OdinId`).
- `Introduction` (`identities: List<String>`, `timestamp: Long`, `message: String`).
- `TargetDrive` (`alias: Uuid`, `type: Uuid`, both via `UuidSerializer`).

`OdinSystemSerializer` (OdinSystemSerializer.kt:22) is the JSON config: `ignoreUnknownKeys = true`, `coerceInputValues = true`, camelCase naming, `explicitNulls = false`, `decodeEnumsCaseInsensitive = true`. `OdinId` deserializes via its public `OdinId(String)` constructor which validates a domain (OdinId.kt:33,83) — so `OdinId` JSON values must be valid domains like `"frodo.dotyou.cloud"`.

### The eventBus assertion target
`EventBus.kt`: `events: SharedFlow<BackendEvent>` (`replay = 1`, `extraBufferCapacity = 10`). `BackendEvent.CircleNetworkEvent` subtypes (BackendEvent.kt:37–54): `ConnectionRequestReceived(sender)`, `ConnectionRequestAccepted(acceptedBy)`, `ConnectionRequestFinalized(identity)`, `NewFollower(identity)`, `IntroductionAccepted(introducerOdinId, recipient)`, `IntroductionsReceived(introducerOdinId, introduction)`. `DriveAuthorizationFailed(message)` is at line 242.

### Convention + exemplars to match
- **`OutboxSyncTest.kt`** (`homebase-api/src/commonTest/kotlin/id/homebase/api/sync/database/OutboxSyncTest.kt`) — THE model for testing the send/sync engine: fresh `EventBus()` per test, `async { eventBus.events.filterIsInstance<…>().first() }` collector kicked off with `testScheduler.runCurrent()` **before** the action, `runTest` + `advanceUntilIdle()`, **fakes not mocks** (`TestUploader : OutboxUploader`), real `DatabaseManager({ createInMemoryDatabase() })`. READ this file in full before writing.
- **`DriveWebSocketUpsertWorkerTest.kt`** (`homebase-api/src/jvmAndNativeTest/kotlin/id/homebase/api/sync/DriveWebSocketUpsertWorkerTest.kt`) — THE model for the WS upsert worker: `runBlocking` + real `Dispatchers.Default`, `CompletableDeferred<BatchReceived>` completed by a collector, bounded `withTimeoutOrNull(5.seconds)`, a `makeFile(...)` JSON-template fixture for a valid `HomebaseFile`. READ this file in full — its `makeFile` template is the fixture you reuse if you ever exercise a `header != null` file branch.
- `createInMemoryDatabase()` is an `expect`/`actual` test helper: declared in `commonTest/.../database/TestDatabaseHelper.kt`, with `jvm`/`native`/`web`/`androidHost` actuals. It is available in `commonTest` and below.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check (run FIRST) | `git diff --stat 45e2832e..HEAD -- homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt` | No output (no lines printed) |
| Compile commonMain (after a seam edit, if any) | `./gradlew :homebase-api:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Run the new tests (JVM) | `./gradlew :homebase-api:jvmTest` | `BUILD SUCCESSFUL`; the new test class appears green |
| Run a single new test class | `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.websockets.WsNotificationDeserializationTest"` | `BUILD SUCCESSFUL` |
| Run the router test class | `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.websockets.WsNotificationDispatchTest"` | `BUILD SUCCESSFUL` |
| Konsist/arch gate unaffected (no Compose touched) | `./gradlew :homebase-common:jvmTest` | `BUILD SUCCESSFUL` (only if you touched homebase-common — you should not) |

## Scope
**In scope (files you may create/modify):**
- `homebase-api/src/commonTest/kotlin/id/homebase/api/client/websockets/WsNotificationDeserializationTest.kt` — NEW. Step 1 (pure deserialization; no seam needed).
- `homebase-api/src/commonTest/kotlin/id/homebase/api/client/websockets/WsNotificationDispatchTest.kt` — NEW. Steps 3–5 (router behavior via the seam).
- `homebase-api/src/commonMain/kotlin/id/homebase/api/client/websockets/OdinWebSocketClient.kt` — MODIFY **only if** Step 2's seam is taken, and **only** to extract the `when` body into an injectable `NotificationDispatcher` with **no behavior change**. If the seam proves too invasive (see Step 2 STOP), do NOT touch this file and scope down to Step 1 + Step 5 (env-fake branch tests) only.

**Out of scope (do NOT touch):**
- `WebSocketPingSupervisor.kt` — already has a `nowMs` injection seam; not the subject here.
- `PeerWebSocketClient.kt` — the finding flags it as also untested, but it's a separate 341-line surface; characterizing it is a deferred follow-up (see Maintenance notes), not this plan.
- `DriveSyncManager.kt`, `CredentialsManager.kt`, `EventBus.kt`, `BackendEvent.kt`, any payload-type file — read-only; the test asserts their current behavior, it must not change them.
- Any `homebase-common`/Compose file — no UI involved; keep the Konsist arch test out of scope.
- The dispatch **logic itself** — this is a *characterization* test: it pins down what the code does **today**. If you discover a latent bug (e.g. a missing branch), do NOT fix it here; record it in Maintenance notes and write the test to assert the current (possibly wrong) behavior with a `// CHARACTERIZATION: current behavior, see Maintenance notes` comment.

## Steps

> Build-never-broken ordering: Step 1 adds a test that needs **no** source change and can land alone. Steps 2–4 add the seam and the router tests. Step 5 is an optional belt-and-suspenders. You may STOP after any step and still have shipped value.

### Step 1 — Deserialization characterization (no source change)
Create `WsNotificationDeserializationTest.kt` in `commonTest/.../client/websockets/`. Package `id.homebase.api.client.websockets`. Model the structure on `OutboxSyncTest` (plain `@Test`, `assertEquals`/`assertNotNull`/`assertFailsWith`), but these are **synchronous** — no coroutines needed, deserialization is pure.

Cover, using `OdinSystemSerializer.deserialize<T>(json)` with hand-written JSON literals:
1. **Envelope, unencrypted passthrough.** `WebSocketClientNotificationPayload` with `{"isEncrypted":false,"payload":"<inner>"}` round-trips; assert `isEncrypted == false` and `payload` is the inner string verbatim. (This is the branch `decryptData` returns at line 794 without touching `sharedSecret`.)
2. **`ClientNotificationPayload` for each routed `ClientNotificationType`.** For every enum value that has a real branch (`deviceHandshakeSuccess`, `pong`, `authenticationError`, `inboxItemReceived`, `fileAdded`, `fileDeleted`, `fileModified`, `statisticsChanged`, `reactionContentAdded`, `reactionContentDeleted`, `allReactionsByFileDeleted`, `introductionsReceived`, `introductionAccepted`, `connectionRequestReceived`, `connectionRequestAccepted`, `connectionFinalized`, `newFollower`, `appNotificationAdded`, `deviceConnected`, `deviceDisconnected`, `error`), assert `{"notificationType":"<name>","data":"…"}` deserializes to the matching enum constant. Drive it from `ClientNotificationType.entries` so a newly added enum value forces a decision (loop and assert each round-trips).
3. **Enum case-insensitivity + unknown coercion** (locks `decodeEnumsCaseInsensitive`/`coerceInputValues` from OdinSystemSerializer.kt): `{"notificationType":"FILEADDED","data":""}` decodes to `fileAdded`; an unknown `{"notificationType":"totallyNewType","data":""}` coerces to the default `unused` (does NOT throw — proves the `else {}` safety net is reachable, not a crash).
4. **Each CircleNetwork inner payload** round-trips with realistic JSON:
   - `IntroductionReceivedNotification`: `{"introducerOdinId":"frodo.dotyou.cloud","introduction":{"identities":["sam.dotyou.cloud"],"timestamp":1700000000000,"message":"hi"}}` → assert `introducerOdinId` domain and `introduction.identities`.
   - `IntroductionAcceptedNotification`, `ConnectionRequestReceivedNotification`, `ConnectionRequestAcceptedNotification`, `ConnectionRequestFinalizedNotification`, `NewFollowerNotification` — each with valid `OdinId` domains. Use a domain that passes `OdinId` validation (the repo uses `"frodo.dotyou.cloud"`, `"owner.test"`, `"sam.dotyou.cloud"` in existing tests).
5. **`ClientDriveNotification` shape:** a header-absent variant `{"targetDrive":{"alias":"<uuid>","type":"<uuid>"},"header":null,"isDeleteNotification":false}` deserializes with `header == null` and `targetDrive!!.alias` matching — this is the `syncDrive` fallback path's input at handleFileEvent:669–672. (Do NOT try to build a full `ServerFile` header here; that's the `DriveWebSocketUpsertWorkerTest.makeFile` territory and is out of scope for deserialization coverage.)
6. **`InboxItemReceivedNotification` requires `targetDrive`:** a valid `{"targetDrive":{"alias":"<uuid>","type":"<uuid>"}}` deserializes; assert the alias. (Document that `targetDrive` is non-nullable — if the server ever omits it, this throws and `handleProcessInbox`'s caller swallows it at dispatchNotification:405.)

Use `Uuid.random().toString()` to mint UUID strings; import `kotlin.uuid.Uuid`. Keep each case a separate `@Test` (or a small table-driven loop) so a failure names the exact payload.

**Verify:** `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.websockets.WsNotificationDeserializationTest"` → `BUILD SUCCESSFUL`.

### Step 2 — Decide on the seam (read first, then either extract or scope down)
Re-read `dispatchNotification` (OdinWebSocketClient.kt:412–564) and its private callees (`onHandshakeSuccess`, `handleProcessInbox`, `handleFileEvent`, `handleAuthError`, `handleReactionEvent`, `handleAllReactionsDeletedEvent`). Decide:

**Preferred (low-risk extraction):** introduce a private `suspend fun dispatch(notification: ClientNotificationPayload)` that contains the **exact** current `when` body, and have `dispatchNotification` delegate to it. This is a pure rename/no-op refactor and does NOT create the seam yet — it just isolates the routing table. THEN: extract a non-private, package-visible `internal` function or `internal` interface that takes the **collaborators the branches use** as parameters so a test can call it without a socket:

```kotlin
// Behavior-preserving seam: the routing table, parameterized over its effects.
// Each lambda mirrors the exact call the current `when` makes today.
internal suspend fun routeNotification(
    notification: ClientNotificationPayload,
    onHandshakeSuccess: suspend () -> Unit,
    onPong: () -> Unit,
    onAuthError: suspend (ClientNotificationPayload) -> Unit,
    onInboxItem: suspend (ClientNotificationPayload) -> Unit,
    onFileEvent: suspend (ClientNotificationPayload) -> Unit,
    onCircleEvent: suspend (BackendEvent) -> Unit,   // for the 6 emit branches
) { /* the current when, calling the lambdas; deserialize<…> stays inline */ }
```

The production `dispatchNotification` then calls `routeNotification(notification, ::onHandshakeSuccess, pingSupervisor::notifyPongReceived, ::handleAuthError, ::handleProcessInbox, ::handleFileEvent) { eventBus.emit(it) }`. **No behavior changes** — same branches, same order, same deserialize calls, same no-op reaction/device/appNotification/error/else arms. The reaction and device/appNotification/error branches stay as bodies inside `routeNotification` (they touch nothing).

Keep the function `internal` so the `commonTest` source set (same module) can call it. Confirm `homebase-api` has no `internal`-visibility test barrier (it does not — `commonTest` sees `internal`).

**STOP condition for this step:** if extracting `routeNotification` requires pulling the inline `OdinSystemSerializer.deserialize<…>` + `eventBus.emit(CircleNetworkEvent.…)` out in a way that changes which thread/scope the emit runs on, or forces a signature that the production caller can't satisfy without restructuring `connectOnce`/`sharedSecret` lifecycle — STOP. Do not force it. Fall back to **Step 1 + Step 5 only** (drop Steps 3–4) and note in the plan row that the seam was deferred.

**Verify (only if you edited the source):** `./gradlew :homebase-api:compileKotlinJvm` → `BUILD SUCCESSFUL`, AND `git diff homebase-api/.../OdinWebSocketClient.kt` shows only the extraction (no logic lines deleted or reordered).

### Step 3 — Router tests: the no-IO branches (CircleNetwork emits + pong + reaction no-ops)
Create `WsNotificationDispatchTest.kt` in `commonTest/.../client/websockets/`. These tests call `routeNotification(...)` directly with **fake lambdas** (record-into-a-list), so no socket/DB/credentials are needed. Use `runTest` (import `kotlinx.coroutines.test.runTest`) since `routeNotification` is `suspend`.

Pattern per test (modeled on OutboxSyncTest's "assert what was/ wasn't emitted"):
```kotlin
val emitted = mutableListOf<BackendEvent>()
val calls = mutableListOf<String>()  // which lambda fired
routeNotification(
    notification = ClientNotificationPayload(
        notificationType = ClientNotificationType.connectionRequestReceived,
        data = OdinSystemSerializer.serialize(ConnectionRequestReceivedNotification(
            sender = OdinId("frodo.dotyou.cloud"), recipient = OdinId("owner.test"))),
    ),
    onHandshakeSuccess = { calls += "handshake" },
    onPong = { calls += "pong" },
    onAuthError = { calls += "auth" },
    onInboxItem = { calls += "inbox" },
    onFileEvent = { calls += "file" },
    onCircleEvent = { emitted += it },
)
assertEquals(1, emitted.size)
val e = emitted.single()
assertTrue(e is BackendEvent.CircleNetworkEvent.ConnectionRequestReceived)
assertEquals(OdinId("frodo.dotyou.cloud"), (e as …).sender)
assertTrue(calls.isEmpty(), "circle event must not fire any of the IO lambdas")
```
Cover, one `@Test` each (asserting the emitted event type + key field, and that NO IO lambda fired):
- `connectionRequestReceived` → `ConnectionRequestReceived(sender)`.
- `connectionRequestAccepted` → `ConnectionRequestAccepted(acceptedBy = sender)` (note the field-name swap at dispatchNotification:514 — `acceptedBy = d.sender`; pin it).
- `connectionFinalized` → `ConnectionRequestFinalized(identity)`.
- `newFollower` → `NewFollower(identity = d.sender)` (again pin the `sender` → `identity` mapping at line 538).
- `introductionsReceived` → `IntroductionsReceived(introducerOdinId, introduction)`.
- `introductionAccepted` → `IntroductionAccepted(introducerOdinId, recipient)`.
- `pong` → exactly `calls == ["pong"]`, `emitted` empty.
- `reactionContentAdded` / `reactionContentDeleted` / `allReactionsByFileDeleted` → `emitted` empty AND no IO lambda fired (locks the **intentional no-op** contract; KDoc at 596/616).
- `appNotificationAdded`, `deviceConnected`, `deviceDisconnected`, `error`, and an `unused`/unknown coerced type → all produce no emit and no IO lambda (the `else {}` / empty-body arms). For `error` also assert it doesn't throw.

**Verify:** `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.websockets.WsNotificationDispatchTest"` → `BUILD SUCCESSFUL`.

### Step 4 — Router tests: the IO-delegating branches (delegation contract only)
Still in `WsNotificationDispatchTest.kt`, prove that the **router delegates** the side-effecting branches to the right lambda with the right payload — without standing up the real IO:
- `deviceHandshakeSuccess` → `calls == ["handshake"]`, nothing emitted.
- `authenticationError` → `onAuthError` fired with the same `ClientNotificationPayload` (capture and assert `data` equals the input). This pins that auth-error routing reaches `handleAuthError` (which is what emits `DriveAuthorizationFailed`).
- `inboxItemReceived` → `onInboxItem` fired with the input payload. (The real `handleProcessInbox` would `notify("processInbox", …)`; here we only assert routing, because `notify` needs a live `session`.)
- `fileAdded`, `fileDeleted`, `fileModified`, `statisticsChanged` → each fires `onFileEvent` with the input payload, exactly once, and emits nothing directly. This pins that all four file-ish types funnel into `handleFileEvent` (a real regression risk: a future edit dropping `statisticsChanged` into `else {}` would silently stop reaction-preview updates — this test catches it).

Each is a `@Test` with the same fake-lambda harness. Assert the **exact** lambda fired and that the others did not (so a mis-route, e.g. `fileModified` → `else`, fails).

**Verify:** `./gradlew :homebase-api:jvmTest --tests "id.homebase.api.client.websockets.WsNotificationDispatchTest"` → `BUILD SUCCESSFUL`.

### Step 5 — (Optional, only if Step 2 was scoped down) handleAuthError regex via a thin env fake
If — and only if — the seam was NOT extracted (Step 2 STOP fired), you still want one real-effect test. `handleAuthError` is the cheapest real branch: it touches only `eventBus.emit(DriveAuthorizationFailed(message))` and an internal `unauthorizedDriveAliases` set; it does NOT need a socket, DB, or `sharedSecret`. Construct a real `OdinWebSocketClient` is still heavy (HttpClient + DriveSyncManager). Instead, add a tiny `internal` test-only entry that forwards to `handleAuthError`, OR assert the **regex contract** directly by lifting the UUID-extraction regex (`\[([0-9a-fA-F-]{36})]`) into the test and asserting it extracts the alias from `"Unauthorized to read to drive [<uuid>]"`. Document this as a partial characterization. (This step exists so the plan still ships value if the seam is refused; skip it entirely if Steps 3–4 ran.)

**Verify:** `./gradlew :homebase-api:jvmTest` → `BUILD SUCCESSFUL`.

### Step 6 — Full module test run + index update
1. Run the whole module test suite to be sure nothing regressed: `./gradlew :homebase-api:jvmTest` → `BUILD SUCCESSFUL`. (If a seam was extracted, also `./gradlew :homebase-api:compileKotlinJvm` → `BUILD SUCCESSFUL`.)
2. Update the plan index. In `plans/README.md` mark this plan (024) done. If `plans/README.md` does not exist, create it with a minimal one-row table header (`| Plan | Title | Status |`) and the 024 row marked done.
   Verify: `grep -n '024' plans/README.md` → the row is present and marked done.

## Test plan
**New tests:**
- `homebase-api/src/commonTest/kotlin/id/homebase/api/client/websockets/WsNotificationDeserializationTest.kt`
  - Cases: envelope unencrypted-passthrough; every routed `ClientNotificationType` round-trips; enum case-insensitivity (`FILEADDED` → `fileAdded`); unknown-type coercion to `unused` (no throw); each CircleNetwork inner payload; `ClientDriveNotification` header-null shape; `InboxItemReceivedNotification` requires `targetDrive`.
  - **Regression it fixes:** a wire-format/field-rename change that today only surfaces as a swallowed `catch` at handleTextFrame:380 will now fail this test at build time.
  - Model after: `OutboxSyncTest` (structure, assertions) — but synchronous, no coroutines.
- `homebase-api/src/commonTest/kotlin/id/homebase/api/client/websockets/WsNotificationDispatchTest.kt`
  - Cases: the full routing table via `routeNotification` with fake lambdas — every `when` arm asserted (emit-or-delegate-or-noop), with the `acceptedBy=sender` / `identity=sender` field mappings pinned, and the four file-ish types all proven to reach `onFileEvent`.
  - **Regression it fixes:** a future edit that drops a `notificationType` into the `else {}` no-op (e.g. `statisticsChanged` → silent), or swaps an emit field, fails here.
  - Model after: `OutboxSyncTest`'s "collect-into-a-list, assert what was/wasn't emitted" pattern, adapted to synchronous fake lambdas under `runTest`.

**Verify command (both):** `./gradlew :homebase-api:jvmTest`.

This test code also exercises the **buffer-drain seam** indirectly (the `routeNotification` table is what `handleNotification`'s drain loop calls per item) — so when plan 003 (ws-notification-flush-no-drop) changes the buffer/flush behavior, the routing contract these tests pin must still hold, catching a flush change that accidentally re-orders or drops a notification type.

## Done criteria
- [ ] `git diff --stat 45e2832e..HEAD` shows changes ONLY under `plans/` and (at most) the single in-scope source file `OdinWebSocketClient.kt`; no other `homebase-api` source modified.
- [ ] `WsNotificationDeserializationTest.kt` exists and `./gradlew :homebase-api:jvmTest --tests "*WsNotificationDeserializationTest"` is green.
- [ ] If the seam was extracted: `WsNotificationDispatchTest.kt` exists, `./gradlew :homebase-api:jvmTest --tests "*WsNotificationDispatchTest"` is green, AND `./gradlew :homebase-api:compileKotlinJvm` is green.
- [ ] If the seam was refused (Step 2 STOP): Step 5 ran instead, and the plan row notes "seam deferred".
- [ ] `./gradlew :homebase-api:jvmTest` (whole module) is `BUILD SUCCESSFUL`.
- [ ] No production behavior changed: `git diff` of `OdinWebSocketClient.kt` (if touched) contains no removed/reordered branch logic — only the extraction.
- [ ] `plans/README.md` row for 024 marked done.

## STOP conditions
- **Drift:** the Drift-check `git diff --stat` prints any line → STOP, re-read the cited files, re-confirm line numbers, then proceed.
- **Seam too invasive (Step 2):** extracting `routeNotification` would change emit thread/scope, the `sharedSecret`/`session` lifecycle, or require restructuring `connectOnce` → STOP touching the source; fall back to Step 1 + Step 5; record "seam deferred" in the plan row.
- **A test reveals a real routing bug** (a `notificationType` genuinely mis-routed today, or a payload that fails to deserialize against real server JSON you have on hand) → STOP, do NOT fix the production code in this plan. Write the characterization test asserting current behavior, mark it with a `// CHARACTERIZATION` comment, and record the suspected bug under Maintenance notes for a follow-up plan.
- **`internal` not visible from `commonTest`** (unexpected; it should be) → STOP; do not widen visibility to `public` just for the test — instead keep the seam `internal` and report the build error.
- **`./gradlew :homebase-api:jvmTest` fails on a pre-existing unrelated test** (not one you added) → STOP and report; do not "fix" unrelated tests under this plan.

## Maintenance notes
- **What a reviewer should scrutinize:** (1) that the Step 2 extraction is genuinely behavior-preserving — diff the old `when` against the new `routeNotification` body branch-for-branch; the reaction/device/appNotification/error/`else` arms must remain no-ops in the same order. (2) that the tests assert the *current* field mappings, not the "obvious" ones — specifically `connectionRequestAccepted` maps `d.sender` → `acceptedBy` (line 514) and `newFollower` maps `d.sender` → `identity` (line 538); a reviewer might "correct" these and break the pin.
- **Why no live-socket test:** `handleProcessInbox`/`notify` need a connected `session`; `handleFileEvent`'s header path needs `sharedSecret` (set only inside `connectOnce`) and a real `DatabaseManager`. Standing up a fake Ktor WebSocket server is disproportionate for a P3 characterization. The seam tests the routing decision; the *effect* of `handleFileEvent`'s worker path is already covered by `DriveWebSocketUpsertWorkerTest`. If a future plan wants end-to-end coverage, it should fake the Ktor engine (MockEngine does not do WebSockets — would need a custom transport), which is a separate L-effort effort.
- **Deferred follow-ups:**
  - `PeerWebSocketClient` (341 lines, untested) — flagged by the same finding; characterize it in a sibling plan once this seam pattern is proven.
  - The non-nullable `InboxItemReceivedNotification.targetDrive`: if the server can omit it, `handleProcessInbox` throws and the per-item `catch` at dispatchNotification:405 swallows it silently. Consider a follow-up to make the field nullable + log, but that's a behavior change out of this plan's scope.
  - Any routing bug surfaced during testing goes here, then into its own fix plan — never fixed inside this characterization plan.
- **Interaction with plan 003:** plan 003 reworks the `notificationBuffer` flush (handleNotification:385). The router contract these tests pin is the invariant 003 must preserve — keep both plans' line references in sync if 003 lands first.
