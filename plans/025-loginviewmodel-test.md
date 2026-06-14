# Plan 025: Add a commonTest suite covering LoginViewModel's auth/callback state logic

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-auth/src homebase-auth/build.gradle.kts gradle/libs.versions.toml`. On mismatch with the Current state excerpts below, STOP and re-read the cited files before continuing.

## Status
Priority **P3**; Effort **M**; Risk **LOW**; Depends on: none; Category **tests**; Planned at: commit 45e2832e, 2026-06-14.

> ## DRIFT NOTE — read before doing anything (codeMatchedFinding = false)
>
> The finding's *line numbers and method names are accurate* (verified against the real file below). But the finding's **prescribed fix is not buildable as written** and you MUST adapt it. Three concrete reasons, each verified by reading source:
>
> 1. **There is no auth-client interface to fake.** The finding says "write a FakeAuthClient implementing the interface." The VM's collaborators are all **concrete final classes**, not interfaces:
>    - `YouAuthFlowManager` (homebase-api/src/commonMain/.../youauth/YouAuthFlowManager.kt:72) is a `class` with five concrete constructor deps (`DriveSyncManager`, `CredentialsManager`, `HttpClient`, `DriveFileProviderCached`, `PublicProfileProviderCached`) and an `init {}` block (line 101-112) that launches a coroutine calling `restoreSession()`, which touches `CredentialStorage` and the `expect object SecureStorage` global singleton (no actual on a bare JVM unit-test classpath path that's been set up).
>    - `AuthConnectionCoordinator` (homebase-common/src/commonMain/.../core/auth/AuthConnectionCoordinator.kt:41) has **ten** concrete constructor deps and spins up a `CoroutineScope` + WebSocket machinery.
>    - `DriveSyncManager` (homebase-api/src/commonMain/.../sync/DriveSyncManager.kt:29) needs a `DriveQueryProvider`, `CredentialsManager`, `EventBus`, `DatabaseManager`, etc.
>    - `NotificationService`, `UsernameStorage` likewise depend on `SecureStorage`/`SharedPreferences` globals.
>
>    Kotlin classes are **final by default** and there is no Mockito/MockK in this repo (fakes only, per repo policy), so you cannot subclass-fake these concrete types, and you cannot construct them without dragging in the whole app graph + platform storage. **Constructing a real `LoginViewModel` in a unit test is therefore not feasible** without a source change (out of scope per the spec).
>
> 2. **The cited reference test does NOT drive a ViewModel.** `homebase-core/src/jvmTest/.../vault/note/VaultNoteEditorViewModelTest.kt` (read in full — 73 lines) never instantiates `VaultNoteEditorViewModel`; every test constructs `VaultNoteEditorUiState(...)` and asserts on the **UiState data class / derived properties** in isolation. So "model after VaultNoteEditorViewModelTest" actually means *test the isolatable state + pure decision logic*, which is exactly what this plan does.
>
> 3. **`LoginUiEvent` is stored IN the UiState, not in a separate SharedFlow.** The finding (and the repo's own one-time-event convention) describe a "one-time `LoginUiEvent` SharedFlow"; the real VM carries the event in `LoginUiState.uiEvent: LoginUiEvent?` (LoginUiState.kt:16) and clears it via `eventConsumed()` (LoginViewModel.kt:65-69). There is no SharedFlow to assert on. (This is a pre-existing deviation from CLAUDE.md's "separate SharedFlow" rule — see Plan 014 — but it is **out of scope** here; do not change it.)
>
> **What this plan therefore delivers:** a `commonTest` suite that exercises the *pure, isolatable* logic the VM relies on, with **zero source changes** to `LoginViewModel.kt`:
> - The `OdinId(String)` valid-vs-invalid parse decision that gates `startLogin` (LoginViewModel.kt:120-128).
> - The HTTP ping → boolean decision identical to `isValidHomebaseId` (LoginViewModel.kt:97-115), driven by a Ktor **`MockEngine`** (200 → true; non-200 → false; thrown exception → false). This is the OAuth/data-upgrade-adjacent "is this a real identity" gate.
> - The `observeAuthState` terminal-vs-loading decision (`syncStopped` / `syncCannotStart`, LoginViewModel.kt:271-278) expressed against the real `YouAuthState` / `SyncState` types and `computeSyncState` (DriveStatus.kt:42-51).
> - The `LoginUiState` / `LoginError` / `LoginUiEvent` transition shapes as data (mirrors the reference test's altitude).
>
> If, when you reach the steps, you discover the collaborators have since been refactored to **interfaces** (so a real `LoginViewModel` CAN be constructed with fakes), STOP and report — the higher-value test would then be a true VM-under-test suite and the plan should be revised rather than silently followed.

## Why this matters
Auth is the app's critical path and the OAuth / data-upgrade return flow is known-fragile (MEMORY: "iOS Crashlytics async race", "Pending-share leak gate", "credentials always set before Authenticated"). Today the only test in `homebase-auth` is `LoginUiTest.kt`, a render smoke test that asserts node tags exist and **never exercises any decision logic** — the homebase-id validity gate, the ping success/failure branch, and the "hold the loading screen until sync stops" rule are all untested. A regression in any of those ships silently. This plan adds fast JVM-runnable tests that pin the exact branch decisions (valid/invalid id, ping 200/non-200/throw, authenticated-but-still-syncing vs authenticated-and-done) so a future refactor of `LoginViewModel` that breaks them fails CI. It does **not** attempt the (currently impossible) full VM-under-test; it locks down the parts that are reachable without a source change, which is most of the risk surface.

## Current state
- **`homebase-auth/src/commonMain/kotlin/id/homebase/auth/login/LoginViewModel.kt`** (318 lines) — the unit under examination. Real, current excerpts:
  - Invalid-id gate (lines 120-128):
    ```kotlin
    val homebaseId = try {
        OdinId(homebaseIdValue)
    } catch (_: Exception) {
        Logger.w(tag = "LoginViewModel", messageString = "Invalid Homebase ID: $homebaseIdValue")
        _uiState.update {
            it.copy(error = LoginError.Res(MR.string.login_error_invalid_id))
        }
        return
    }
    ```
  - Ping decision (lines 97-115), `isValidHomebaseId(identity: OdinId): Boolean` — GETs `https://$identity/api/v2/health/ping`, returns `true` only on HTTP 200, `false` on any other status, and `false` (via `catch (t: Throwable)`) on any thrown error. **Note the call uses a `timeout { requestTimeoutMillis = …; connectTimeoutMillis = … }` block (lines 101-104)** — that extension requires the `HttpTimeout` plugin to be installed on the client (see Step 3 caveat).
  - The hold-the-screen decision inside `observeAuthState` (lines 271-278):
    ```kotlin
    val syncStopped =
        syncState is SyncState.Completed || syncState is SyncState.Failed
    val syncCannotStart = syncState is SyncState.Idle && !isConnecting
    if (syncStopped || syncCannotStart) {
        handleAuthenticatedUser()
    } else {
        _uiState.update { it.copy(isLoading = true) }
    }
    ```
