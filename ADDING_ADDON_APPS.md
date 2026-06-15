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
  FooScreen.kt                   # coordinator: scaffold, pickers, dialogs
  FooContent.kt                  # list/grid body (loading, empty, populated states)
  FooViewModel.kt                # single VM: combines streams, dispatches actions
  FooUiState.kt                  # UiState + UiAction + UiEvent + FooError

  FooStream.kt                   # EventBus → incremental StateFlow (real-time data)
  FooService.kt                  # metadata CRUD via OutboxSync + OptimisticWriter
  FooUploaderService.kt          # upload/download/append orchestration (if file-backed)

  auth/
    FooBiometricGate.kt          # biometric session + privacy overlay (if gated)
  gallery/                       # (if media-heavy)
    FooGalleryScreen.kt          # scaffold + pager + top bar
    FooGalleryDetailSheet.kt     # editing sheet
    FooZoomableImage.kt          # pinch-zoom-pan composable
  components/
    FooLockedContent.kt          # lock screen (if biometric-gated)
    FooEmptyState.kt             # empty state prompt
    ...                          # feature-specific reusable composables
  model/
    FooEntry.kt                  # domain model + HomebaseFile mapper
    FooSection.kt                # grouping model (if applicable)
    FooFileContent.kt            # @Serializable JSON schemas for appData.content
  settings/
    FooSettingsScreen.kt
    FooSettingsViewModel.kt
    FooSettingsUiState.kt
  onboarding/
    FooOnboardingScreen.kt       # intro + Setup/Dismiss buttons
```

Class names are load-bearing: Koin's `viewModelOf(::FooViewModel)` binds by
constructor reference, so renaming the VM requires an `AppModule.kt` update.

---

## Mandatory vs Optional Drives

The sync engine distinguishes two categories of drives:

| Category | Constant / Source | Examples |
|---|---|---|
| **Mandatory** | `mandatorySyncDrives` in `AppConfig.kt` | Chat, Contacts |
| **Optional** | `DriveRegistry` (files on the Chat drive) | Feed, Vault, … |

**Mandatory drives** (`chatLabeledDrive`, `contactLabeledDrive`) are always mounted. They cannot
be removed and require no user action. These are the minimum set needed for the chat app to
function. (Profile data is loaded via the public `/pub/profile` HTTP endpoint, not via the
drive sync engine, so the profile drive is intentionally absent from the mandatory list.)

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

### Bootstrap on login

`AuthConnectionCoordinator.onAuthStateChanged(Authenticated)` calls
`DriveRegistry.bootstrap()` before opening the WebSocket. Bootstrap tries the local DB
first (cold boot of a returning user — free, offline-safe) and falls back to a single
`getFileHeaderByUid` HTTP call against the Chat drive if local is empty (fresh login,
or local DB wiped). The result is mounted into `DriveSyncManager` AND passed
explicitly to `connect()` and `start(initialBaseline=…)`, so the first WS connect
already subscribes to the full set and the observer's diff baseline matches.

Without bootstrap the fresh-login path would: connect WS with mandatory only → wait
for the first sync cycle to deliver the registry file → observer fires → debounced
WS reconnect with the full set. The targeted server fetch saves that round-trip.

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

    /**
     * Clear all in-memory state for a clean login. Called from
     * `onPostAuthenticated` in `AppModule.kt`. Re-reads boolean flags
     * from the DB (which returns defaults after a logout wipe) and
     * zeroes biometric session timestamps.
     */
    fun reset() {
        _activated.value = readBoolean(ACTIVATED_KEY, default = false)
        _iconVisible.value = readBoolean(ICON_VISIBLE_KEY, default = true)
        _biometricsEnabled.value = readBoolean(BIOMETRICS_KEY, default = true)
        // Clear any in-memory session state (biometric timestamps, flags, etc.)
    }

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        val bytes: ByteArray = runCatching {
            keyValue.selectByKey(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Bump the penultimate byte for each new add-on — see the ownership
        // table below for the next free 0a0Nxx slot.
        val ACTIVATED_KEY:   Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0N01")
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0N02")
        val BIOMETRICS_KEY:  Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0N03")
    }
}
```

**UUID namespacing.** These keys must be stable across releases, so never reuse an
existing range. Current owners:

| Range | Owner |
|---|---|
| `0000...0a01xx` | Vault |
| `0000...0a02xx` | Moments |
| `0000...0a03xx` | Location |
| `0000...0a04xx` | Lists |
| `0000...0a05xx` | **next free** — claim it here when you take it |

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

    var authorized by remember {
        mutableStateOf(
            !fooPreferences.biometricsEnabled.value || fooPreferences.isAuthSessionValid()
        )
    }
    var unlockAttempt by remember { mutableStateOf(0) }
    var isAuthenticating by remember { mutableStateOf(false) }

    LaunchedEffect(authorized, unlockAttempt) {
        if (authorized || isAuthenticating) return@LaunchedEffect
        isAuthenticating = true
        when (authenticateBiometric(title, subtitle)) {
            BiometricResult.Success, BiometricResult.Unavailable -> {
                fooPreferences.recordAuthSuccess()
                authorized = true
            }
            BiometricResult.Failure -> { /* stay on locked screen */ }
        }
        isAuthenticating = false
    }

    // When !authorized, show a locked screen with an "Unlock" button
    // that increments unlockAttempt to re-trigger biometrics.
    // When authorized, render the feature content.
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

## Step 6b — Data layer (three-service pattern)

Reference: `VaultStream.kt`, `VaultService.kt`, `VaultUploaderService.kt`,
`VaultViewModel.kt`. Chat equivalents: `ConversationStream`, `ConversationService`,
`ChatMessageSenderService`.

Any add-on backed by an encrypted drive should split its data layer into three
services. This pattern gives you real-time updates, optimistic UI, and testable
units with clear boundaries.

### The three services

| Service | Responsibility | Dependencies |
|---------|---------------|--------------|
| `FooStream` | Real-time data observation via EventBus; holds in-memory StateFlows; provides optimistic mutation methods | `DatabaseManager`, `CredentialsManager`, `EventBus`, `CoroutineScope` |
| `FooService` | Metadata CRUD (create/rename/delete/reorder items); enqueues to outbox with optimistic DB writes | `OutboxSync`, `OptimisticWriter` |
| `FooUploaderService` | File upload/download/append; encryption + thumbnails; depends on `FooService` for metadata updates that accompany payload changes | `OutboxSync`, `OptimisticWriter`, `PayloadBundleEncryptionService`, `FileOperationsProvider`, `DriveFileProvider`, `LocalAttachmentContextStore`, `FooService` |

### FooStream — real-time observation

Cold-loads all data from the local DB on init, then observes `EventBus` for
incremental updates. Exposes two `StateFlow`s that the ViewModel combines:

```kotlin
class FooStream(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = fooLabeledDrive.drive.alias

    private val _items = MutableStateFlow<List<FooItem>>(emptyList())
    val items: StateFlow<List<FooItem>> = _items.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        scope.launch { observeEvents() }
    }

    /**
     * Load data from the local DB. Called from `onPostAuthenticated` in
     * `AppModule.kt` — never from `init`, because the singleton survives
     * logout and `init` only runs once per app process.
     */
    fun start() {
        scope.launch { loadAll() }
    }

    /**
     * Clear all in-memory state so a subsequent [start] loads cleanly
     * for a different identity. Called from `onPostAuthenticated` before
     * [start]. Must clear: StateFlows, resurrection-prevention sets,
     * pending-operation tracking, and any other session-scoped state.
     */
    fun reset() {
        _items.value = emptyList()
        _isLoaded.value = false
        // Clear deletion-tracking sets, pending-delete maps, etc.
    }

    suspend fun loadAll() { /* QueryBatch from local DB → emit to StateFlows */ }

    private suspend fun observeEvents() {
        eventBus.events.collect { event ->
            when (event) {
                // Incremental: merge new/updated files into in-memory state.
                // BatchReceived is a DataEvent (WS push + OptimisticWriter).
                // DriveSync itself is silent — see DriveEvent.Stopped below.
                is BackendEvent.DataEvent.BatchReceived ->
                    if (event.driveId == driveId) processBatch(event.batchData)
                // Full reload at end of a DriveSync round, gated on totalCount > 0
                // (a no-op reconnect catch-up adds nothing to the local index).
                // Do NOT gate on result == Success: DriveSync writes each batch
                // before the next starts, so Stopped(Aborted, totalCount > 0)
                // still means real rows landed — and on PermissionDenied there
                // is no next round, so waiting for a Success would hide them.
                is BackendEvent.DriveEvent.Stopped ->
                    if (event.driveId == driveId && event.totalCount > 0) loadAll()
                // Full reload: outbox confirmed, DB has authoritative state
                is BackendEvent.OutboxEvent.ItemCompleted ->
                    if (event.driveId == driveId) loadAll()
                is BackendEvent.OutboxEvent.ItemFailed ->
                    if (event.driveId == driveId) loadAll()
                else -> {}
            }
        }
    }

    // Optimistic mutations — called by ViewModel after service enqueue succeeds
    fun insertOptimistic(item: FooItem) { _items.update { it + item } }
    fun updateOptimistic(item: FooItem) { /* replace by uniqueId */ }
    fun remove(uniqueId: Uuid) { _items.update { it.filter { i -> i.uniqueId != uniqueId } } }
}
```

