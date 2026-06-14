# Plan 013: Split AppNavHost.kt's single 1502-line file into per-feature NavGraphBuilder extension functions

> Executor instructions: follow step by step; run every verification command and confirm the expected result before the next step; if a STOP condition occurs, stop and report; when done, update this plan row in plans/README.md.
> Drift check (run first): `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt homebase-common/src/commonMain/kotlin/id/homebase/core/ui/navigation/Routes.kt`. If `AppNavHost.kt` changed since this plan was written, compare the Current state excerpts below to live code first; on mismatch, STOP. (`Routes.kt` is out of scope but listed so you can see whether route definitions shifted.)

## Status
- Priority: P3
- Effort: M
- Risk: MED
- Depends on: none
- Category: tech-debt
- Planned at: commit 45e2832e, 2026-06-14

## Why this matters
`AppNavHost.kt` is a single 1502-line file whose `@Composable fun AppNavHost(...)` body spans lines 171–1428 and contains **43** `composable<Route.X>` route registrations (the `NavHost { ... }` block, lines 686–1413) plus ~12 closure-captured values (`navController`, `isAuthenticated`, three `open*` callbacks, three feature ViewModels, `isVaultActivated`, `uriHandler`, `showingOnlyDetailPane`). Every feature team that adds, moves, or edits a screen edits this one giant function, so it is a perpetual merge-conflict hotspot and is impossible to review at a glance — a reviewer cannot tell whether a one-line route change is correct without scrolling past 40 unrelated routes. Extracting the route registrations into per-feature `NavGraphBuilder.xxxGraph(...)` extension functions (one file per feature) shrinks `AppNavHost` to its scaffold + the parameters it threads, lets each feature's routes be reviewed in isolation, and reduces conflict surface — **without changing a single route string, argument, destination, or navigation call** (those must stay byte-identical or deep links / `popBackStack(Route.X)` targets / share-intent navigation break). This is pure mechanical re-homing of verbatim blocks.

## Current state

All excerpts read at commit 45e2832e, verbatim with real line numbers.

