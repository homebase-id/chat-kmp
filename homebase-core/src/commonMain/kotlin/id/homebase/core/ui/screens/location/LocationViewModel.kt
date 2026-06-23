package id.homebase.core.ui.screens.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.toContactUiModel
import id.homebase.chat.services.livelocation.LiveLocationShareService
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.contactbook.emergencyContacts
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.LocationTracker
import id.homebase.core.location.tracking.LocationTrackingCoordinator
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.ui.screens.location.devices.LocationDeviceDirectory
import id.homebase.core.ui.screens.location.history.localDayStart
import id.homebase.core.ui.screens.location.history.shiftDay
import id.homebase.core.ui.screens.location.livelocation.LIVE_STALE_MS
import id.homebase.core.ui.screens.location.livelocation.LiveLocationReceiveStore
import id.homebase.core.util.initials
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Well-known GUID (N-format) of the circle whose members may see this identity's location
 * in an emergency. Matching by id rather than name survives a rename; the owner-console
 * "manage" deep link uses the same id.
 */
private const val EMERGENCY_CIRCLE_ID = "8b5383a5927246f8a666f4f3fcb7392b"

class LocationViewModel(
    private val locationPreferences: LocationPreferences,
    private val locationPermissionViewModel: ExtendPermissionViewModel,
    private val optionalDriveActivation: OptionalDriveActivation,
    private val trackingCoordinator: LocationTrackingCoordinator,
    private val pointStore: LocationPointStore,
    private val uploaderService: LocationTrackUploaderService,
    private val deviceDirectory: LocationDeviceDirectory,
    private val contactRepository: ContactRepository,
    private val credentialsManager: CredentialsManager,
    private val receiveStore: LiveLocationReceiveStore,
    private val liveShareService: LiveLocationShareService,
    tracker: LocationTracker,
) : ViewModel() {

    /**
     * Whether Location is activated, derived from the mounted-drive state — the
     * cross-device source of truth (a registered drive is mounted by
     * AuthConnectionCoordinator's login pre-mount loop on every device). Replaces the
     * former device-local `activated` preference flag, which could disagree with the
     * registry across devices.
     */
    val isActivated: StateFlow<Boolean> =
        optionalDriveActivation.isActivatedFlow(locationLabeledDrive)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                optionalDriveActivation.isActivated(locationLabeledDrive),
            )

    private val _uiState = MutableStateFlow(
        LocationUiState(
            trackingAvailable = tracker.isAvailable,
            trackingEnabled = locationPreferences.trackingEnabled.value,
            activated = isActivated.value,
            iconVisible = locationPreferences.iconVisible.value,
            mapProvider = locationPreferences.mapProvider.value,
        )
    )
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LocationUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LocationUiEvent> = _events.asSharedFlow()

    val locationExtendPermissionViewModel: ExtendPermissionViewModel
        get() = locationPermissionViewModel

    // Synchronous one-shot guard for the auto-activate collector below — see MomentsViewModel.
    private var activationKicked = false

    init {
        // "Who can locate you" = the contacts you've marked as emergency contacts (the app-data
        // flag), resolved to display models. Reactive so marking/unmarking a contact updates the
        // dashboard live. (emergencyContacts is a cold Flow; collecting it here makes it hot for
        // the lifetime of this ViewModel.)
        viewModelScope.launch {
            // Exclude the logged-in identity: a self-contact can carry the flag (e.g. you marked
            // your own contact), but you are never your own emergency contact for "who can locate me".
            val self = runCatching { credentialsManager.getActiveDomain() }.getOrNull()
            contactRepository.emergencyContacts
                .map { list ->
                    list.mapNotNull { it.toContactUiModel() }
                        .filterNot { it.odinId == self }
                }
                .collect { members ->
                    _uiState.update {
                        it.copy(emergencyContacts = members, emergencyContactsLoaded = true)
                    }
                }
        }

        viewModelScope.launch {
            locationPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!activationKicked && !isActivated.value) {
                        activationKicked = true
                        // Auto-activate as soon as the drive is authorized — including the
                        // passive launch-time autoCheck grant. mountDrive registers the drive
                        // (persist=true) and mounts it; the drive appearing in driveStatuses
                        // flips isActivated true. It does not move the user.
                        val initiatedByUser = _uiState.value.setupInitiated
                        optionalDriveActivation.activate(locationLabeledDrive)
                        _uiState.update {
                            it.copy(isCheckingPermissions = false, setupInitiated = false)
                        }
                        if (initiatedByUser) {
                            _events.tryEmit(LocationUiEvent.Activated)
                        }
                    }
                }
        }

        // Resetting setupInitiated on every Dismissed transition (Cancel button, outside-tap,
        // or owner-console cancellation) ensures the next visit to onboarding starts clean.
        viewModelScope.launch {
            locationPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect {
                    _uiState.update {
                        it.copy(isCheckingPermissions = false, setupInitiated = false)
                    }
                }
        }

        viewModelScope.launch {
            locationPreferences.trackingEnabled.collect { enabled ->
                _uiState.update { it.copy(trackingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            locationPreferences.disclosureAccepted.collect { accepted ->
                _uiState.update { it.copy(disclosureAccepted = accepted) }
            }
        }
        viewModelScope.launch {
            isActivated.collect { activated ->
                _uiState.update { it.copy(activated = activated) }
            }
        }
        viewModelScope.launch {
            locationPreferences.mapProvider.collect { provider ->
                _uiState.update { it.copy(mapProvider = provider) }
            }
        }
        viewModelScope.launch {
            locationPreferences.iconVisible.collect { visible ->
                _uiState.update { it.copy(iconVisible = visible) }
            }
        }
        viewModelScope.launch {
            pointStore.lastPoint.collect { p ->
                _uiState.update {
                    it.copy(lastFixEpochMs = p?.t, lastFixLat = p?.lat, lastFixLon = p?.lon)
                }
                refreshCounts()
            }
        }
        viewModelScope.launch {
            uploaderService.lastFlushTime.collect { t ->
                _uiState.update { it.copy(lastFlushEpochMs = t) }
                refreshCounts()
            }
        }
        viewModelScope.launch {
            uploaderService.pendingCount.collect { n ->
                _uiState.update { it.copy(pendingUploadCount = n.toInt()) }
            }
        }
        // "Live location sharing" dashboard section: who I'm sharing with (deduped, longest until),
        // who's sharing with me (with age), and whether to show the section/map-link at all. Recomputed
        // on the 30 s ticker so the live entries prune and ages/until stay fresh without a second timer.
        viewModelScope.launch {
            combine(
                liveShareService.recipients,
                receiveStore.positions,
                liveTicker(),
            ) { roster, positions, _ ->
                val now = Clock.System.now().toEpochMilliseconds()

                // Outgoing: one row per identity, longest end-time across overlapping shares.
                val outgoing = roster
                    .filter { it.endTimeMs > now }
                    .groupBy { it.odinId }
                    .map { (id, entries) ->
                        val contact = resolveContact(OdinId(id))
                        OutgoingShareRow(
                            odinId = id,
                            name = contact?.name?.ifEmpty { null } ?: id,
                            avatarInitials = contact?.avatarInitials?.ifEmpty { null } ?: id.initials(),
                            avatarUrl = contact?.avatarUrl?.ifEmpty { null },
                            untilMs = entries.maxOf { it.endTimeMs },
                        )
                    }
                    .sortedBy { it.name.lowercase() }

                // Incoming: people whose last fix is still fresh, with its age.
                val incoming = positions.values
                    .filter { now - it.receivedAtMs <= LIVE_STALE_MS }
                    .map { lp ->
                        val id = lp.senderOdinId.domainName
                        val contact = resolveContact(lp.senderOdinId)
                        IncomingShareRow(
                            odinId = id,
                            name = contact?.name?.ifEmpty { null } ?: id,
                            avatarInitials = contact?.avatarInitials?.ifEmpty { null } ?: id.initials(),
                            avatarUrl = contact?.avatarUrl?.ifEmpty { null },
                            ageMs = now - lp.receivedAtMs,
                        )
                    }
                    .sortedBy { it.name.lowercase() }

                Triple(outgoing.isNotEmpty() || incoming.isNotEmpty(), outgoing, incoming)
            }.collect { (visible, outgoing, incoming) ->
                _uiState.update {
                    it.copy(
                        liveSharingVisible = visible,
                        outgoingShares = outgoing,
                        incomingShares = incoming,
                    )
                }
            }
        }
    }

    /**
     * Resolve a peer odinId to its display model via the contact repository, or null when the
     * identity isn't a saved contact (the share rows fall back to the raw odinId / initials).
     * Matching is by [OdinId] equality (normalized hash), mirroring ContactService's by-id map.
     */
    private fun resolveContact(odinId: OdinId): ContactUiModel? =
        contactRepository.contacts.value
            .firstOrNull { it.content.odinId?.let(::OdinId) == odinId }
            ?.toContactUiModel()

    private fun liveTicker() = flow {
        while (true) {
            emit(Unit)
            delay(30_000L)
        }
    }

    fun onAction(action: LocationUiAction) {
        when (action) {
            LocationUiAction.SetupClicked -> viewModelScope.launch {
                // Already-granted paths must complete synchronously: the
                // permissionsGranted StateFlow is already `true` on a re-login
                // whose drive grant survived, so the auto-activate collector in
                // init (filter { it }) will never fire again — waiting for it
                // leaves Setup visibly doing nothing (observed on emulator:
                // four taps, dialog stuck at Idle, no navigation).
                when {
                    isActivated.value -> {
                        _events.tryEmit(LocationUiEvent.Activated)
                    }

                    locationPermissionViewModel.permissionsGranted.value -> {
                        optionalDriveActivation.activate(locationLabeledDrive)
                        _events.tryEmit(LocationUiEvent.Activated)
                    }

                    else -> {
                        _uiState.update {
                            it.copy(isCheckingPermissions = true, setupInitiated = true)
                        }
                        locationPermissionViewModel.recheckPermissions()
                    }
                }
            }

            LocationUiAction.DismissOnboardingClicked -> {
                viewModelScope.launch {
                    locationPreferences.setIconVisible(false)
                    _events.tryEmit(LocationUiEvent.CloseOnboarding)
                }
            }

            is LocationUiAction.SetTrackingEnabled -> {
                viewModelScope.launch {
                    trackingCoordinator.setTrackingEnabled(action.enabled)
                }
            }

            is LocationUiAction.SetIconVisible -> {
                viewModelScope.launch { locationPreferences.setIconVisible(action.visible) }
            }

            is LocationUiAction.SetMapProvider -> {
                viewModelScope.launch { locationPreferences.setMapProvider(action.provider) }
            }

            is LocationUiAction.StopSharingWith -> {
                viewModelScope.launch { liveShareService.stopAll(OdinId(action.odinId)) }
            }

            LocationUiAction.StopSharingWithEveryone -> {
                viewModelScope.launch { liveShareService.stopAll() }
            }

            // Permission requests are dispatched at the screen level (the
            // PermissionsManager is composition-scoped); the VM only receives
            // status updates via updatePermissionStatus.
            LocationUiAction.RequestWhileInUseClicked,
            LocationUiAction.RequestAlwaysClicked,
            LocationUiAction.OpenSystemSettingsClicked -> Unit
        }
    }

    /** Persist the prominent-disclosure consent (Agree in the dialog). */
    fun acceptDisclosure() {
        viewModelScope.launch { locationPreferences.setDisclosureAccepted(true) }
    }

    // One-shot: armed by the screen right before it fires a permission request,
    // consumed when that request resolves to GRANTED. Gating on this flag (rather
    // than "granted && !trackingEnabled") keeps a passive launch-time recheck —
    // which also flips granted false→true — from re-enabling tracking the user
    // deliberately turned off while keeping the permission.
    private var pendingTrackingAutoEnable = false

    fun armTrackingAutoEnableOnGrant() {
        pendingTrackingAutoEnable = true
    }

    fun updateWhileInUseStatus(granted: Boolean, permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(whileInUseGranted = granted, whileInUsePermanentlyDenied = permanentlyDenied)
        }
        maybeAutoEnableTracking(granted)
    }

    fun updateAlwaysStatus(granted: Boolean, permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(alwaysGranted = granted, alwaysPermanentlyDenied = permanentlyDenied)
        }
        maybeAutoEnableTracking(granted)
    }

    /** Flip tracking on the first time an explicitly-requested grant lands. */
    private fun maybeAutoEnableTracking(granted: Boolean) {
        if (!granted || !pendingTrackingAutoEnable) return
        val state = _uiState.value
        if (!state.trackingAvailable || state.trackingEnabled) return
        pendingTrackingAutoEnable = false
        viewModelScope.launch { trackingCoordinator.setTrackingEnabled(true) }
    }

    /** Called on screen entry / resume to refresh DB-backed status numbers. */
    fun refresh() {
        viewModelScope.launch {
            pointStore.refresh()
            refreshCounts()
        }
        loadDashboard()
    }

    /** Dashboard data: today's traces (map preview), the device list, and the
     *  members granted emergency location access. */
    fun loadDashboard() {
        viewModelScope.launch {
            val dayStart = localDayStart(Clock.System.now().toEpochMilliseconds())
            val traces = runCatching { deviceDirectory.loadDayTraces(dayStart, shiftDay(dayStart, 1)) }
                .getOrDefault(emptyList())
            val devices = runCatching { deviceDirectory.loadDevices() }
                .getOrDefault(emptyList())

            // The "who can locate you" list itself comes from the emergency-contact flag (collected
            // reactively in init). Here we only resolve the owner-console deep link for managing the
            // Emergency Location Access circle (the actual location-drive grant).
            val domain = runCatching { credentialsManager.getActiveCredentials()?.domain?.domainName }
                .getOrNull()
            val manageUrl = domain?.let { "https://$it/owner/circles/$EMERGENCY_CIRCLE_ID" }

            _uiState.update {
                it.copy(
                    todayTraces = traces,
                    devices = devices,
                    emergencyManageUrl = manageUrl,
                )
            }
        }
    }

    private suspend fun refreshCounts() {
        // "Points today" comes from the hour files (+ unflushed remainder), NOT
        // the upload buffer — the buffer drains on upload confirmation, so its
        // row count is the "waiting to upload" number, not the day's history.
        val today = uploaderService.countPointsToday()
        val pending = pointStore.countPendingUpload()
        _uiState.update {
            it.copy(pointsToday = today, pendingUploadCount = pending.toInt())
        }
    }
}
