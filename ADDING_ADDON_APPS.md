# Adding an Add-on App

This guide shows how to scaffold a new "add-on app" (a top-level feature reachable
from the bottom navigation bar) following the pattern established by **Vault** on the
`add-vault-feature-scaffold` branch. Use it as a recipe — each step cites the real
Vault file you can diff against.

Canonical reference: `git show add-vault-feature-scaffold:<path>` for any of the
files mentioned below.

## What is an add-on app?

A self-contained feature that:

1. Shows up as a **toggleable icon** in the bottom navigation bar (or side rail).
2. Has an **onboarding screen** the first time the user taps it, with *Set it up* and
   *Dismiss* buttons.
3. When setup it flips a runtime **activation flag** via an "Extend Permissions" dialog — which in
   turn widens the set of drives the auth WebSocket subscribes to.
4. Has a **Settings sub-page** with a switch to hide its icon and (optionally) a
   biometrics switch.
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

Declare the feature's `LabeledDrive` and add an `activeSyncLabeledDrives(...)`
overload that appends it when requested:

```kotlin
// Get real alias / type UUIDs from the server team before shipping.
val fooLabeledDrive = LabeledDrive(
    drive = TargetDrive(
        alias = Uuid.parse("<server-provided-alias>"),
        type  = Uuid.parse("<server-provided-type>"),
    ),
    label = "Foo",
)

fun activeSyncLabeledDrives(
    includeVault: Boolean = false,
    includeFoo: Boolean = false,
): List<LabeledDrive> = buildList {
    addAll(syncLabeledDrives)
    if (includeVault) add(vaultLabeledDrive)
    if (includeFoo)   add(fooLabeledDrive)
}
```

### 9b — `AuthConnectionCoordinator.kt`

Inject `FooPreferences` and pass its activation flag into
`activeSyncLabeledDrives(...)` when opening the WebSocket:

```kotlin
class AuthConnectionCoordinator(
    ...
    private val vaultPreferences: VaultPreferences,
    private val fooPreferences: FooPreferences,
    ...
) {
    // inside connect():
    drives = activeSyncLabeledDrives(
        includeVault = vaultPreferences.activated.value,
        includeFoo   = fooPreferences.activated.value,
    ).map { it.drive }
}
```

> **Limitation today:** `AuthConnectionCoordinator` reads `activated.value` once at
> connect time. Flipping the toggle after login requires a reconnect to actually
> widen the drive subscription. If your add-on needs live re-subscription, observe
> the flow and trigger a reconnect — or file a TODO and document the workaround
> ("sign out / sign back in after enabling").

---

## Step 10 — DI registration

Reference: `AppModule.kt`.

```kotlin
single { FooPreferences(get()) }

// Update DriveSyncManager factory to include the new drive
single {
    val vaultPrefs = get<VaultPreferences>()
    val fooPrefs   = get<FooPreferences>()
    val drives = activeSyncLabeledDrives(
        includeVault = vaultPrefs.activated.value,
        includeFoo   = fooPrefs.activated.value,
    )
    DriveSyncManager(get(), get(), get(), get(), get(),
        drives.associate { it.drive.alias to it.label })
}

// Update AuthConnectionCoordinator factory to pass the new prefs
single {
    AuthConnectionCoordinator(
        ...
        vaultPreferences = get(),
        fooPreferences = get(),
        onPostAuthenticated = { /* … */ }
    )
}

// ViewModels
viewModelOf(::FooViewModel)
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
- [ ] `fooLabeledDrive` + `activeSyncLabeledDrives(includeFoo=…)` in `AppConfig`
- [ ] `AuthConnectionCoordinator` receives `FooPreferences` and uses it
- [ ] `AppModule`: `single { FooPreferences }`, two `viewModelOf`, updated
      `DriveSyncManager` + `AuthConnectionCoordinator` factories
- [ ] (Optional) `FooBiometricAuth` expect + three actuals
- [ ] `CLAUDE.md` UI checklist: Material 3 only, `stringResource`,
      `Icons.AutoMirrored.*` for directional icons, `collectAsStateWithLifecycle`,
      `start`/`end` padding, `contentDescription` on icons

---

## Known gotchas

- **Placeholder drive UUIDs.** Vault currently ships with a stub `f47ac10b-…`.
  Get real alias/type UUIDs from the server team before enabling the drive in
  production — otherwise the WebSocket will subscribe to a non-existent drive.
- **Activation does not hot-reload the WebSocket.** `AuthConnectionCoordinator`
  snapshots `activated.value` at connect time. See Step 9b.
- **Dismiss is sticky.** Dismissing onboarding only hides the icon
  (`iconVisible = false`) — it never flips `activated`. The user must re-enable
  the icon via Settings → *Show Foo icon in bottom bar* to see the onboarding
  flow again.
- **Koin binds by class name.** `viewModelOf(::FooViewModel)` breaks silently if
  you rename the VM without updating `AppModule.kt`.
- **Fresh UUID namespace per add-on.** Reusing Vault's `0a01xx` range will corrupt
  user preferences across both features.