### File layout / important fact about where `Route` lives
The navigation package `id.homebase.core.ui.navigation` is **split across two modules**:
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/` — `AppNavHost.kt`, `AppViewModel.kt`, `BackStackGate.kt`. **This is where the new graph files go.**
- `homebase-common/src/commonMain/kotlin/id/homebase/core/ui/navigation/Routes.kt` — the `@Serializable sealed class Route` and all 43 route subtypes (`Route.ChatList`, `Route.MomentDetail`, …). **Out of scope — do not touch.**

Because both directories declare `package id.homebase.core.ui.navigation`, the new graph files (in the same package, in homebase-core which depends on homebase-common) can reference `Route.*`, `firstContaining`, `TopLevelRoute`, and the private file-local helpers **with no extra import** — *provided* those helpers are made non-`private` (see Step 1). This is why `AppNavHost.kt` line 354 writes `Route.ChatList` with no `import` for `Route`.

### `AppNavHost.kt` — the function signature and the captured locals (lines 170–192)
```kotlin
170	@Composable
171	fun AppNavHost(
172	    viewModel: AppViewModel,
173	    navController: NavHostController,
174	    youAuthFlowManager: YouAuthFlowManager
175	) {
176	    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
177	    val authState by youAuthFlowManager.authState.collectAsStateWithLifecycle()
178	    val isAuthenticated = authState is YouAuthState.Authenticated
...
186	    val momentsViewModel: MomentsViewModel = koinViewModel()
...
189	    val vaultViewModel: VaultViewModel = koinViewModel()
...
192	    val locationViewModel: LocationViewModel = koinViewModel()
```
The three `open*` callbacks captured by graph blocks are defined at lines 203–224 (`openMoments`, `openLocation`), 323–329 (`openVault`); `isVaultActivated` at line 321; `uriHandler` at line 225; `showingOnlyDetailPane` (a `var`) at line 238.

### The `NavHost { ... }` block that owns all 43 routes (lines 655–1413)
```kotlin
655	                    NavHost(
656	                        navController = navController,
657	                        startDestination = Route.AppLoading,
...
686	                        }) {
687	                        composable<Route.AppLoading> { ... }
...
1405	                        composable<Route.Defragmenter> { ... }
1412	                        }
1413	                    }
```

### Verified inventory of the 43 `composable<Route.X>` blocks (start line → feature group)
From `grep -n "composable<Route\." AppNavHost.kt`:

| Start line | Route | Group → target file |
|---|---|---|
| 687 | AppLoading | **rootGraph** → `RootNavGraph.kt` |
| 703 | Login | rootGraph |
| 714 | Home | rootGraph |
| 726 | Feed | rootGraph |
| 741 | ChatList | **chatGraph** → `ChatNavGraph.kt` |
| 831 | CreateConversation | chatGraph |
| 851 | CreateConversationSelectMembers | chatGraph |
| 862 | CreateConversationGroup | chatGraph |
| 879 | ArchivedConversations | chatGraph |
| 910 | ContactInfo | chatGraph |
| 919 | MessageInfo | chatGraph |
| 928 | Crop | **editorGraph** → `EditorNavGraph.kt` |
| 941 | Draw | editorGraph |
| 952 | ConversationSettings | chatGraph |
| 968 | ConversationMedia | chatGraph |
| 988 | GroupSettings | chatGraph |
| 1006 | GroupAddMembers | chatGraph |
| 1015 | GroupEdit | chatGraph |
| 1024 | Examples | rootGraph |
| 1030 | Settings | **settingsGraph** → `SettingsNavGraph.kt` |
| 1063 | MomentsOnboarding | **momentsGraph** → `MomentsNavGraph.kt` |
| 1072 | Moments | momentsGraph |
| 1092 | MomentDetail | momentsGraph |
| 1113 | MomentCompose | momentsGraph |
| 1131 | MomentAudience | momentsGraph |
| 1152 | CreateMomentGroup | momentsGraph |
| 1162 | MomentsSettings | momentsGraph |
| 1172 | LocationOnboarding | **locationGraph** → `LocationNavGraph.kt` |
| 1181 | Location | locationGraph |
| 1200 | LocationHistory | locationGraph |
| 1209 | LocationFindDevice | locationGraph |
| 1231 | LocationSettings | locationGraph |
| 1241 | Connections | settingsGraph |
| 1259 | NotificationSettings | settingsGraph |
| 1267 | AppearanceSettings | settingsGraph |
| 1275 | Vault | **vaultGraph** → `VaultNavGraph.kt` |
| 1311 | VaultSettings | vaultGraph |
| 1324 | VaultEntryDetail | vaultGraph |
| 1330 | VaultNoteEditor | vaultGraph |
| 1373 | Help | settingsGraph |
| 1385 | DeveloperMenu | settingsGraph |
| 1393 | StorageSettings | settingsGraph |
| 1405 | Defragmenter | settingsGraph |

8 graph files: `rootGraph` (5), `chatGraph` (12), `editorGraph` (2), `settingsGraph` (10), `momentsGraph` (7), `locationGraph` (5), `vaultGraph` (4). Total 45 — note `Crop`/`Draw` are shared by chat *and* moments composers, so they get their own `editorGraph` rather than being duplicated.

### Verified closure-capture surface per group (what each graph's parameters must thread)
From `awk 'NR>=687 && NR<=1412'` + grep of `openVault|openMoments|openLocation|momentsViewModel|locationViewModel|vaultViewModel|isVaultActivated|uriHandler|showingOnlyDetailPane`:

- **Every block** uses `isAuthenticated: Boolean` and `navController: NavHostController`.
- `rootGraph`: also `openVault`, `openMoments`, `openLocation` (Home block, lines 718–720).
- `chatGraph`: also `showingOnlyDetailPane` write (ChatList block line 825 — a setter `(Boolean) -> Unit`).
- `momentsGraph`: also `momentsViewModel` (MomentsOnboarding line 1066; `momentsViewModel.momentsExtendPermissionViewModel` Moments line 1076), `openMoments` (MomentsSettings line 1167).
- `locationGraph`: also `locationViewModel` (LocationOnboarding line 1175, Location line 1184), `openLocation` (LocationSettings line 1236).
- `vaultGraph`: also `vaultViewModel` (Vault lines 1288/1293/1294, VaultSettings unused but adjacent), `isVaultActivated: Boolean?` (line 1277), `openVault` (VaultSettings line 1318), `uriHandler` (VaultNoteEditor line 1367).
- `settingsGraph`: also `openMoments`, `openVault`, `openLocation` (Settings block navigates *by route*, not via the callbacks — it uses `navController.navigate(Route.MomentsSettings)` etc., so it needs **only** `navController` + `isAuthenticated`). **Verify** during extraction: the Settings block (lines 1030–1061) calls `navController.navigate(...)` exclusively, no `open*` capture. If true, `settingsGraph` needs no callbacks.
- `editorGraph`: only `isAuthenticated` + `navController`.

### The private file-local helpers the graphs need to see (lines 1431–1502)
```kotlin
1431	private fun NavHostController.selectConversationOnChatList(... ): Boolean { ... }   // 1431
1449	private fun NavDestination?.isTopLevelRoute(): Boolean { ... }                       // 1449
1458	private fun AnimatedContentTransitionScope<...>.isBetweenTopLevelRoutes(): Boolean  // 1458
1462	private fun NavDestination?.isVerticalSlideRoute(): Boolean { ... }                  // 1462
```
`selectConversationOnChatList` is called inside the ChatList, CreateConversation, CreateConversationGroup, ArchivedConversations, ConversationSettings, ConversationMedia, Connections blocks — i.e. it is used by **chatGraph** and **settingsGraph** (Connections). It is currently `private`. When those blocks move to separate files, `selectConversationOnChatList` **must be made non-private** (drop `private`, becoming an internal top-level extension in the package) or the moved blocks won't compile. `isTopLevelRoute` / `isBetweenTopLevelRoutes` / `isVerticalSlideRoute` are used only inside the `NavHost` transition lambdas and the bottom-nav code that **stay in `AppNavHost.kt`**, so they can remain `private`.

### `firstContaining` (BackStackGate.kt:20) is already public
```kotlin
20	suspend fun <T> Flow<List<T>>.firstContaining(predicate: (T) -> Boolean): List<T> =
```
It is used by the notification-tap `LaunchedEffect` (lines 348, 392, 425) which **stays in AppNavHost.kt** (it is not a `composable<>` route block). No change needed.

### Convention to match
`composable<T> { }` is an extension on `androidx.navigation.NavGraphBuilder` (from `androidx.navigation.compose.composable`, imported at `AppNavHost.kt:60`). The new extension functions therefore have receiver `NavGraphBuilder` and are **non-`@Composable`** (the `composable {}` content lambda is the composable scope, not the graph function). Exemplar for the file-splitting style in this repo: there is no prior NavGraphBuilder split, so match the import discipline of `AppNavHost.kt` itself — each new file needs the imports that its moved blocks actually reference (Compose, Koin, the screen composables, `Route`, `Uuid`, etc.). Per the repo's MR.string rule, none of these graph blocks contain `Text("literal")`, so the Konsist `ArchitectureTest` is unaffected.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Drift check | `git diff --stat 45e2832e..HEAD -- homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt homebase-common/src/commonMain/kotlin/id/homebase/core/ui/navigation/Routes.kt` | empty (no drift) |
| Compile homebase-core (JVM) — primary gate after EACH graph | `./gradlew :homebase-core:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| Compile homebase-core (Android) | `./gradlew :homebase-core:compileAndroidMain` | `BUILD SUCCESSFUL` |
| Compile homebase-core (iOS, macOS host only) | `./gradlew :homebase-core:compileKotlinIosSimulatorArm64` | `BUILD SUCCESSFUL` |
| Run the nav regression tests | `./gradlew :homebase-core:jvmTest --tests "id.homebase.core.ui.navigation.*"` | `BUILD SUCCESSFUL`, BackStackGateTest + NotificationTapColdStartTest pass |
| Confirm no route string changed | `git diff 45e2832e -- '*/ui/navigation/*' \| grep -E '^[-+].*Route\.[A-Z]' \| grep -vE '^(\+\+\+|---)'` | every `-Route.X` has a matching `+Route.X` (lines only moved, never edited) — see Step 9 |
| Count composable registrations preserved | `git grep -c "composable<Route\." -- 'homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/*.kt'` (sum across files) | total still **43** |