**Key design decisions:**

- **Incremental on BatchReceived, full reload on outbox events.** Batch events
  carry the actual `HomebaseFile` objects so you can merge them in-memory. Outbox
  completion/failure events don't carry data — reload from DB to get confirmed state.
- **Stream owns no business logic.** It only holds data and provides mutation methods.
  The ViewModel decides *when* to call them.
- **CoroutineScope comes from Koin.** `ApiModule` registers a
  `single<CoroutineScope>` with `SupervisorJob() + Dispatchers.Default`. Use
  `singleOf(::FooStream)` — Koin auto-resolves the scope. Don't create a separate one.

### FooService — metadata CRUD

Handles all non-file mutations. No encryption, no file I/O.

```kotlin
class FooService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
) {
    suspend fun createItem(id: Uuid, content: FooContent, keyHeader: KeyHeader): Boolean {
        val metadata = buildMetadata(id, content)
        val enqueued = outboxSync.tryEnqueue(UploadFileRequest(...))
        if (enqueued) {
            optimisticWriter.writeNewFile(driveId, keyHeader, metadata, 0, FileSystemType.Standard)
        }
        return enqueued
    }

    suspend fun deleteItem(uniqueId: Uuid, fileId: Uuid): Boolean { /* DeleteLocalFilesByFileIdRequest */ }
    suspend fun updateMetadata(...): Boolean { /* UpdateFileByUniqueIdRequest */ }
}
```

### FooUploaderService — file operations