- **`homebase-auth/src/commonMain/kotlin/id/homebase/auth/login/LoginUiState.kt`** — `LoginUiState` is a flat `@Immutable data class` with `uiEvent: LoginUiEvent? = null` (line 16); `LoginError` is a sealed interface with `Res(resource, arg)` and `Message(text)` (lines 25-28).
- **`homebase-auth/src/commonMain/kotlin/id/homebase/auth/login/LoginUiEvent.kt`** — sealed interface: `NavigateToHome`, `ShowError`, `OpenUrl`, `OpenAuthUrl`.
- **`homebase-api/src/commonMain/kotlin/id/homebase/api/youauth/YouAuthFlowManager.kt`** — defines `sealed interface YouAuthState` (lines 36-57): `Initializing`, `Unauthenticated`, `Authenticating`, `Authenticated(identity, clientAuthToken, sharedSecret)`, `Error(message)`. Constructing `YouAuthState.Authenticated` needs an `OdinId` + two non-empty strings.
- **`homebase-api/src/commonMain/kotlin/id/homebase/api/sync/DriveStatus.kt`** — `sealed interface SyncState` (lines 29-34): `Idle`, `Syncing`, `Completed`, `Failed`; `sealed interface DriveState` (lines 22-27); `fun computeSyncState(statuses: Map<Uuid, DriveStatus>): SyncState` (lines 42-51). All in package `id.homebase.api.sync`.
- **`homebase-api/src/commonMain/kotlin/id/homebase/api/common/OdinId.kt`** — `OdinId(String)` (line 83) validates+normalises via `AsciiDomainName` and computes the hash **synchronously** (`cachedHash` → `reduceSha256HashSync`, lines 112-117). It is safe to call in a plain test (no `runBlocking`/dispatcher needed). An invalid domain throws from the constructor (caught by the VM's `catch (_: Exception)`).
- **`homebase-auth/build.gradle.kts`** — KMP library. `sourceSets`: `commonMain` (api `:homebase-api`, implementation `:homebase-common`, ktor client core, etc.). `commonTest.dependencies` currently has ONLY:
  ```kotlin
  commonTest.dependencies {
      implementation(kotlin("test"))
      implementation(libs.jetbrains.compose.ui.test)
  }
  jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
  }
  ```
  **There is NO `kotlinx-coroutines-test` and NO `ktor-client-mock` on the test classpath yet.** Both must be added (Step 1). `homebase-auth` has only `commonMain`/`commonTest` source sets for our code (plus per-platform `*Main` for ktor engines) — so the new test goes in **commonTest**.
- **Convention + exemplar to match:**
  - State-as-data unit test altitude: `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/vault/note/VaultNoteEditorViewModelTest.kt` (no VM construction; asserts on UiState).
  - Ktor `MockEngine` idiom: `homebase-api/src/jvmTest/kotlin/id/homebase/api/client/profile/PublicProfileProviderCachedTest.kt:45-55` (a `MockEngine { _ -> respond(bytes, status) }` + `HttpClient(mockEngine)`, with `nextStatus`/`nextException` fields the test flips per case) and `runTest` from `kotlinx.coroutines.test` (its import line 20).
  - Catalog aliases already present (verified): `libs.ktor.client.mock` (libs.versions.toml:145), `libs.kotlinx.coroutines.test` (libs.versions.toml:176). Sibling precedent `homebase-chat/build.gradle.kts:134` declares `implementation(libs.kotlinx.coroutines.test)` in its `jvmTest`.

## Commands you will need
| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-auth/src homebase-auth/build.gradle.kts gradle/libs.versions.toml` | empty, or only changes you can reconcile with this plan |
| Confirm catalog aliases exist | `grep -nE "ktor-client-mock|kotlinx-coroutines-test" gradle/libs.versions.toml` | both lines present (145 / 176) |
| Compile auth tests for JVM (primary gate) | `./gradlew :homebase-auth:jvmTest` | `BUILD SUCCESSFUL`, new test class runs, all green |
| Compile commonMain still OK (sanity) | `./gradlew :homebase-auth:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Confirm no source file changed | `git diff --name-only -- homebase-auth/src/commonMain` | empty |

## Scope
**In scope (only these files):**
- `homebase-auth/build.gradle.kts` — add `libs.kotlinx.coroutines.test` and `libs.ktor.client.mock` to `commonTest.dependencies` (test-only; does not touch shipping code).
- `homebase-auth/src/commonTest/kotlin/id/homebase/auth/login/LoginViewModelLogicTest.kt` — **new** test file (the deliverable). Named `...LogicTest` (not `...ViewModelTest`) to be honest that it tests the VM's *logic*, not a constructed VM instance.

**Out of scope (do NOT touch):**
- `homebase-auth/src/commonMain/.../login/LoginViewModel.kt` — source change forbidden by the spec; the whole point is to test it as-is.
- `LoginUiState.kt` / `LoginUiEvent.kt` / `LoginUiAction.kt` — no production change; the test reads these types, doesn't modify them.
- `LoginUiTest.kt` — leave the existing render smoke test alone; this plan is additive.
- `YouAuthFlowManager.kt`, `DriveStatus.kt`, `OdinId.kt`, `AuthConnectionCoordinator.kt`, `DriveSyncManager.kt`, `NotificationService.kt`, `UsernameStorage.kt` — read-only; do not refactor any to an interface to enable VM construction (that's a separate, larger plan).
- `homebase-core` / any other module — unaffected.

## Steps

1. **Add the two test-only dependencies.** Edit `homebase-auth/build.gradle.kts`, the `commonTest.dependencies { … }` block, to:
   ```kotlin
   commonTest.dependencies {
       implementation(kotlin("test"))
       implementation(libs.jetbrains.compose.ui.test)
       implementation(libs.kotlinx.coroutines.test)
       implementation(libs.ktor.client.mock)
   }
   ```
   Leave `jvmTest.dependencies` unchanged. Do not add these to `commonMain` (test-only).
   **Verify:** `grep -nE "kotlinx.coroutines.test|ktor.client.mock" homebase-auth/build.gradle.kts` → both lines present in the `commonTest` block.

2. **Create the test file with the OdinId-gate + state-shape cases (no HTTP, no coroutines yet).** Create `homebase-auth/src/commonTest/kotlin/id/homebase/auth/login/LoginViewModelLogicTest.kt` in package `id.homebase.auth.login`. First add the cases that need neither `MockEngine` nor `runTest`, so the file compiles and runs before the harder cases:
   - **`startLogin_gate_acceptsValidHomebaseId`**: `OdinId("frodo.dotyou.cloud")` does not throw, and `.domainName == "frodo.dotyou.cloud"`. Asserts the path that lets `startLogin` proceed (mirrors LoginViewModel.kt:120-121).
   - **`startLogin_gate_rejectsInvalidHomebaseId_producesInvalidIdError`**: wrap `OdinId("not a domain!!")` in a try/catch exactly like the VM (LoginViewModel.kt:120-128). Assert the catch fires, then build the same state the VM builds — `LoginUiState().copy(error = LoginError.Res(MR.string.login_error_invalid_id))` — and assert `state.error is LoginError.Res`. (Import: `import id.homebase.resources.MR` and `import id.homebase.resources.login_error_invalid_id` — per MEMORY "MR.string needs explicit import", each `MR.string.X` needs its own `import id.homebase.resources.X`.) Use another clearly-invalid input too, e.g. `""` (blank → `OdinId.validate`/constructor throws).
   - **`uiState_eventConsumed_clearsEvent`** (data-shape, mirrors `eventConsumed()` LoginViewModel.kt:65-69): start from `LoginUiState(uiEvent = LoginUiEvent.NavigateToHome)`, apply `.copy(uiEvent = null)`, assert `uiEvent == null`.
   - **`uiState_authenticatedTransition_shape`** (mirrors `handleAuthenticatedUser()` LoginViewModel.kt:309-316): from `LoginUiState(isLoading = true, homebaseId = "frodo.dotyou.cloud")`, apply `.copy(isLoading = false, isAuthenticated = true, error = null, uiEvent = LoginUiEvent.NavigateToHome)`; assert `isAuthenticated`, `!isLoading`, `error == null`, `uiEvent is LoginUiEvent.NavigateToHome`.
   - **`loginError_message_carriesText`**: `LoginError.Message("Invalid identity")` round-trips `.text == "Invalid identity"`.
   Use `kotlin.test.Test` / `assertTrue` / `assertEquals` / `assertFailsWith` / `assertIs` (`kotlin.test.assertIs`). No coroutines in these cases.
   **Verify:** `./gradlew :homebase-auth:jvmTest` → `BUILD SUCCESSFUL`, the new class appears in the run, these cases pass.

3. **Add the ping-decision cases driven by a Ktor `MockEngine`.** This reproduces `isValidHomebaseId`'s 200→true / non-200→false / throw→false contract (LoginViewModel.kt:97-115) **without** constructing the VM — write a tiny local helper that performs the exact same GET-and-map-status the VM does, against a `MockEngine`-backed `HttpClient`. Model on `PublicProfileProviderCachedTest.kt:45-55`.

   **CAVEAT (do not skip):** the VM's call uses `httpClient.get(url) { timeout { … } }` (LoginViewModel.kt:100-105). The `timeout {}` request-builder extension is provided by the **`HttpTimeout` plugin**, which is NOT installed on a bare `HttpClient(MockEngine)`. You have two acceptable options — pick (a) for fidelity:
   - **(a)** install the plugin in the test client so the helper can use the identical `timeout {}` block:
     ```kotlin
     import io.ktor.client.plugins.HttpTimeout
     val client = HttpClient(mockEngine) { install(HttpTimeout) }
     ```
   - **(b)** omit the `timeout {}` block in the test helper (it does not affect the status→boolean decision under test). If you choose (b), add a one-line comment saying the timeout is exercised only on the real client, not here.

   Local helper to put in the test (it is the decision under test, copied verbatim in spirit from the VM):
   ```kotlin
   private suspend fun pingIsValid(client: HttpClient, identity: String): Boolean =
       try {
           val response = client.get("https://$identity/api/v2/health/ping")
           response.status.value == 200
       } catch (t: Throwable) {
           false
       }
   ```
   Cases (each its own `@Test fun … = runTest { … }`, importing `kotlinx.coroutines.test.runTest`):
   - **`ping_status200_isValid`**: MockEngine responds `HttpStatusCode.OK` → `pingIsValid(...) == true`.
   - **`ping_status404_isInvalid`**: responds `HttpStatusCode.NotFound` → `false`.
   - **`ping_status500_isInvalid`**: responds `HttpStatusCode.InternalServerError` → `false`.
   - **`ping_thrownException_isInvalid`**: MockEngine lambda `throws RuntimeException("network down")` (or `io.ktor.utils.io.errors.IOException`) → `pingIsValid(...) == false` (covers the `catch (t: Throwable)` branch). Use a `respond("", status)` for the non-200 bodies like the exemplar does.
   Imports: `io.ktor.client.HttpClient`, `io.ktor.client.engine.mock.MockEngine`, `io.ktor.client.engine.mock.respond`, `io.ktor.client.request.get`, `io.ktor.http.HttpStatusCode`.
   **Verify:** `./gradlew :homebase-auth:jvmTest` → `BUILD SUCCESSFUL`, all four ping cases pass.

4. **Add the `observeAuthState` terminal-vs-loading decision cases.** This is the highest-value, fragile-flow coverage: "hold the initial loading screen until drive sync has STOPPED (Completed OR Failed), or skip it if sync can't even start (Idle && !isConnecting)" (LoginViewModel.kt:264-278). Express the exact predicate as a pure function in the test and assert its truth table against the real `SyncState` types:
   ```kotlin
   // Mirror of LoginViewModel.observeAuthState's Authenticated branch (lines 271-274).
   private fun shouldFinishLoading(syncState: SyncState, isConnecting: Boolean): Boolean {
       val syncStopped = syncState is SyncState.Completed || syncState is SyncState.Failed
       val syncCannotStart = syncState is SyncState.Idle && !isConnecting
       return syncStopped || syncCannotStart
   }
   ```
   Cases (plain `@Test`, no coroutines needed — these are pure):
   - `authenticated_syncCompleted_finishesLoading` → `shouldFinishLoading(SyncState.Completed, isConnecting = false) == true`.
   - `authenticated_syncFailed_finishesLoading` → `shouldFinishLoading(SyncState.Failed, false) == true` (a network-drop terminal still closes the screen — the documented behaviour at lines 264-270).
   - `authenticated_syncSyncing_keepsLoading` → `shouldFinishLoading(SyncState.Syncing, false) == false`.
   - `authenticated_syncIdle_notConnecting_finishesLoading` → `shouldFinishLoading(SyncState.Idle, false) == true` (the `syncCannotStart` skip).
   - `authenticated_syncIdle_stillConnecting_keepsLoading` → `shouldFinishLoading(SyncState.Idle, isConnecting = true) == false` (must NOT skip while a connection is still settling — this is the subtle regression-prone case).
   Also add one `computeSyncState` integration check tying the real aggregate to the predicate so the two stay coherent: build a `Map<Uuid, DriveStatus>` with one own-drive in `DriveState.Completed`, assert `computeSyncState(map) == SyncState.Completed`, then `shouldFinishLoading(computeSyncState(map), false) == true`. (Imports: `id.homebase.api.sync.SyncState`, `id.homebase.api.sync.DriveState`, `id.homebase.api.sync.DriveStatus`, `id.homebase.api.sync.computeSyncState`, `kotlin.uuid.Uuid`. `DriveStatus`/`Uuid` need the `@OptIn(ExperimentalUuidApi::class)` — `homebase-auth/build.gradle.kts` already sets `optIn.add("kotlin.uuid.ExperimentalUuidApi")` at the kotlin block level, so no per-file opt-in is required; if the compiler still complains, add `@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)` at the top like `VaultNoteEditorViewModelTest.kt:1`.)
   **Verify:** `./gradlew :homebase-auth:jvmTest` → `BUILD SUCCESSFUL`, all six decision cases pass.

5. **Add an `YouAuthState`-mapping case** so the `Authenticated/Authenticating/Unauthenticated/Error` → `isLoading/isAuthenticated/error` mapping (LoginViewModel.kt:262-299) is pinned as data:
   - `authState_unauthenticated_clearsLoadingAndAuth`: from `LoginUiState(isLoading = true)`, apply the Unauthenticated branch's `.copy(isLoading = false, isAuthenticated = false)`; assert both false.
   - `authState_error_setsMessageError`: construct `YouAuthState.Error("boom")`, then build `LoginUiState().copy(isLoading = false, isAuthenticated = false, error = LoginError.Message((it as YouAuthState.Error).message))`; assert `error is LoginError.Message` and `.text == "boom"`.
   - `youAuthState_authenticated_constructs`: `YouAuthState.Authenticated(OdinId("frodo.dotyou.cloud"), clientAuthToken = "cat", sharedSecret = "ss")` constructs and `.identity.domainName == "frodo.dotyou.cloud"` (sanity that the real type is usable from auth's test classpath; `:homebase-api` is `api(...)` so it's visible). Import `id.homebase.api.youauth.YouAuthState`, `id.homebase.api.common.OdinId`.
   **Verify:** `./gradlew :homebase-auth:jvmTest` → `BUILD SUCCESSFUL`, these cases pass.

6. **Final sanity + confirm no production change.**
   **Verify:**
   - `./gradlew :homebase-auth:jvmTest` → `BUILD SUCCESSFUL` (whole new suite green).
   - `./gradlew :homebase-auth:compileKotlinJvm` → `BUILD SUCCESSFUL` (commonMain untouched still compiles).
   - `git diff --name-only -- homebase-auth/src/commonMain` → **empty** (no source file changed).
   - `git diff --name-only` → shows exactly `homebase-auth/build.gradle.kts` and the new test file (plus pre-existing unrelated dirty files from the working tree).

7. **Update `plans/README.md`** — mark this plan's row done/landed per that file's existing convention (read it first to match the column format; do not invent a new format).

## Test plan
- **New file:** `homebase-auth/src/commonTest/kotlin/id/homebase/auth/login/LoginViewModelLogicTest.kt`.
- **Cases & the regressions they pin:**
  - Invalid-id gate (`startLogin_gate_rejectsInvalidHomebaseId_*`) — pins that a malformed homebase id produces `LoginError.Res(login_error_invalid_id)` and never proceeds to authorize (LoginViewModel.kt:120-128). Regression target: someone loosening the `OdinId` try/catch and launching auth against garbage.
  - Ping contract (`ping_status200/404/500/thrownException_*`) — pins the 200-only success rule and the catch-all-false rule of `isValidHomebaseId` (LoginViewModel.kt:107-114). Regression target: a refactor that treats any 2xx (or a thrown timeout) as "valid" and lets the OAuth flow start against an unreachable host.
  - Hold-the-screen rule (`authenticated_sync*` + `computeSyncState` tie-in) — pins the exact `syncStopped || syncCannotStart` truth table, including the **must-keep-loading-while-connecting** case (LoginViewModel.kt:271-278). Regression target: the known-fragile "stuck on the loading screen forever" / "closes too early before sync" bugs.
  - State/event shapes (`uiState_*`, `loginError_*`, `youAuthState_*`) — pin the data contracts the VM relies on.
- **Model after:** `homebase-core/.../vault/note/VaultNoteEditorViewModelTest.kt` (state-as-data altitude) for Steps 2/4/5, and `homebase-api/.../profile/PublicProfileProviderCachedTest.kt` (`MockEngine` + `runTest`) for Step 3.
- **Verify command:** `./gradlew :homebase-auth:jvmTest`.

## Done criteria
- [ ] `homebase-auth/build.gradle.kts` `commonTest.dependencies` contains `libs.kotlinx.coroutines.test` and `libs.ktor.client.mock`; nothing added to `commonMain`.
- [ ] `homebase-auth/src/commonTest/kotlin/id/homebase/auth/login/LoginViewModelLogicTest.kt` exists, package `id.homebase.auth.login`.
- [ ] It contains, at minimum: 2 OdinId-gate cases, 4 ping cases (200/404/500/throw), 5 hold-the-screen decision cases + 1 `computeSyncState` tie-in, and ≥3 state/YouAuthState mapping cases.
- [ ] `./gradlew :homebase-auth:jvmTest` → `BUILD SUCCESSFUL`, every new case green.
- [ ] `git diff --name-only -- homebase-auth/src/commonMain` → empty (no production source changed).
- [ ] `LoginViewModel.kt` and `LoginUiTest.kt` are byte-for-byte unchanged.
- [ ] `plans/README.md` row updated.

## STOP conditions
- **Drift check fails** (Step-0 `git diff --stat` shows `LoginViewModel.kt` / `build.gradle.kts` / catalog changes you cannot reconcile) → STOP, re-read, report.
- **Collaborators are now interfaces.** If `YouAuthFlowManager`, `AuthConnectionCoordinator`, `DriveSyncManager`, `UsernameStorage`, and `NotificationService` have ALL become interfaces (or gained injectable fakes) such that a real `LoginViewModel(...)` can be constructed in `commonTest` without platform globals → STOP and report; the better deliverable is a true VM-under-test and this plan should be revised.
- **`timeout {}` plugin error** in Step 3 (`HttpTimeout` not installed) and option (a) doesn't resolve it → fall back to option (b), note it, continue. Do NOT add `HttpTimeout` to `commonMain`.
- **`MR.string.login_error_invalid_id` won't resolve** in commonTest (missing generated resource accessor) → it should: `homebase-auth` generates `Res`/`MR` accessors and `:homebase-api`'s `MR` is on the classpath via `api(...)`. If a specific `MR.string.X` is genuinely absent, drop that single assertion to a structural `LoginError.Res(...)` shape check that doesn't reference the missing accessor, note it, continue — do not block the whole suite on one resource id.
- **Test flakiness / hang** under `runTest` → the ping helper is fully driven by `MockEngine` (deterministic, no real network); if it hangs, you've accidentally created a real engine — fix the client construction, don't add timeouts/retries.

## Maintenance notes
- A reviewer should scrutinise that the test's local `pingIsValid` / `shouldFinishLoading` helpers stay a **faithful mirror** of `LoginViewModel`'s real branches — these are copies, so they can drift from the source. Each helper carries a comment pointing at the exact source lines; if `LoginViewModel` changes those branches, the helper (and this plan's line refs) must be updated. This copy-the-predicate approach is the honest second-best given the VM can't be constructed; it is explicitly NOT a substitute for a real VM test.
- **Deferred follow-up (separate plan, larger):** to enable a *true* `LoginViewModel`-under-test, extract narrow interfaces for the VM's collaborators (e.g. an `AuthFlow` facade exposing `authState`, `authorize`, `handleCallback`, `onAppResumed`; a `SyncStatus` source; a `UsernameStore`) and inject those, so `commonTest` can supply in-memory fakes and assert real `uiState`/event emissions end-to-end (including the `onCallbackUrl` → Authenticated transition the finding wanted but which is unreachable today because `handleCallback` mutates `YouAuthFlowManager`'s private `_authState`, not anything observable from a fake). That refactor also lets you finally test `onCallbackUrl` error paths. It touches shipping code, so it is out of scope here.
- **Also note** (do not fix here): `LoginUiEvent` lives inside `LoginUiState.uiEvent` rather than a separate one-time `SharedFlow`, contrary to CLAUDE.md's convention and Plan 014's direction. Flag it to the owner; converting it is a behavioural change, not a test.