## Scope

**In scope (only these may be modified/created):**
- `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt` — remove the 43 route blocks from the `NavHost {}`, replace with calls to the new graph functions; drop `private` from `selectConversationOnChatList`; prune now-unused imports.
- NEW `homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/RootNavGraph.kt`
- NEW `.../ChatNavGraph.kt`
- NEW `.../EditorNavGraph.kt`
- NEW `.../SettingsNavGraph.kt`
- NEW `.../MomentsNavGraph.kt`
- NEW `.../LocationNavGraph.kt`
- NEW `.../VaultNavGraph.kt`
- `plans/README.md` (or create it) — update/add this plan's row.

**Out of scope (do NOT touch):**
- `homebase-common/.../navigation/Routes.kt` — route definitions; changing any `Route.X` or `@SerialName` breaks deep links / serialization.
- `BackStackGate.kt`, `BackStackGateTest.kt`, `NotificationTapColdStartTest.kt` — `firstContaining` already public; tests must pass unchanged (they are the regression guard).
- `AppViewModel.kt` — unrelated.
- Any screen composable (`ChatList`, `VaultScreen`, `MomentsScreen`, …) — only their *call sites* move, verbatim; their definitions are untouched.
- The `NotificationNavigationEvent` `LaunchedEffect` (lines 332–439), the Moments/Location onboarding-event `LaunchedEffect`s (442–471), the `Scaffold`/bottom-nav/rail code, the `TopLevelRoute` sealed class, and `TopLevelNavIcon` — these stay in `AppNavHost.kt`. Do not extract them; this plan is **only** the `composable<>` route blocks inside `NavHost {}`.

