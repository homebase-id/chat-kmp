# Adding an Add-on App

This guide shows how to scaffold a new "add-on app" (a top-level feature reachable
from the bottom navigation bar) following the pattern established by **Vault** on the
`add-vault-feature-scaffold` branch. Use it as a recipe — each step cites the real
Vault file you can diff against.

Canonical reference: `git show add-vault-feature-scaffold:<path>` for any of the
files mentioned below.

## What is an add-on app?

A self-contained feature that:

1. Shows up as a **toggleable icon** in the bottom navigation bar (or side rail for desktop).
2. Has an **onboarding screen** the first time the user taps it, with *Set it up* and
   *Dismiss* options.
3. When setup it flips a runtime **activation flag** via an "Extend Permissions" dialog — which in
   turn widens the set of drives the auth WebSocket subscribes to (and probably mounts it as an optional drive).
4. Has a **Settings sub-page** with a switch to hide its icon and (optionally) a
   biometrics switch plus other app specific settings.
5. Optionally gates the main screen behind **device biometrics** on entry.

Everything the user toggles is persisted in the encrypted local key/value store — no
server round-trip for the UI flags themselves.

## Anatomy — file/folder layout

Assuming a new add-on called **Foo**:

```
homebase-common/src/
  commonMain/kotlin/id/homebase/core/foo/
    FooBiometricAuth.kt          # expect (only if biometric-gated)
    FooPreferences.kt            # activated / iconVisible / biometricsEnabled flags
  androidMain/.../foo/FooBiometricAuth.android.kt
  appleMain/.../foo/FooBiometricAuth.apple.kt
  jvmMain/.../foo/FooBiometricAuth.jvm.kt   # returns Unavailable

homebase-core/src/commonMain/kotlin/id/homebase/core/ui/screens/foo/
  FooScreen.kt                   # authenticated content (biometric gate)
  FooOnboardingScreen.kt         # intro + Setup/Dismiss buttons + permission dialog
  FooViewModel.kt                # onboarding state + UiEvents
  FooUiState.kt                  # UiState data class + UiAction + UiEvent
  FooSettingsScreen.kt           # Settings sub-page
  FooSettingsViewModel.kt
  FooSettingsUiState.kt
```

Class names are load-bearing: Koin's `viewModelOf(::FooViewModel)` binds by
constructor reference, so renaming the VM requires an `AppModule.kt` update.

---

## Mandatory vs Optional Drives

The sync engine distinguishes two categories of drives:

| Category | Constant / Source | Examples |
|---|---|---|
| **Mandatory** | `mandatorySyncDrives` in `AppConfig.kt` | Chat, Contacts, Profile |
| **Optional** | `DriveRegistry` (files on the Chat drive) | Feed, Vault, … |

**Mandatory drives** (`chatLabeledDrive`, `contactLabeledDrive`, `profileLabeledDrive`) are always
mounted. They cannot be removed and require no user action. These are the minimum set needed for the
chat app to function.

**Optional drives** are persisted as a **single singleton file** on the user's **Chat drive**:

- `fileType = RegistryDriveFileType` (4242)
- `uniqueId = REGISTRY_UNIQUE_ID` (a fixed well-known UUID — exactly one such file per identity)
- `appData.content = OdinSystemSerializer.serialize(List<LabeledDrive>)`

The Chat drive is mandatory and synced to the local SQLDelight index on every device, so
`DriveRegistry.loadDrives()` is a pure local read of one row by `(identityId, chatDrive,
REGISTRY_UNIQUE_ID)` — offline-safe, no HTTP. The sync pipeline decrypts `appData.content`
before storing (`ServerFile.decryptAppData`), so consumers read plaintext.

Writes are read-modify-write against the singleton file via
`DriveUploadProvider.uploadFile` (initial create, `versionTag=null`) or
`updateFileByUniqueId` (subsequent edits, `versionTag=X`). The file itself is never deleted;
"unmount" is just "remove this drive from the array and rewrite the file." Concurrent edits
from two devices land as `VersionTagMismatch`; the loser re-fetches and retries, merging its
delta into the winner's list (up to `MAX_CONFLICT_RETRIES`).

### Cross-device propagation

