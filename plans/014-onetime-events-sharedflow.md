# Plan 014: Migrate the Home screen off uiEvent-in-state to a SharedFlow one-time-event channel (exemplar)

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeContract.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeViewModel.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeScreen.kt`. If any in-scope file changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP.

## Status
- Priority: P3
- Effort: M
- Risk: MED
- Depends on: none
- Category: tech-debt
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
CLAUDE.md (and the kmp-compose-multiplatform skill) mandate that one-time events (navigation, snackbar, file share) live on a **separate SharedFlow**, not inside `UiState`. 24 `UiState`/Contract files in this repo currently store a nullable `uiEvent` field and require a manual `eventConsumed()` round-trip (22 ViewModels declare `eventConsumed()`). Storing the event in state means: (a) it sits in `equals()`/diffing and re-fires on recomposition or config-change if the consume call is missed, (b) the event survives `WhileSubscribed` restarts, and (c) the screen must null the field back out, which is easy to forget. This plan establishes the correct pattern **on one representative screen (Home)** as a reviewable exemplar; the other 21 screens are an explicit follow-up backlog (listed in Maintenance notes), NOT touched here. The repo already has a clean target pattern to copy — `VaultViewModel` (`homebase-core/.../vault/VaultViewModel.kt:97-98`) exposes `events: SharedFlow<VaultUiEvent>` backed by `MutableSharedFlow(extraBufferCapacity = 4)` and emits with `_events.tryEmit(...)`.

## Current state

### 1. `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeContract.kt`  — UiState + event/action sealed interfaces
The event is stored as a nullable field on the state (line 10):
```kotlin
6  data class HomeUiState(
7      val isLoading: Boolean = false,
8      val appVersion: String,
9      val appName: String = "Homebase Chat",
10     val uiEvent: HomeUiEvent? = null
11 )
```
`HomeUiEvent` (lines 21-27) is already a well-formed sealed interface — keep it as-is:
```kotlin
21 sealed interface HomeUiEvent {
22     data class ShareFile(val file: Path) : HomeUiEvent
23     data class OpenFileBrowser(val file: Path) : HomeUiEvent
24     data object NavigateToExample : HomeUiEvent
25     data class ShowInfoMessage(val res: StringResource) : HomeUiEvent
26     data class ShowErrorMessage(val res: StringResource) : HomeUiEvent
27 }
```

### 2. `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeViewModel.kt`  — emits via state copy + exposes `eventConsumed()`
```kotlin
23     private val _uiState = MutableStateFlow(HomeUiState(appVersion = platformInfo.versionName))
24     val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
...
42     fun eventConsumed() {
43         _uiState.update { it.copy(uiEvent = null) }
44     }
45
46     private fun sendEvent(event: HomeUiEvent) {
47         _uiState.update { it.copy(uiEvent = event) }
48     }
```
`sendEvent(...)` is called from `onAction` (line 37) and from `exportLogFile()` (lines 61, 64) and `clearLogFile()` (lines 88, 91). Imports currently include `MutableStateFlow`, `StateFlow`, `asStateFlow`, `update`, `launch` (lines 14-18). `viewModelScope` is already imported (line 4).

### 3. `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeScreen.kt`  — collects via `LaunchedEffect(uiState.uiEvent)` and calls `viewModel.eventConsumed()`
```kotlin
71     val uiState by viewModel.uiState.collectAsStateWithLifecycle()
...
76     // Pre-resolve StringResource events at composition time — stringResource() cannot
77     // be called inside LaunchedEffect.
78     val snackbarText = when (val event = uiState.uiEvent) {
79         is HomeUiEvent.ShowInfoMessage -> stringResource(event.res)
80         is HomeUiEvent.ShowErrorMessage -> stringResource(event.res)
81         else -> ""
82     }
83
84     LaunchedEffect(uiState.uiEvent) {
85         when (val event = uiState.uiEvent) {
86             null -> {}
87             is HomeUiEvent.ShareFile -> {
88                 viewModel.eventConsumed()
89                 uriHandler.shareFile(event.file) { error -> ... }
...
101            is HomeUiEvent.NavigateToExample -> {
102                viewModel.eventConsumed()
103                onNavigateToExamples()
104            }
105
106            is HomeUiEvent.ShowInfoMessage,
107            is HomeUiEvent.ShowErrorMessage -> {
108                viewModel.eventConsumed()
109                scope.launch { snackbarHostState.showSnackbar(snackbarText) }
110            }
111        }
112    }
```
Note the `snackbarText` pre-resolution (lines 76-82): `stringResource()` cannot be called inside `LaunchedEffect`, so the two `ShowInfoMessage`/`ShowErrorMessage` resources are resolved at composition. When migrating to a SharedFlow collected in `LaunchedEffect`, the event payload is no longer readable at composition time, so this needs a different resolution strategy (see Step 3 / STOP note).

### Exemplar to match — `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/vault/VaultViewModel.kt`
```kotlin
31  import kotlinx.coroutines.flow.MutableSharedFlow
33  import kotlinx.coroutines.flow.SharedFlow
36  import kotlinx.coroutines.flow.asSharedFlow
...
97      private val _events = MutableSharedFlow<VaultUiEvent>(extraBufferCapacity = 4)
98      val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()
...
179         _events.tryEmit(VaultUiEvent.Activated)
```
This is the canonical in-repo shape: a private `MutableSharedFlow` with `extraBufferCapacity` (no replay), exposed read-only via `asSharedFlow()`, emitted with `tryEmit`. The kmp-compose-multiplatform skill's HomeViewModel pattern uses the same idea (events on a `SharedFlow`, screen collects in a `LaunchedEffect`). NOTE: `VaultViewModel` uses the plain `MutableSharedFlow(extraBufferCapacity = 4)` constructor (no explicit `replay`/`onBufferOverflow`). To keep the new pattern strictly within an already-imported, already-compiling surface, **match VaultViewModel exactly** rather than introducing `BufferOverflow.DROP_OLDEST` (which would need an extra import). `extraBufferCapacity = 4` with the default `onBufferOverflow = SUSPEND` and `tryEmit` means: if the buffer is somehow full (collector absent), `tryEmit` returns `false` and the event is dropped rather than queued — acceptable for fire-once UI events, and identical to the established Vault behavior.

### Test layout
- Existing core ViewModel/coroutine tests live in `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/...`. Model coroutine setup after `homebase-core/src/jvmTest/.../vault/VaultStreamTest.kt` (uses `runTest`, `TestScope`, `advanceUntilIdle`, `@OptIn(ExperimentalCoroutinesApi::class)`).
- `kotlinx-coroutines-test` (`runTest`) is already available to `homebase-core` jvmTest (VaultStreamTest uses it). **Turbine is NOT a dependency** — do NOT use it; collect into a `MutableList` via a `launch { events.toList(list) }`-style collector or `backgroundScope.launch { events.collect { list += it } }`.
- `PlatformInfo` (`homebase-common/.../util/PlatformInfo.kt`) is a plain interface with three members — fake it inline in the test:
```kotlin
interface PlatformInfo {
    val versionName: String
    val versionCode: Int
    val supportsBackgroundWake: Boolean
}
```

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeContract.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeViewModel.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeScreen.kt` | No output (no drift) |
| Compile homebase-core (JVM) — primary gate | `./gradlew :homebase-core:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile homebase-core (Android) | `./gradlew :homebase-core:compileAndroidMain` | `BUILD SUCCESSFUL` |
| Run the new VM test | `./gradlew :homebase-core:jvmTest --tests 'id.homebase.core.ui.screens.home.HomeViewModelEventsTest'` | `BUILD SUCCESSFUL`, new test green |
| Full core jvmTest (regression) | `./gradlew :homebase-core:jvmTest` | `BUILD SUCCESSFUL` |
| Konsist string-literal architecture gate (must still pass) | `./gradlew :homebase-common:jvmTest --tests '*ArchitectureTest*'` | `BUILD SUCCESSFUL` |
| Confirm uiEvent removed from Home | `git grep -n 'uiEvent\|eventConsumed' -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home` | No output |

## Scope

**In scope (only these files may change):**
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeContract.kt` — remove `uiEvent` from `HomeUiState`; keep `HomeUiEvent` sealed interface unchanged.
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeViewModel.kt` — add `_events`/`events` SharedFlow, emit via `tryEmit`, delete `eventConsumed()` and the state-mutating `sendEvent`.
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeScreen.kt` — collect `viewModel.events` in a `LaunchedEffect(Unit)`; remove all `viewModel.eventConsumed()` calls and the `uiState.uiEvent` reads.
- `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/home/HomeViewModelEventsTest.kt` — NEW test file.
- `plans/README.md` — NEW (does not yet exist); add a table with this plan's row (see Step 6).

**Out of scope (do NOT touch — each is a separate follow-up):**
- The other 21 ViewModels still using `eventConsumed()` (listed in Maintenance notes) — migrating them is the backlog this exemplar unblocks. Touching them here defeats the "one representative screen" scope.
- `HomeUi`, `FeatureCard`, `NavigationButton`, `HomeUiPreview` composables in HomeScreen.kt — they never read `uiEvent`; leave their bodies alone except where the top-level `HomeScreen` wiring forces an import change.
- Any DI wiring (`AppModule.kt`) — `HomeViewModel`'s constructor signature does not change.
- `VaultViewModel` and any shared helper extraction — do NOT extract a base class or generic helper. The finding's option (1) said "REUSE any established pattern"; the established pattern is the 2-line `MutableSharedFlow` idiom inlined per-ViewModel (as Vault does). Inlining keeps this plan's blast radius to the Home trio.

## Steps

1. **Drift check.** Run the drift command in the header. Expected: no output. If any of the three Home files differ from the Current-state excerpts above, STOP and report.
   Verify: `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeContract.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeViewModel.kt homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeScreen.kt` -> empty.

2. **HomeContract.kt — remove `uiEvent` from state.** Delete the `uiEvent` field (and the trailing comma on the line above so the data class stays valid). Result:
   ```kotlin
   data class HomeUiState(
       val isLoading: Boolean = false,
       val appVersion: String,
       val appName: String = "Homebase Chat",
   )
   ```
   Leave the `HomeUiEvent` sealed interface (lines 21-27) and its imports (`Path`, `StringResource`) exactly as they are — they are still referenced by the SharedFlow type.
   Verify: `./gradlew :homebase-core:compileKotlinJvm` -> will FAIL at this point (HomeViewModel/HomeScreen still reference `uiEvent`). This is expected mid-step; do not stop. Proceed to Step 3 immediately. (If you prefer a never-broken build, do Steps 2-4 as one edit batch before compiling; the verify for the batch is Step 4's compile.)

3. **HomeScreen.kt — collect from the SharedFlow.** This is the subtlest edit because of the `stringResource()`-in-`LaunchedEffect` constraint (Current state §3, lines 76-82). Apply ALL of:
   - Delete the `snackbarText` pre-resolution block (lines 76-82) and the `uiState.uiEvent`-keyed `LaunchedEffect` (lines 84-112).
   - Add, near the top imports: `import kotlinx.coroutines.flow.collectLatest` (or use `collect`).
   - Resolve the two snackbar strings at composition time as plain locals (stringResource IS callable here, in the composable body, just not inside LaunchedEffect):
     ```kotlin
     val logClearedText = stringResource(MR.string.log_cleared)
     val logClearFailedText = stringResource(MR.string.log_clear_failed)
     ```
     These two `MR.string` symbols are already imported via the `ShowInfoMessage`/`ShowErrorMessage` payloads' usage; if the imports were only present transitively through `HomeUiEvent`, add `import id.homebase.resources.log_cleared` and `import id.homebase.resources.log_clear_failed` (per the repo's "MR.string needs explicit import" convention).
   - Replace the removed `LaunchedEffect` with one keyed on the ViewModel (collect once for the lifetime of the screen):
     ```kotlin
     LaunchedEffect(Unit) {
         viewModel.events.collect { event ->
             when (event) {
                 is HomeUiEvent.ShareFile ->
                     uriHandler.shareFile(event.file) { error -> Logger.e(error) { "Failed to share file" } }
                 is HomeUiEvent.OpenFileBrowser ->
                     uriHandler.openFileBrowser(event.file) { error -> Logger.e(error) { "Failed to open file browser" } }
                 is HomeUiEvent.NavigateToExample -> onNavigateToExamples()
                 is HomeUiEvent.ShowInfoMessage ->
                     scope.launch { snackbarHostState.showSnackbar(logClearedText) }
                 is HomeUiEvent.ShowErrorMessage ->
                     scope.launch { snackbarHostState.showSnackbar(logClearFailedText) }
             }
         }
     }
     ```
     Note: there is no `null ->` branch anymore (a SharedFlow only emits real events), and no `viewModel.eventConsumed()` calls. The `event.res` indirection becomes unnecessary because only two info/error events exist and each maps to one fixed resource; if you want to keep `res`-driven flexibility, instead resolve via a `remember`-ed map of `StringResource -> String` built at composition — but the two-local approach above is simpler and matches current behavior 1:1 (`ShowInfoMessage` always carries `log_cleared`, `ShowErrorMessage` always carries `log_clear_failed`; see HomeViewModel.kt:88,91). If a future event carries an arbitrary `res`, the map approach will be needed — note it in the PR.
   - Confirm `import androidx.compose.runtime.LaunchedEffect` and `kotlinx.coroutines.launch` are still present (they are, lines 33 and 60).
   Verify: deferred to Step 4's compile.

4. **HomeViewModel.kt — expose the SharedFlow, delete state-event plumbing.** Apply ALL of:
   - Add imports: `import kotlinx.coroutines.flow.MutableSharedFlow`, `import kotlinx.coroutines.flow.SharedFlow`, `import kotlinx.coroutines.flow.asSharedFlow` (mirror VaultViewModel lines 31, 33, 36).
   - Add the channel alongside `uiState` (after line 24):
     ```kotlin
     private val _events = MutableSharedFlow<HomeUiEvent>(extraBufferCapacity = 4)
     val events: SharedFlow<HomeUiEvent> = _events.asSharedFlow()
     ```
   - Delete `fun eventConsumed()` (lines 42-44) entirely.
   - Rewrite `sendEvent` to emit on the flow instead of copying into state:
     ```kotlin
     private fun sendEvent(event: HomeUiEvent) {
         _events.tryEmit(event)
     }
     ```
     (All existing `sendEvent(...)` call sites at lines 37, 61, 64, 88, 91 keep working unchanged.)
   - Remove now-unused imports if and only if nothing else in the file uses them. `update` (line 17) was used only by `eventConsumed`/`sendEvent`'s state copy — after this edit it is unused; remove `import kotlinx.coroutines.flow.update`. Keep `MutableStateFlow`, `StateFlow`, `asStateFlow` (still used by `_uiState`/`uiState`).
   Verify: `./gradlew :homebase-core:compileKotlinJvm` -> `BUILD SUCCESSFUL`. Then `./gradlew :homebase-core:compileAndroidMain` -> `BUILD SUCCESSFUL`.

5. **Add the regression test.** Create `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/home/HomeViewModelEventsTest.kt` per the Test plan below.
   Verify: `./gradlew :homebase-core:jvmTest --tests 'id.homebase.core.ui.screens.home.HomeViewModelEventsTest'` -> `BUILD SUCCESSFUL`, all new cases green.

6. **Update plans/README.md.** This file does NOT exist yet (the `plans/` dir currently has only the numbered `.md` files, no README). Create `plans/README.md` with a status table and add this plan's row:
   ```markdown
   # Implementation plans

   | Plan | Title | Priority | Status |
   |---|---|---|---|
   | 014 | Migrate Home screen to SharedFlow one-time events (exemplar) | P3 | Done |
   ```
   (If a future executor finds the README already exists, just append the 014 row instead of recreating it.)
   Verify: `git status --short plans/README.md` -> shows the file staged/modified.

7. **Full regression + Konsist gate.**
   Verify: `./gradlew :homebase-core:jvmTest` -> `BUILD SUCCESSFUL`. Then `./gradlew :homebase-common:jvmTest --tests '*ArchitectureTest*'` -> `BUILD SUCCESSFUL` (confirms no new `Text("literal")` was introduced by the snackbar refactor).

## Test plan

**New file:** `homebase-core/src/jvmTest/kotlin/id/homebase/core/ui/screens/home/HomeViewModelEventsTest.kt`
Model after `homebase-core/src/jvmTest/.../vault/VaultStreamTest.kt` for the `runTest` + `backgroundScope.launch` collector pattern. Do NOT use Turbine (not a dependency). Use a fake `PlatformInfo`.

Cases:
1. `examplesClicked_emitsNavigateToExample` — construct `HomeViewModel(fakePlatformInfo)`; in `runTest`, start a collector (`backgroundScope.launch { vm.events.collect { collected += it } }`), call `vm.onAction(HomeUiAction.ExamplesClicked)`, `advanceUntilIdle()`, assert `collected.single() is HomeUiEvent.NavigateToExample`. **This is the core regression:** it proves the event reaches the SharedFlow (not `uiState`).
2. `event_isNotStoredInUiState` — after the same `ExamplesClicked`, assert the `HomeUiState` no longer has any event field. Since `uiEvent` is deleted, this is satisfied by the type no longer compiling with `.uiEvent`; encode it as a comment + assert `vm.uiState.value == vm.uiState.value` is unchanged across the action (state is stable, only `events` fired). Concretely: capture `vm.uiState.value` before and after the action and `assertEquals` they are equal — proving the event did NOT mutate state.
3. `event_doesNotReplayToLateCollector` — emit an event with NO collector attached (call `onAction` first), THEN attach a collector and `advanceUntilIdle()`; assert the late collector saw nothing (`assertTrue(collected.isEmpty())`). This proves no replay (the bug uiEvent-in-state had: a late/recomposed reader re-saw the stored event).

Fake to include in the test file:
```kotlin
private val fakePlatformInfo = object : PlatformInfo {
    override val versionName = "1.0.0"
    override val versionCode = 1
    override val supportsBackgroundWake = false
}
```
(`PlatformInfo` is `id.homebase.core.util.PlatformInfo` in homebase-common, on homebase-core's classpath.)

Verify: `./gradlew :homebase-core:jvmTest --tests 'id.homebase.core.ui.screens.home.HomeViewModelEventsTest'` -> `BUILD SUCCESSFUL`, 3 cases green.

## Done criteria
- [ ] `git grep -n 'uiEvent\|eventConsumed' -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home` returns NOTHING.
- [ ] `git grep -n 'val events: SharedFlow<HomeUiEvent>' -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/home/HomeViewModel.kt` returns exactly one line.
- [ ] `./gradlew :homebase-core:compileKotlinJvm` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-core:compileAndroidMain` -> `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-core:jvmTest --tests 'id.homebase.core.ui.screens.home.HomeViewModelEventsTest'` -> 3 new cases pass.
- [ ] `./gradlew :homebase-core:jvmTest` -> `BUILD SUCCESSFUL` (no regression).
- [ ] `./gradlew :homebase-common:jvmTest --tests '*ArchitectureTest*'` -> `BUILD SUCCESSFUL` (Konsist string gate still green).
- [ ] `git status --short` shows ONLY: the 3 Home files (modified), the new test file, and `plans/README.md`. No other file changed (especially none of the 21 backlog ViewModels).
- [ ] plans/README.md has a 014 row marked Done.

## STOP conditions
- **Drift:** any of the three Home source files differs from the Current-state excerpts (Step 1 non-empty) — STOP, re-verify against live code, do not blindly edit.
- **stringResource-in-LaunchedEffect:** if you find a Home event whose `StringResource` is genuinely dynamic (not the fixed `log_cleared`/`log_clear_failed` pair), the two-local approach in Step 3 is insufficient — STOP and switch to the `remember`-ed `StringResource -> String` map, and note it explicitly in the PR rather than silently dropping the indirection.
- **Compile fails twice** on `:homebase-core:compileKotlinJvm` after Step 4 with the same error — STOP and report the error; do not add `@Suppress`, try/catch, or unrelated edits to force it green.
- **Out-of-scope file needed:** if completing the migration appears to require editing any file outside the In-scope list (e.g. DI, another ViewModel, `HomeUi`), STOP — that means the scope assumption is wrong; report what's needed.
- **Turbine assumed:** if you reach for `app.cash.turbine`, STOP — it is not a dependency; use the plain collector pattern.

## Maintenance notes
- **This is an exemplar, not the full migration.** After this lands, the SAME mechanical change should be applied to the remaining **21** ViewModels that still expose `eventConsumed()` (and their UiState + Screen files). Backlog (each is a separate small PR; verified at commit 45e2832e):
  - homebase-auth: `login/LoginViewModel.kt`
  - homebase-chat: `addgroupmembers/AddGroupMembersViewModel.kt`, `archivedconversations/ArchivedConversationsViewModel.kt`, `contactinfo/ContactInfoViewModel.kt`, `conversationlist/ConversationListViewModel.kt`, `conversationsettings/ConversationSettingsViewModel.kt`, `createconversation/CreateConversationViewModel.kt`, `createconversationgroup/CreateConversationGroupViewModel.kt`, `editconversationgroup/EditConversationGroupViewModel.kt`, `groupsettings/GroupSettingsViewModel.kt`, `messageinfo/MessageInfoViewModel.kt`, `selectmembers/SelectMembersViewModel.kt`, `core/connections/ConnectRequestViewModel.kt`
  - homebase-core: `appearance/AppearanceSettingsViewModel.kt`, `connections/ConnectionsViewModel.kt`, `devmenu/DeveloperMenuViewModel.kt`, `help/HelpViewModel.kt`, `loading/AppLoadingViewModel.kt`, `settings/SettingsViewModel.kt`, `storage/StorageSettingsViewModel.kt`
  - image-editor-ui: `ui/CropEditorViewModel.kt`, `ui/DrawEditorViewModel.kt`
  (Note: `VaultViewModel`, `MomentsViewModel`, `FeedViewModel`, `LocationViewModel`, `ExtendPermissionViewModel`, `ConnectRequestViewModel`'s peers etc. that ALREADY use `MutableSharedFlow` are NOT in this backlog — confirm with `git grep -l 'fun eventConsumed' -- <module>` before migrating; that grep returned 22 files at plan time, of which Home is one, leaving 21.)
- **Reviewer should scrutinize:** (a) the snackbar string resolution — `stringResource()` must stay OUT of `LaunchedEffect`; verify the two locals resolve the same resources the old `event.res` did (`log_cleared` for info, `log_clear_failed` for error). (b) `LaunchedEffect(Unit)` keying — it must be keyed on a stable value so the collector is not torn down/recreated on every state change (do NOT key it on `uiState`). (c) That no `null ->` branch lingers (a SharedFlow can't emit null).
- **Deferred decision for the backlog:** whether to extract a shared `BaseEventViewModel` / `UiEventViewModel` helper once 3+ screens are migrated, vs. continuing to inline the 2-line idiom. This plan intentionally inlines (matching Vault) to avoid a cross-cutting abstraction before the pattern has proven itself across screens. Revisit after ~5 migrations.
- **Buffer semantics:** `extraBufferCapacity = 4` + `tryEmit` (matching Vault) drops events if no collector is attached AND the buffer is full. For Home that's fine (events only fire from user taps while the screen is on-screen and collecting). If a future Home event must fire while no collector is attached (e.g. a deep-link side effect), reconsider `replay`/`Channel` semantics — but that is out of scope here.