## Steps

> Golden rule for every step: **cut the `composable<Route.X> { ... }` block byte-for-byte** from `AppNavHost.kt` and **paste it unchanged** into the new graph function. The only edits permitted are (a) replacing a captured local with the identically-named extension-function parameter (names are kept the same, so the block body needs zero edits), and (b) import lines in the new file. If a block forces you to edit a `Route.X(...)` call, a route string, an argument order, or a `navigate`/`popBackStack` target — STOP (see STOP conditions).

1. **Make `selectConversationOnChatList` non-private.** In `AppNavHost.kt` line 1431, change `private fun NavHostController.selectConversationOnChatList(` → `internal fun NavHostController.selectConversationOnChatList(`. Leave the body and the other three private helpers (1449, 1458, 1462) as-is.
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL` (no functional change yet).

2. **Extract `editorGraph` first (smallest, 2 routes, only `isAuthenticated`+`navController`).** Create `EditorNavGraph.kt` in the package with:
   ```kotlin
   package id.homebase.core.ui.navigation

   import androidx.navigation.NavGraphBuilder
   import androidx.navigation.NavHostController
   import androidx.navigation.compose.composable
   import id.homebase.imageeditor.ui.CropScreen
   import id.homebase.imageeditor.ui.DrawScreen
   import org.koin.compose.viewmodel.koinViewModel

   fun NavGraphBuilder.editorGraph(
       navController: NavHostController,
       isAuthenticated: Boolean,
   ) {
       // paste the Crop block (AppNavHost.kt 928-940) verbatim
       // paste the Draw block (AppNavHost.kt 941-950) verbatim
   }
   ```
   Cut both blocks out of the `NavHost {}` in `AppNavHost.kt` and replace their former location with a single call `editorGraph(navController, isAuthenticated)` (placement inside `NavHost {}` is irrelevant to behaviour — order of `composable` registrations does not matter to the navigator).
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

3. **Extract `rootGraph` (5 routes: AppLoading 687, Login 703, Home 714, Feed 726, Examples 1024).** Signature:
   ```kotlin
   fun NavGraphBuilder.rootGraph(
       navController: NavHostController,
       isAuthenticated: Boolean,
       openVault: () -> Unit,
       openMoments: () -> Unit,
       openLocation: () -> Unit,
   )
   ```
   The Home block (714–724) references all three `open*` callbacks; AppLoading/Login/Feed/Examples need only `navController`+`isAuthenticated`. Move all five blocks; replace with `rootGraph(navController, isAuthenticated, openVault, openMoments, openLocation)`. Add imports the moved blocks use: `AppLoadingScreen`, `LoginScreen`, `HomeScreen`, `FeedScreen`, `RichTextExample`, `Route`, `koinViewModel`.
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

4. **Extract `chatGraph` (12 routes: ChatList 741, CreateConversation 831, CreateConversationSelectMembers 851, CreateConversationGroup 862, ArchivedConversations 879, ContactInfo 910, MessageInfo 919, ConversationSettings 952, ConversationMedia 968, GroupSettings 988, GroupAddMembers 1006, GroupEdit 1015).** Signature:
   ```kotlin
   fun NavGraphBuilder.chatGraph(
       navController: NavHostController,
       isAuthenticated: Boolean,
       onDetailPaneVisibilityChanged: (Boolean) -> Unit,
   )
   ```
   The ChatList block writes `showingOnlyDetailPane = it` at line 825 — replace **only that one assignment** with `onDetailPaneVisibilityChanged(it)` (keep the `@Suppress("AssignedValueIsNeverRead")` is no longer needed once it's a lambda call; you may drop it, but dropping it is the *only* allowed edit beyond the rename — if unsure, leave the suppress, it is harmless on a function call). At the call site in `AppNavHost.kt`, pass `onDetailPaneVisibilityChanged = { showingOnlyDetailPane = it }`. These blocks call `selectConversationOnChatList` (now `internal`, same package — resolves with no import). Add imports: `ConversationListScreen`, `ConversationListViewModel`, `ConversationLoadTrigger`, `CreateConversationScreen`, `SelectMembersScreen`, `CreateConversationGroupScreen`, `ArchivedConversationsScreen`, `ContactInfoScreen`, `MessageInfoScreen`, `ConversationSettingsScreen`, `ConversationMediaScreen`, `GroupSettingsScreen`, `AddGroupMembersScreen`, `EditConversationGroupScreen`, `Logger`, `Uuid`, `toRoute`, `collectAsStateWithLifecycle`, `LaunchedEffect`, `Route`, `koinViewModel`.
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

5. **Extract `momentsGraph` (7 routes: MomentsOnboarding 1063, Moments 1072, MomentDetail 1092, MomentCompose 1113, MomentAudience 1131, CreateMomentGroup 1152, MomentsSettings 1162).** Signature:
   ```kotlin
   fun NavGraphBuilder.momentsGraph(
       navController: NavHostController,
       isAuthenticated: Boolean,
       momentsViewModel: MomentsViewModel,
       openMoments: () -> Unit,
   )
   ```
   MomentsOnboarding uses `momentsViewModel` (line 1066); Moments uses `momentsViewModel.momentsExtendPermissionViewModel` (line 1076); MomentsSettings uses `openMoments` (1167); the rest use `navController`+`koinViewModel`. Move all seven; call `momentsGraph(navController, isAuthenticated, momentsViewModel, openMoments)`. Note Crop/Draw used by MomentCompose are **already** registered by `editorGraph` — keep MomentCompose's `navController.navigate(Route.Crop(...))` call verbatim (it navigates by route, no dependency on where the block lives).
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

6. **Extract `locationGraph` (5 routes: LocationOnboarding 1172, Location 1181, LocationHistory 1200, LocationFindDevice 1209, LocationSettings 1231).** Signature:
   ```kotlin
   fun NavGraphBuilder.locationGraph(
       navController: NavHostController,
       isAuthenticated: Boolean,
       locationViewModel: LocationViewModel,
       openLocation: () -> Unit,
   )
   ```
   LocationOnboarding (1175) + Location (1184) use `locationViewModel`; LocationSettings (1236) uses `openLocation`. The LocationFindDevice block (1209–1229) uses a fully-qualified `org.koin.core.parameter.parametersOf(...)` — copy verbatim (it's fully qualified, needs no new import). Call `locationGraph(navController, isAuthenticated, locationViewModel, openLocation)`.
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

7. **Extract `vaultGraph` (4 routes: Vault 1275, VaultSettings 1311, VaultEntryDetail 1324, VaultNoteEditor 1330).** Signature:
   ```kotlin
   fun NavGraphBuilder.vaultGraph(
       navController: NavHostController,
       isAuthenticated: Boolean,
       vaultViewModel: VaultViewModel,
       isVaultActivated: Boolean?,
       openVault: () -> Unit,
       uriHandler: UriHandler,   // match the exact type returned by getUriHandler(); see note
   )
   ```
   The Vault block (1275–1309) reads `isVaultActivated` and `vaultViewModel` (+`vaultViewModel.vaultExtendPermissionViewModel`). VaultSettings (1318) uses `openVault`. VaultNoteEditor (1367) calls `uriHandler.shareFile(Path(filePath))`. **Note on `uriHandler` type:** before writing the signature, run `grep -n "fun getUriHandler" -r homebase-core/src` and use that function's exact return type as the parameter type (do not guess `androidx.compose.ui.platform.UriHandler` — this repo wraps it with a custom interface that also has `shareFile`/`openUrl`). The VaultNoteEditor block keeps its custom vertical-slide `enterTransition`/`exitTransition`/`popEnterTransition`/`popExitTransition` arguments (1331–1354) **verbatim** — they are passed to `composable<Route.VaultNoteEditor>(...)`, not to the graph function. Call `vaultGraph(navController, isAuthenticated, vaultViewModel, isVaultActivated, openVault, uriHandler)`.
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

8. **Extract `settingsGraph` (10 routes: Settings 1030, Connections 1241, NotificationSettings 1259, AppearanceSettings 1267, Help 1373, DeveloperMenu 1385, StorageSettings 1393, Defragmenter 1405).** Signature:
   ```kotlin
   fun NavGraphBuilder.settingsGraph(
       navController: NavHostController,
       isAuthenticated: Boolean,
   )
   ```
   **Verify the assumption first:** the Settings block (1030–1061) navigates with `navController.navigate(Route.MomentsSettings)` / `Route.VaultSettings` / `Route.LocationSettings` — *by route, not via the `open*` callbacks*. Confirm with `awk 'NR>=1030 && NR<=1061'` that no `openMoments`/`openVault`/`openLocation` identifier appears. If confirmed, `settingsGraph` needs no callbacks. The Connections block (1241–1257) calls `selectConversationOnChatList` (now `internal`). Call `settingsGraph(navController, isAuthenticated)`.
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

9. **Prune now-dead imports in `AppNavHost.kt`** that referenced only the moved screen composables (e.g. `LoginScreen`, `HomeScreen`, all the `*Screen` imports that no longer appear in the file). Do **not** remove imports still used by the retained code (`MomentsViewModel`, `VaultViewModel`, `LocationViewModel`, `Route`, `koinViewModel`, `firstContaining` via `currentBackStack`, etc.). Let the compiler warnings guide you — unused-import is a warning, not an error, so removal is cosmetic; if uncertain, leave the import (a stray unused import never breaks the build).
   Verify: `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.