A drive activated on Device A updates the registry file; the Chat-drive sync engine delivers
the updated file to Device B's local index; `DriveRegistry`'s `BatchReceived` observer matches
on `uniqueId == REGISTRY_UNIQUE_ID`, diffs the new list against the in-memory baseline, and
calls `AuthConnectionCoordinator.mountDrive(drive, persist = false)` for additions /
`unmountDrive(driveId, persist = false)` for removals. Both paths hot-update DriveSyncManager
and schedule a debounced WebSocket reconnect.

The same channel handles unmount: removing a drive on Device A shrinks the list and writes a
new revision; Device B's sync sees the updated file (it's still there, just with a smaller
list), the observer diffs out the removed alias, and unmounts locally.

### No default seed

There is no "first-startup seeds Feed" behaviour. A fresh install starts with mandatory drives
only. Feed (or any other add-on) appears on all devices only after the user explicitly
activates it once, somewhere.

### Activating an add-on drive at runtime

When the user completes the *Extend Permissions* flow for a new add-on, call the single
entry point on `AuthConnectionCoordinator`:

```kotlin
// Persists in DriveRegistry, hot-mounts in DriveSyncManager (HTTP polling starts
// immediately) and schedules a debounced WebSocket reconnect (~500ms) so real-time
// push arrives without waiting for an app restart. Safe to call multiple times in
// quick succession — the reconnects coalesce.
authConnectionCoordinator.mountDrive(fooLabeledDrive)
```

The symmetric `authConnectionCoordinator.unmountDrive(driveId)` handles user-initiated
removal the same way (registry + sync manager + WS refresh).

### Unmounting on 403 Forbidden

If `DriveSyncManager` receives a `BackendEvent.DriveResult.PermissionDenied` event (emitted by
`DriveSync` when the server returns 403), it calls `unmountDrive()` automatically. This clears the
sync indicator without touching `DriveRegistry` — the drive will be attempted again on the next
startup, which is intentional (session-only unmount, not a permanent removal).

---

## Step 1 — Preferences (runtime flags)

Reference: `homebase-common/src/commonMain/kotlin/id/homebase/core/vault/VaultPreferences.kt`.

One class with three `StateFlow<Boolean>` backed by `DatabaseManager.keyValue`:

| Flag | Default | Set by |
| --- | --- | --- |
| `activated` | `false` | *Extend* button on the permission dialog |
| `iconVisible` | `true` | Settings switch; also flipped to `false` when user dismisses onboarding |
| `biometricsEnabled` | `true` | Settings switch; gates `FooScreen` on entry |

Template (copy `VaultPreferences.kt`, rename, and give each key a **fresh UUID**):

```kotlin
class FooPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _activated = MutableStateFlow(readBoolean(ACTIVATED_KEY, default = false))
    val activated: StateFlow<Boolean> = _activated.asStateFlow()

    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = true))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    private val _biometricsEnabled = MutableStateFlow(readBoolean(BIOMETRICS_KEY, default = true))
    val biometricsEnabled: StateFlow<Boolean> = _biometricsEnabled.asStateFlow()

    suspend fun setActivated(value: Boolean) {
        if (_activated.value == value) return
        keyValue.upsertValue(ACTIVATED_KEY, encode(value))
        _activated.value = value
    }
    // setIconVisible / setBiometricsEnabled follow the same shape

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        val bytes: ByteArray = runCatching {
            keyValue.selectByKey(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Bump the penultimate byte for each new add-on — Vault = 0a01xx, next = 0a02xx, etc.
        val ACTIVATED_KEY:   Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0201")
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0202")
        val BIOMETRICS_KEY:  Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0203")
    }
}
```

**UUID namespacing.** Vault owns `0000...0a01xx`. Pick the next free `0a0Nxx` slot for
your add-on — these keys must be stable across releases, so never reuse an existing
range.

---

## Step 2 — Strings

Reference: `homebase-common/src/commonMain/composeResources/values/strings.xml`.

Add these keys under the prefix `foo_`:

```
foo_label
foo_onboarding_title   foo_onboarding_body_1   foo_onboarding_body_2
foo_onboarding_setup   foo_onboarding_dismiss
foo_permission_dialog_title   foo_permission_dialog_text
foo_permission_extend   foo_permission_cancel
foo_welcome
foo_settings_section   foo_settings_open
foo_settings_show_icon   foo_settings_biometrics
foo_biometric_prompt_title   foo_biometric_prompt_subtitle
```