Only needed if your add-on handles file uploads (images, documents, etc.). Depends
on `FooService` for metadata updates that accompany payload changes (e.g., append
pages updates the file's metadata + adds new payloads in one request).

### ViewModel — combine streams into UI state

The ViewModel combines Stream flows via `combine().stateIn()`. It never holds
duplicate state — the Stream is the single source of truth for data.

```kotlin
class FooViewModel(
    private val fooStream: FooStream,
    private val fooService: FooService,
    private val fooUploaderService: FooUploaderService,
    // ... other deps
) : ViewModel() {

    private val _overlayState = MutableStateFlow<FooOverlay?>(null)

    val uiState: StateFlow<FooUiState> = combine(
        fooStream.items,
        fooStream.isLoaded,
        _overlayState,
    ) { items, isLoaded, overlay ->
        FooUiState(items = items, isLoading = !isLoaded, overlay = overlay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FooUiState())

    fun onAction(action: FooUiAction) { /* dispatch to service layer */ }
}
```

### Two layers of optimistic updates

There are two independent mechanisms for making the UI feel instant:

| Layer | Where | Speed | Survives loadAll()? |
|-------|-------|-------|---------------------|
| **OptimisticWriter** (Layer 1) | Writes to local SQLite DB | Visible on next `loadAll()` (outbox completion) | Yes — it IS the DB |
| **Stream mutations** (Layer 2) | Updates in-memory StateFlow | Instant — UI recomposes immediately | No — `loadAll()` overwrites with DB state |

**Layer 1** happens automatically inside `FooService.enqueueFileContentUpdate()`.
**Layer 2** must be explicitly triggered by the ViewModel after a successful enqueue.

Use both layers for operations where the user expects instant feedback:

```kotlin
// In ViewModel — after service enqueue succeeds, update the stream
private fun handleRename(item: FooItem, newName: String) {
    viewModelScope.launch {
        val success = fooService.rename(item, newName)
        if (success) {
            fooStream.updateOptimistic(item.copy(name = newName))
        } else {
            _events.tryEmit(FooUiEvent.Error(FooError.RenameFailed))
        }
    }
}
```

For operations with placeholder data (e.g., appending pages where the real
payload descriptors aren't available yet), insert placeholders into the Stream
and revert on failure:

```kotlin
private fun handleAppend(item: FooItem, newFiles: List<PlatformFile>) {
    viewModelScope.launch {
        // Optimistic: add placeholders so count updates immediately
        val placeholders = buildPlaceholders(item, newFiles)
        val optimistic = item.copy(pages = item.pages + placeholders)
        fooStream.updateOptimistic(optimistic)

        val success = fooUploaderService.append(item, newFiles)
        if (!success) {
            fooStream.updateOptimistic(item) // revert
            _events.tryEmit(FooUiEvent.Error(FooError.AppendFailed))
        }
        // On success: outbox completion fires loadAll(), replacing placeholders
        // with real data from the DB
    }
}
```

### Model files

Define your domain models in a `model/` sub-package. Each model has:
- An `@Immutable data class` with UI-relevant fields
- A `HomebaseFile.toFooItem()` extension mapper
- A `@Serializable` content class matching your `appData.content` JSON schema

```kotlin
// model/FooEntry.kt
@Immutable
data class FooEntry(
    val fileId: Uuid,
    val uniqueId: Uuid,
    val name: String,
    // ... fields the UI needs
)

fun HomebaseFile.toFooEntry(): FooEntry? {
    val content = /* deserialize appData.content */ ?: return null
    // Check isPendingSendTag for upload status detection
    return FooEntry(...)
}

// model/FooFileContent.kt
@Serializable
data class FooFileContent(val name: String, val label: String? = null)
const val FOO_FILE_TYPE = 5574  // reserve with the team
```

### DI registration

```kotlin
// AppModule.kt
singleOf(::FooStream)           // CoroutineScope auto-resolved from ApiModule
singleOf(::FooService)
singleOf(::FooUploaderService)  // only if file-backed

viewModel {
    FooViewModel(
        fooStream = get(),
        fooService = get(),
        fooUploaderService = get(),
        // ... other deps
    )
}
```

### File size guidelines

Aim for **under 300 lines per file**. When a file grows past that, look for
extraction opportunities:

- Screen coordinator > 400 lines → extract `FooContent.kt` (list body),
  `auth/FooBiometricGate.kt` (biometric logic)
- Gallery overlay > 300 lines → split into `gallery/FooGalleryScreen.kt` (scaffold),
  `gallery/FooGalleryDetailSheet.kt` (editing), `gallery/FooZoomableImage.kt` (gestures)
- Dialogs, bottom sheets, FABs → `components/` sub-package

The ViewModel is the natural exception — it holds all action dispatch logic and
tends toward 400-500 lines. This is consistent with the chat module
(`ConversationListViewModel` is 3,478 lines).

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
dismissed onboarding — but the Home screen shortcut (Step 7b) always remains
visible as a secondary entry point.

---

## Step 7b — Home screen shortcut

Reference: `HomeScreen.kt`, `AppNavHost.kt`.

The bottom-bar icon can be hidden by the user (via *Dismiss* / settings toggle),
so add a permanent shortcut on the **Home** tab. This ensures the feature is
always discoverable regardless of the icon-visibility preference.

### In `HomeScreen.kt`

Add an `onNavigateToFoo: () -> Unit` parameter and render a tappable tile above
the log buttons:

```kotlin
Surface(
    onClick = onNavigateToFoo,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 1.dp,
    modifier = Modifier.size(96.dp),
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.<FooIcon>,
            contentDescription = stringResource(MR.string.foo_label),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(MR.string.foo_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
```

### In `AppNavHost.kt`

Wire the callback in the `composable<Route.Home>` block:

```kotlin
HomeScreen(
    viewModel = koinViewModel(),
    onNavigateToFoo = openFoo,
    onNavigateToExamples = { navController.navigate(Route.Examples) },
)
```

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

### Logout/login cleanup — `onPostAuthenticated`

Koin singletons (`FooPreferences`, `FooStream`, `FooService`) live for the
entire app process. On logout the DB is wiped (`DROP TABLE`), but the
singleton's in-memory `StateFlow`s still hold the previous user's data. Without
an explicit reset, a second login shows stale data from the wrong identity.

Add cleanup to the existing `onPostAuthenticated` block in `AppModule.kt`:

```kotlin
onPostAuthenticated = {
    // ... existing ConversationStream / ContactService wiring ...

    get<FooPreferences>().reset()
    get<FooStream>().apply { reset(); start() }
}
```

`reset()` zeroes every `StateFlow` and clears session-scoped tracking sets
(deletion IDs, pending-operation maps). `start()` then reloads from the
now-fresh DB for the new identity. Order matters: **reset before start**.

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

**Shell (navigation, preferences, UI chrome):**

- [ ] `FooPreferences` with fresh stable UUIDs (new `0a0Nxx` namespace)
- [ ] Strings under `foo_*` prefix in `strings.xml`
- [ ] Three `Route.Foo*` entries in `Routes.kt`
- [ ] `FooOnboardingScreen`, `FooScreen`, `FooSettingsScreen`
- [ ] `FooViewModel` + `FooUiState` + `FooSettingsViewModel` + `FooSettingsUiState`
- [ ] `TopLevelRoute.Foo`, reactive `topLevelRoutes`, `openFoo()` helper in
      `AppNavHost`
- [ ] Three `composable<Route.Foo…>` entries + event-collecting `LaunchedEffect`
- [ ] `isTopLevelRoute()` updated to include `Route.Foo`
- [ ] Settings row + `onNavigateToFooSettings` wired
- [ ] Home screen shortcut tile wired via `onNavigateToFoo` (always visible)
- [ ] (Optional) `FooBiometricAuth` expect + three actuals
- [ ] (Optional) `auth/FooBiometricGate.kt` extracted from screen

**Data layer (three-service pattern):**

- [ ] `FooStream` — EventBus observation, incremental StateFlows, optimistic mutations
- [ ] `FooService` — metadata CRUD via `OutboxSync` + `OptimisticWriter`
- [ ] `FooUploaderService` — file operations (only if file-backed)
- [ ] `model/FooEntry.kt` — domain model + `HomebaseFile.toFooEntry()` mapper
- [ ] `model/FooFileContent.kt` — `@Serializable` content schemas + file type constants
- [ ] ViewModel uses `combine(stream.items, stream.isLoaded, ...).stateIn()`
- [ ] Optimistic Stream updates for user-visible mutations (create, rename, append)
- [ ] Optimistic revert on failure for placeholder-based operations (append pages)

**Drive + DI:**

- [ ] `fooLabeledDrive` constant in `AppConfig.kt` (do NOT add to
      `mandatorySyncDrives` — optional drives live in `DriveRegistry`)
- [ ] `AppModule`: `singleOf(::FooStream)`, `singleOf(::FooService)`,
      `singleOf(::FooUploaderService)`, `viewModel { FooViewModel(...) }`,
      `viewModelOf(::FooSettingsViewModel)`
- [ ] `onPostAuthenticated`: `FooPreferences.reset()` + `FooStream.reset(); start()`
      (clears stale in-memory state across logout/login)
- [ ] Drive sync: `mountDrive()` called during activation; `driveRegistry.hasDrive()`
      checked before creating defaults (prevents AES key mismatch on re-login)

**Quality:**

- [ ] `CLAUDE.md` UI checklist: Material 3 only, `stringResource`,
      `Icons.AutoMirrored.*` for directional icons, `collectAsStateWithLifecycle`,
      `start`/`end` padding, `contentDescription` on icons
- [ ] Pending files: `isPendingSendTag` checked in file-to-UI mapper; local file
      paths stored in `LocalAttachmentContextStore` for instant preview during upload
- [ ] Typed error events: sealed `FooError` class, resolved to `stringResource()`
      in the screen composable (never hardcoded strings)
- [ ] No file exceeds 300 lines (ViewModel is the natural exception at 400-500)

---

## Known gotchas

- **`ExtendPermissionDialog` auto-prompts unless gated.** If your onboarding
  flow uses the shared `ExtendPermissionDialog` / `ExtendPermissionViewModel`
  pair (rather than an inline dialog), `ExtendPermissionViewModel.init` runs
  `checkPermissions()` eagerly. As soon as `FooViewModel` is constructed —
  which `AppNavHost` does at the top of composition via `koinViewModel()` —
  the qualified permission VM checks the server, sees the new drive isn't
  granted, and flips its state to `ShowDialog`. The dialog then appears the
  moment the user lands on the onboarding screen, before they tap *Set it up*.
  Add a `setupInitiated: Boolean` to `FooUiState`, flip it to `true` only on
  the *Set it up* click (and call `recheckPermissions()` there), and gate
  both the dialog render and the `ON_RESUME` recheck observer behind it.
- **Cancelling the dialog must reset `setupInitiated`.** After the user taps
  Cancel, `ExtendPermissionViewModel` stays at `Dismissed` until the next
  recheck. If `setupInitiated` is still `true`, navigating back to onboarding
  fires the `ON_RESUME` observer → `recheckPermissions()` → `ShowDialog` again,
  re-prompting a user who already said no. In `FooViewModel.init`, observe
  `fooPermissionViewModel.uiState.filter { it is ExtendPermissionUiState.Dismissed }`
  and reset `setupInitiated` to `false`. This catches all three cancel paths:
  the dialog's Cancel button, tap-outside dismissal, and the owner-console
  `PermissionsExtensionCanceled` event. Also reset it on successful
  activation so the flag never lingers.
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
- **Drive data won't sync without `mountDrive()`.** The add-on drive is NOT in
  `mandatorySyncDrives`. If `authConnectionCoordinator.mountDrive(fooLabeledDrive)`
  is never called, uploads work (outbox pushes directly to the server) but the drive
  is never pulled down. On reinstall or logout+login the data disappears from the UI
  because `DriveSyncManager` doesn't know about the drive. The fix: always call
  `mountDrive()` during activation — `DriveRegistry.addDrive()` is idempotent so
  calling it twice is safe.
- **`Preferences.activated` is device-local — use `DriveRegistry` as source of truth.**
  After logout+login, `activated` resets to `false`. If you blindly re-create default
  content (sections, folders, etc.) with new AES keys, the server rejects with
  "AES key must match." Before creating defaults, check
  `driveRegistry.hasDrive(fooDriveId)` — if the drive is already registered, the
  feature was set up before. Just restore the local `activated` flag and let sync
  pull existing data. Only create defaults when the drive is genuinely new.
- **Optimistic writes make files visible before payloads upload.**
  `OptimisticWriter.writeNewFile()` inserts file metadata into the local DB and emits
  `BatchReceived`. The UI sees the file immediately, but the payload bytes are still
  in the outbox queue. If the UI tries to load the image from the server, it fails.
  Fix: check `isPendingSendTag` in your file-to-UI mapper — files with that tag are
  still uploading. Use `LocalAttachmentContextStore` (keyed by `uniqueId` +
  `payloadKey`) to store the local file path at send time, and render the local file
  via `AsyncImage` in your card/list composable. This matches the chat pattern in
  `MediaItem.kt`.
- **Koin singletons survive logout — you MUST add `reset()`.** The DB is wiped on
  logout (`DROP TABLE`), but `FooStream`, `FooPreferences`, and `FooService` are Koin
  singletons whose `StateFlow`s and tracking sets persist for the lifetime of the app
  process. Without an explicit `reset()` call in `onPostAuthenticated`, logging in as a
  different user shows the previous user's data — a **user data isolation bug**. Every
  singleton that holds user-scoped in-memory state needs a `reset()` method wired into
  `onPostAuthenticated`. This includes resurrection-prevention sets (`deletedIds`),
  pending-operation maps, biometric session timestamps, and any `started` flags that
  gate idempotent `start()` calls.
- **Biometric cancel should show a locked screen, not navigate away.** Calling
  `onNavigateBack()` on `BiometricResult.Failure` silently ejects the user with no
  retry option. Instead, show a locked state UI (lock icon + "Unlock" button) and
  let the user re-trigger biometrics. Guard against rapid taps with an
  `isAuthenticating` flag.