10. **Cross-target compile** (catch any source-set-specific resolution): run `./gradlew :homebase-core:compileAndroidMain` and, on a macOS host, `./gradlew :homebase-core:compileKotlinIosSimulatorArm64`. Both → `BUILD SUCCESSFUL`.

11. **Run the navigation regression tests:** `./gradlew :homebase-core:jvmTest --tests "id.homebase.core.ui.navigation.*"` → `BUILD SUCCESSFUL`, `BackStackGateTest` and `NotificationTapColdStartTest` green. These exercise the notification-tap back-stack gate that still lives in `AppNavHost.kt` and depend on `Route.ChatList` / `selectConversationOnChatList` behaviour — they are the guard that the verbatim moves changed nothing.

12. **Route-string diff audit (mechanical proof of zero route change):**
    ```bash
    git diff 45e2832e -- 'homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/*.kt' \
      | grep -E '^[-+]' | grep -E 'Route\.[A-Za-z]' | grep -vE '^(\+\+\+|---)' | sort | sed 's/^[-+]//' | sort | uniq -u
    ```
    Expected: **empty** (every removed `Route.X` line re-appears identically in an added line, so `uniq -u` shows no orphans). A non-empty result means a route reference was edited — STOP and revert that block to verbatim.

13. **Update `plans/README.md`** with this plan's row (status, file, one-line summary). If `plans/README.md` does not exist, create it with a header row and rows for plans 001–013.