Naming convention: `<feature>_<context>_<detail>`. Every user-facing string goes
through `stringResource()` — never hardcoded (CLAUDE.md).

---

## Step 3 — Routes

Reference: `homebase-common/src/commonMain/kotlin/id/homebase/core/ui/navigation/Routes.kt`.

Add three serializable objects to the `Route` sealed class:

```kotlin
@Serializable @SerialName("foo")            data object Foo : Route()
@Serializable @SerialName("foo-onboarding") data object FooOnboarding : Route()
@Serializable @SerialName("foo-settings")   data object FooSettings : Route()
```

---

## Step 4 — ViewModels + UiState

Reference: `VaultViewModel.kt`, `VaultUiState.kt`.

Two VMs. The onboarding VM mixes a `StateFlow<FooUiState>` (for the dialog-visible
flag) with a **`SharedFlow<FooUiEvent>`** (for one-time navigation signals):

```kotlin
class FooViewModel(private val fooPreferences: FooPreferences) : ViewModel() {
    private val _uiState = MutableStateFlow(FooUiState())
    val uiState: StateFlow<FooUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FooUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<FooUiEvent> = _events.asSharedFlow()

    fun onAction(action: FooUiAction) {
        when (action) {
            FooUiAction.SetupClicked ->
                _uiState.update { it.copy(showPermissionDialog = true) }

            FooUiAction.DismissOnboardingClicked -> viewModelScope.launch {
                fooPreferences.setIconVisible(false)
                _events.tryEmit(FooUiEvent.CloseOnboarding)
            }

            FooUiAction.PermissionExtendClicked -> viewModelScope.launch {
                fooPreferences.setActivated(true)
                _uiState.update { it.copy(showPermissionDialog = false) }
                _events.tryEmit(FooUiEvent.Activated)
            }

            FooUiAction.PermissionCancelClicked ->
                _uiState.update { it.copy(showPermissionDialog = false) }
        }
    }
}

data class FooUiState(val showPermissionDialog: Boolean = false)

sealed interface FooUiAction {
    data object SetupClicked : FooUiAction
    data object DismissOnboardingClicked : FooUiAction
    data object PermissionExtendClicked : FooUiAction
    data object PermissionCancelClicked : FooUiAction
}

sealed interface FooUiEvent {
    data object Activated : FooUiEvent
    data object CloseOnboarding : FooUiEvent
}
```

The **Settings VM** is simpler — one flat `FooSettingsUiState` mirroring the
preferences, kept in sync via two `viewModelScope.launch { prefs.xxx.collect { … } }`
blocks in `init`.

> Step 9b extends `FooViewModel`'s constructor with `AuthConnectionCoordinator`
> so the *Extend Permissions* click can run the single-call activation
> (persist + hot-mount + WS refresh). The snippet above only shows the
> preferences dependency to keep the onboarding state flow readable in isolation.

> **Rule (from CLAUDE.md):** one-time events go in `SharedFlow`, persistent state in
> `StateFlow`. Composables must use `collectAsStateWithLifecycle()`.

---

## Step 5 — Onboarding screen

Reference: `VaultOnboardingScreen.kt`.

Standard `Scaffold` + `TopAppBar` + centered `Column`: icon, title, two body
paragraphs, a `FilledTonalButton` (*Set it up*), a `TextButton` (*Dismiss*).

- **Dismiss** → `onAction(DismissOnboardingClicked)` — VM sets `iconVisible = false`
  and emits `CloseOnboarding`.
- **Set it up** → `onAction(SetupClicked)` — VM opens the permission `Dialog`.
- **Extend** inside the dialog → `onAction(PermissionExtendClicked)` — VM sets
  `activated = true` and emits `Activated`.
- **Cancel** inside the dialog → `onAction(PermissionCancelClicked)` — closes dialog.

---

## Step 6 — Main feature screen (optional biometric gate)

Reference: `VaultScreen.kt`.

If your add-on is sensitive, gate entry behind biometrics:

```kotlin
@Composable
fun FooScreen(onNavigateBack: () -> Unit) {
    val fooPreferences = koinInject<FooPreferences>()
    val title = stringResource(MR.string.foo_biometric_prompt_title)
    val subtitle = stringResource(MR.string.foo_biometric_prompt_subtitle)

    var authorized by remember { mutableStateOf(!fooPreferences.biometricsEnabled.value) }

    LaunchedEffect(Unit) {
        if (authorized) return@LaunchedEffect
        when (authenticateBiometric(title, subtitle)) {
            BiometricResult.Success, BiometricResult.Unavailable -> authorized = true
            BiometricResult.Failure -> onNavigateBack()
        }
    }
    // … render content only when `authorized`
}
```

The expect declaration in `homebase-common/commonMain/.../foo/FooBiometricAuth.kt`:

```kotlin
sealed interface BiometricResult {
    data object Success : BiometricResult
    data object Failure : BiometricResult
    data object Unavailable : BiometricResult
}

expect suspend fun authenticateBiometric(title: String, subtitle: String): BiometricResult
```

Actuals follow the Vault pattern:

- **Android** — `androidx.biometric.BiometricPrompt` from the `FragmentActivity`
  returned by `ActivityProvider.getActivity()`. Handles `BIOMETRIC_STRONG |
  BIOMETRIC_WEAK | DEVICE_CREDENTIAL`.
- **iOS (appleMain)** — `LAContext.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, …)`
  on the main dispatcher.
- **JVM (desktop)** — returns `BiometricResult.Unavailable`; `FooScreen` treats
  Unavailable the same as Success so desktop users are not locked out.

If the add-on doesn't need biometrics, skip this step, skip the biometric actuals,
and drop the biometric Switch in Step 7.

---

## Step 7 — Settings entry + sub-page

Reference: `SettingsScreen.kt`, `VaultSettingsScreen.kt`, `VaultSettingsViewModel.kt`.

### In `SettingsScreen.kt`

Add a new callback parameter `onNavigateToFooSettings: () -> Unit` and render a row:

```kotlin
SettingsItemAction(
    imageVector = Icons.Outlined.<FooIcon>,
    text = stringResource(MR.string.foo_settings_section),
    onClick = onNavigateToFooSettings,
)
```

### `FooSettingsScreen`

Three rows:

| Row | Control | Action |
| --- | --- | --- |
| `foo_settings_open` | — (tap the row) | `onOpenFoo()` |
| `foo_settings_show_icon` | `Switch` bound to `uiState.iconVisible` | `SetIconVisible(it)` |
| `foo_settings_biometrics` | `Switch` bound to `uiState.biometricsEnabled` | `SetBiometricsEnabled(it)` |

`FooSettingsViewModel` simply forwards the switch actions to
`fooPreferences.setIconVisible` / `setBiometricsEnabled` and mirrors their
`StateFlow`s into its `UiState`.

The *Show Foo icon* switch is the one the user has to find if they previously
dismissed onboarding — it's the only way the icon comes back.

---

## Step 8 — Bottom-bar integration (the dynamic part)

Reference: `AppNavHost.kt`.

### 8a — Extend the `TopLevelRoute` sealed class

```kotlin
sealed class TopLevelRoute(
    val route: Route, val label: String, val icon: ImageVector
) {
    data object Chat : TopLevelRoute(Route.ChatList, "Chats", BootstrapChat)
    data object Foo  : TopLevelRoute(Route.Foo, "Foo", Icons.Outlined.<FooIcon>)
    data object Home : TopLevelRoute(Route.Home, "Home", Icons.Default.Home)
}
```

### 8b — Build the bar reactively from the visibility flag

```kotlin
val fooPreferences = koinInject<FooPreferences>()
val fooIconVisible by fooPreferences.iconVisible.collectAsStateWithLifecycle()
val fooViewModel: FooViewModel = koinViewModel()

val topLevelRoutes = remember(fooIconVisible) {
    buildList {
        add(TopLevelRoute.Chat)
        if (fooIconVisible) add(TopLevelRoute.Foo)
        add(TopLevelRoute.Home)
    }
}
```

`remember(fooIconVisible)` rebuilds the list whenever the Settings switch flips.

### 8c — `openFoo` helper that branches on activation

```kotlin
val openFoo: () -> Unit = {
    if (fooPreferences.activated.value) {
        navController.navigate(Route.Foo) {
            popUpTo(Route.ChatList) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    } else {
        navController.navigate(Route.FooOnboarding)
    }
}
```

### 8d — Dispatch nav-bar clicks through `openFoo`

In the `NavigationBarItem.onClick` (and the matching `NavigationRailItem.onClick`
for wide-screen layouts):