## Test plan
No new product tests are required — this is a behaviour-preserving mechanical refactor and the existing `BackStackGateTest.kt` + `NotificationTapColdStartTest.kt` (in `homebase-core/src/commonTest/.../ui/navigation/`) are the regression suite. They cover: warm-path notification tap from a Detail screen navigating via ChatList (the membership-gate behaviour that stays in `AppNavHost.kt`), cold-start tap waiting for ChatList on the back stack, and share-intent writing `pendingConversationId` (which flows through `selectConversationOnChatList`, the helper made `internal` in Step 1). Model: these tests already use `runComposeUiTest` with a real `NavHost` and fakes — do not add mocks.

Verify command: `./gradlew :homebase-core:jvmTest --tests "id.homebase.core.ui.navigation.*"` → `BUILD SUCCESSFUL`.

Additionally, the diff audit in Step 12 is the *structural* regression test that no route string/arg drifted — treat a non-empty `uniq -u` output as a failing test.

## Done criteria
- [ ] `git status --porcelain` shows only: `AppNavHost.kt` (M), the 7 new `*NavGraph.kt` files (??/A), `plans/README.md` (M/??). No other file changed.
- [ ] `./gradlew :homebase-core:compileKotlinJvm` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-core:compileAndroidMain` → `BUILD SUCCESSFUL`.
- [ ] (macOS) `./gradlew :homebase-core:compileKotlinIosSimulatorArm64` → `BUILD SUCCESSFUL`.
- [ ] `./gradlew :homebase-core:jvmTest --tests "id.homebase.core.ui.navigation.*"` → `BUILD SUCCESSFUL` (both nav tests pass).
- [ ] `git grep -c "composable<Route\." -- 'homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/*.kt'` summed across files = **43**.
- [ ] `git grep -c "composable<Route\." -- 'homebase-core/src/commonMain/kotlin/id/homebase/core/ui/navigation/AppNavHost.kt'` = **0** (all route blocks left AppNavHost).
- [ ] Step 12 route-diff `uniq -u` output is empty (no route string/arg edited).
- [ ] `AppNavHost.kt` line count dropped substantially (target: under ~700 lines; was 1502).
- [ ] plans/README.md row for 013 present.

## STOP conditions (specific to this plan)
- **Drift:** `AppNavHost.kt` differs from the line-numbered excerpts above (someone added/moved a route since 45e2832e). Re-map the inventory table before proceeding; if a route moved groups, adjust — do not blindly cut by line number.
- **Out-of-scope edit forced:** a moved block won't compile without editing `Routes.kt`, `BackStackGate.kt`, a screen composable, or `AppViewModel.kt`. The only legitimate cross-file edit is making `selectConversationOnChatList` `internal` (Step 1, in `AppNavHost.kt`, in scope). Anything else → STOP and report.
- **`settingsGraph` assumption false:** if the Settings block (1030–1061) *does* reference `openMoments`/`openVault`/`openLocation` (not just `navController.navigate(Route.…)`), the Step 8 signature is wrong — add those three callback parameters and pass them at the call site, then continue. (Re-verify with the `awk` grep before adding params.)
- **`uriHandler` type guess fails:** if `vaultGraph` won't compile because the `UriHandler` parameter type is wrong, read `getUriHandler()`'s return type and use it exactly. Do not import `androidx.compose.ui.platform.UriHandler` blindly.
- **A `composable<>(...)` with custom transitions resists moving:** `VaultNoteEditor` (1330–1371) has four custom transition lambdas as `composable` arguments. They must move verbatim with the block. If they reference a captured local that isn't threaded, thread it — but they reference only `tween`/`slideInVertically`/`slideOutVertically` (stateless), so this should not happen. If it does → STOP.
- **Any verification command fails twice** after a correction attempt → STOP and report the exact compiler error.

## Maintenance notes
- **Reviewer focus:** the single most important review check is the Step 12 route-diff (`uniq -u` empty) — it mechanically proves no `Route.X`, `@SerialName`, navigate-target, or `popBackStack` argument changed. A reviewer should also eyeball each new graph file's signature against the "closure-capture surface" table above to confirm the threaded parameters match what the blocks actually use (a missing param surfaces as a compile error; a *superfluous* param is a silent smell to flag).
- **Future feature additions:** new top-level features (per `ADDING_ADDON_APPS.md`) should now register their routes by adding a `composable<>` to the appropriate `*NavGraph.kt` (or a new `xxxGraph.kt`) and a call inside `AppNavHost`'s `NavHost {}`, rather than growing `AppNavHost` again. Document this expectation in `ADDING_ADDON_APPS.md` as a deferred follow-up.
- **Deferred follow-up (not this plan):** the three `open*` callbacks and the notification-tap `LaunchedEffect` are still in `AppNavHost`. A later plan could lift the notification-tap navigation into a testable `NavController.handleNotificationEvent(...)` helper (mirroring how `firstContaining` was extracted for testability), but that changes behaviour-adjacent code and must not be bundled with this pure move.
- **Watch:** `selectConversationOnChatList` is now `internal` (package-visible) rather than `private`. If a future change wants it truly private again, all callers must be back in one file — i.e. this refactor would have to be reverted. Keep it `internal`, not `public`, to avoid exporting it from the `ComposeApp` framework surface.