```kotlin
onClick = {
    if (topLevelRoute is TopLevelRoute.Foo) openFoo()
    else navController.navigate(topLevelRoute.route) {
        popUpTo(Route.ChatList) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

### 8e — Register the three routes

Inside the existing `NavHost { … }`:

```kotlin
composable<Route.FooOnboarding> {
    if (isAuthenticated) {
        FooOnboardingScreen(
            viewModel = fooViewModel,
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
composable<Route.Foo> {
    if (isAuthenticated) FooScreen(onNavigateBack = { navController.popBackStack() })
}
composable<Route.FooSettings> {
    if (isAuthenticated) {
        FooSettingsScreen(
            viewModel = koinViewModel(),
            onBackClick = { navController.popBackStack() },
            onOpenFoo = openFoo,
        )
    }
}
```

### 8f — Collect onboarding events and translate to navigation

```kotlin
LaunchedEffect(Unit) {
    fooViewModel.events.collect { event ->
        when (event) {
            FooUiEvent.Activated -> {
                navController.popBackStack(Route.FooOnboarding, inclusive = true)
                navController.navigate(Route.Foo) {
                    popUpTo(Route.ChatList) { saveState = true }
                    launchSingleTop = true
                }
            }
            FooUiEvent.CloseOnboarding -> navController.popBackStack()
        }
    }
}
```

### 8g — Keep the bottom bar visible on the feature screen even when hidden

`AppNavHost.kt` uses a static helper so the nav bar still shows when you're *on* the
Foo screen even though the user has hidden the icon. Extend it:

```kotlin
private fun NavDestination?.isTopLevelRoute(): Boolean =
    this?.hasRoute(Route.ChatList::class) == true ||
    this?.hasRoute(Route.Home::class) == true ||
    this?.hasRoute(Route.Foo::class) == true    // add your route here
```

### 8h — Settings screen callback

Wire the new callback you added in Step 7 inside the `composable<Route.Settings>`
block:

```kotlin
onNavigateToFooSettings = { navController.navigate(Route.FooSettings) },
```

---

## Step 9 — Extend-permissions wiring

This is what the "Extend" button in the permission dialog actually does at runtime.

### 9a — `AppConfig.kt`

Declare the feature's `LabeledDrive` (get the alias/type UUIDs from the server team):

```kotlin
val fooLabeledDrive = LabeledDrive(
    drive = TargetDrive(
        alias = Uuid.parse("<server-provided-alias>"),
        type  = Uuid.parse("<server-provided-type>"),
    ),
    label = "Foo",
)
```

### 9b — Activation wiring (`FooViewModel.kt`)

When the user taps *Extend* in the permission dialog, call the single entry point on
`AuthConnectionCoordinator`. It persists to `DriveRegistry`, hot-mounts in
`DriveSyncManager`, and schedules a debounced WebSocket reconnect so real-time push
notifications begin within ~500ms:

```kotlin
FooUiAction.PermissionExtendClicked -> viewModelScope.launch {
    fooPreferences.setActivated(true)
    authConnectionCoordinator.mountDrive(fooLabeledDrive)
    _uiState.update { it.copy(showPermissionDialog = false) }
    _events.tryEmit(FooUiEvent.Activated)
}
```

Inject `AuthConnectionCoordinator` into `FooViewModel` and register it in `AppModule.kt`.

> **Activation is now hot.** `mountDrive()` coalesces bursts into a single WS reconnect,
> so activating two add-ons back-to-back still results in one close+reopen. For the
> symmetric removal path use `authConnectionCoordinator.unmountDrive(driveId)`.

---

## Step 10 — DI registration

Reference: `AppModule.kt`.

```kotlin
single { FooPreferences(get()) }

// AuthConnectionCoordinator is already wired to DriveRegistry and DriveSyncManager —
// no per-add-on changes needed here. authConnectionCoordinator.mountDrive(fooLabeledDrive)
// at activation time is the only registration step.

// ViewModels — inject AuthConnectionCoordinator if the onboarding flow needs to activate
// a drive mid-session (i.e. every onboarding flow).
viewModelOf(::FooViewModel)       // constructor: FooPreferences, AuthConnectionCoordinator
viewModelOf(::FooSettingsViewModel)
```

---

## Step 11 — Platform dependencies (only if biometric-gated)

- `gradle/libs.versions.toml` — `androidx-biometric = "1.1.0"` is already present.
- `homebase-common/build.gradle.kts` — `implementation(libs.androidx.biometric)` is
  already in the `androidMain.dependencies` block.
- iOS uses `platform.LocalAuthentication.*` — no new Gradle dep.
- JVM/desktop needs nothing; the actual returns `Unavailable`.

If you skip biometrics, don't add `FooBiometricAuth*` files, drop Step 6's gate,
and omit the biometrics switch from Settings.

---

## PR checklist

Copy this into your PR description and tick off each wiring point:

- [ ] `FooPreferences` with fresh stable UUIDs (new `0a0Nxx` namespace)
- [ ] Strings under `foo_*` prefix in `strings.xml`
- [ ] Three `Route.Foo* ` entries in `Routes.kt`
- [ ] `FooOnboardingScreen`, `FooScreen`, `FooSettingsScreen`
- [ ] `FooViewModel` + `FooUiState` + `FooSettingsViewModel` + `FooSettingsUiState`
- [ ] `TopLevelRoute.Foo`, reactive `topLevelRoutes`, `openFoo()` helper in
      `AppNavHost`
- [ ] Three `composable<Route.Foo…>` entries + event-collecting `LaunchedEffect`
- [ ] `isTopLevelRoute()` updated to include `Route.Foo`
- [ ] Settings row + `onNavigateToFooSettings` wired
- [ ] `fooLabeledDrive` constant in `AppConfig.kt` (do NOT add to
      `mandatorySyncDrives` — optional drives live in `DriveRegistry`)
- [ ] `AppModule`: `single { FooPreferences }`, two `viewModelOf` — no
      changes to `DriveSyncManager`, `DriveRegistry`, or `AuthConnectionCoordinator`
      bindings (they are generic and already wired)
- [ ] (Optional) `FooBiometricAuth` expect + three actuals
- [ ] `CLAUDE.md` UI checklist: Material 3 only, `stringResource`,
      `Icons.AutoMirrored.*` for directional icons, `collectAsStateWithLifecycle`,
      `start`/`end` padding, `contentDescription` on icons

---

## Known gotchas

- **Activation briefly drops the WebSocket.** `authConnectionCoordinator.mountDrive()`
  debounces by ~500ms and then close+reopens the WS so the new drive joins the
  subscription. The user sees the online indicator blink. Coalescing means two rapid
  activations produce one reconnect, not two, but expect *some* reconnect.
- **403 unmount is session-only.** If the server returns 403 for a drive, `DriveSyncManager`
  unmounts it automatically, clearing the sync indicator. The drive will be attempted again
  on the next startup. This path deliberately does NOT trigger a WS refresh — reconnecting
  would just re-subscribe and be rejected again. It also does NOT mutate the registry file
  on the Chat drive — propagating a permission-denied condition as a cross-device
  registry deletion would affect the user's other devices incorrectly. To permanently remove
  a drive from the registry (for the whole identity), call
  `authConnectionCoordinator.unmountDrive(driveId)` from a settings action.
- **Cross-device propagation latency.** A change on Device A is visible to Device B only
  after B's next Chat-drive sync cycle pulls the updated registry file. Expect a few seconds
  (or an app re-open) for an activation/deactivation to reflect on other devices. First-boot
  on a new device is similar: optional drives appear after the Chat drive has synced once.
- **Offline writes throw.** `DriveRegistry.addDrive` / `removeDrive` go directly through the
  HTTP upload path, not the outbox. An offline activation surfaces a failure to the caller
  and the user must retry when online. Outbox integration is a planned follow-up.
- **Placeholder drive UUIDs.** Vault currently ships with a stub `f47ac10b-…`.
  Get real alias/type UUIDs from the server team before enabling the drive in
  production — otherwise the WebSocket will subscribe to a non-existent drive.
- **Dismiss is sticky.** Dismissing onboarding only hides the icon
  (`iconVisible = false`) — it never flips `activated`. The user must re-enable
  the icon via Settings → *Show Foo icon in bottom bar* to see the onboarding
  flow again.
- **Koin binds by class name.** `viewModelOf(::FooViewModel)` breaks silently if
  you rename the VM without updating `AppModule.kt`.
- **Fresh UUID namespace per add-on.** Reusing Vault's `0a01xx` range will corrupt
  user preferences across both features.
