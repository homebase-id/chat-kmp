package id.homebase.core.ui.screens.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.toContactUiModel
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.livelocation.LiveLocationShareService
import id.homebase.core.config.EMERGENCY_LOCATION_CIRCLE_ID
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.contactbook.EmergencyContactReconciler
import id.homebase.core.contactbook.locatableContacts
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

class LocationViewModel(
    private val locationPreferences: LocationPreferences,
    private val locationPermissionViewModel: ExtendPermissionViewModel,
    private val optionalDriveActivation: OptionalDriveActivation,
    private val trackingCoordinator: LocationTrackingCoordinator,
    private val pointStore: LocationPointStore,
    private val uploaderService: LocationTrackUploaderService,
    private val deviceDirectory: LocationDeviceDirectory,
    private val contactRepository: ContactRepository,
    private val connectionService: ConnectionService,
    private val contactService: ContactService,
    private val emergencyContactReconciler: EmergencyContactReconciler,
    private val temporalDriveReadProvider: TemporalDriveReadProvider,
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
            allowLocationHistory = locationPreferences.allowLocationHistory.value,
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

    // The location drive we preflight per "who I can locate" entry (same alias the reconciler uses).
    private val locationDrive = locationLabeledDrive.drive.alias

    init {
        // "Who can locate you" = the members of our emergency-location-access circle. We host this
        // circle on our OWN identity, so its membership is the source of truth — no app-data flag.
        // Reactive: ConnectionService refreshes circle membership on the ConnectionChanged websocket
        // event, so the dashboard updates live when the owner-console grants/revokes the circle.
        viewModelScope.launch {
            // Exclude the logged-in identity: you are never your own emergency contact.
            val self = runCatching { credentialsManager.getActiveDomain() }
                .getOrNull()?.domainName?.lowercase()
            connectionService.circles.collect { circleState ->
                val members = circleState.membersOf(EMERGENCY_LOCATION_CIRCLE_ID)
                    .asSequence()
                    .filterNot { it == self }
                    .mapNotNull { contactService.resolveByOdinId(OdinId(it)) }
                    .sortedBy { it.name.lowercase() }
                    .toList()
                _uiState.update {
                    it.copy(whoCanLocateMe = members, whoCanLocateMeLoaded = circleState.isLoaded)
                }
            }
        }

        // "Who you can locate" = the contacts carrying our `iCanLocate` app-data flag (set when they
        // designated us via their emergency circle). The flag is a reactive cache; step 8 reconciles
        // it against a temporal-access preflight. Reactive so a new designation appears live.
        viewModelScope.launch {
            val self = runCatching { credentialsManager.getActiveDomain() }
                .getOrNull()?.domainName?.lowercase()
            contactRepository.locatableContacts
                .map { list ->
                    list.mapNotNull { it.toContactUiModel() }
                        .filterNot { it.odinId.domainName.lowercase() == self }
                        .sortedBy { it.name.lowercase() }
                }
                .collect { members ->
                    _uiState.update {
                        it.copy(whoICanLocate = members, whoICanLocateLoaded = true)
                    }
                }
        }

        // On dashboard open, reconcile the iCanLocate cache against the authoritative temporal-access
        // grant so a lost revocation (stale flag) self-corrects. Best-effort, one-shot per open.
        viewModelScope.launch { runCatching { emergencyContactReconciler.reconcile() } }

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
            locationPreferences.allowLocationHistory.collect { enabled ->
                _uiState.update { it.copy(allowLocationHistory = enabled) }
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

    /**
     * Preflight each "who I can locate" entry: does the peer still grant us temporal read access to
     * their location drive, and how fresh is their newest data? Fired when the section is expanded.
     * Each member is verified in its own coroutine so the spinners resolve independently. Members
     * already resolved (or in flight) are skipped, so a re-expand is cheap. A network/parse failure
     * is inconclusive — we drop the key (row shows nothing) so a re-expand retries, rather than
     * falsely showing "broken"; this mirrors [EmergencyContactReconciler]'s leave-untouched rule.
     */
    fun verifyLocatableAccess() {
        _uiState.value.whoICanLocate.forEach { member ->
            val key = member.odinId.domainName
            when (_uiState.value.whoICanLocateStatus[key]) {
                LocateVerifyStatus.Loading,
                LocateVerifyStatus.Broken,
                is LocateVerifyStatus.Active -> return@forEach // resolved or in flight
                null -> Unit // (re)issue
            }
            _uiState.update {
                it.copy(whoICanLocateStatus = it.whoICanLocateStatus + (key to LocateVerifyStatus.Loading))
            }
            viewModelScope.launch {
                val status = runCatching {
                    temporalDriveReadProvider.verifyTemporalAccess(member.odinId, locationDrive)
                }.getOrNull()
                _uiState.update {
                    val next = when {
                        // Inconclusive (threw) → drop so the next expand retries.
                        status == null -> it.whoICanLocateStatus - key
                        // Gate on hasAccess alone (windowSeconds is not a reliable discriminator, #875).
                        !status.hasAccess -> it.whoICanLocateStatus + (key to LocateVerifyStatus.Broken)
                        // newestFileModified == 0 (ZeroTime) means no files yet → Active(null) = "no data".
                        else -> it.whoICanLocateStatus + (key to LocateVerifyStatus.Active(
                            status.newestFileModified.milliseconds.takeIf { ms -> ms > 0 }
                        ))
                    }
                    it.copy(whoICanLocateStatus = next)
                }
            }
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

            is LocationUiAction.SetAllowLocationHistory -> {
                viewModelScope.launch {
                    trackingCoordinator.setAllowLocationHistory(action.enabled)
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

            LocationUiAction.VerifyLocatable -> verifyLocatableAccess()

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

    /**
     * Latch that the user has tried the background ("always") grant. Set when the Grant button is
     * tapped — not when the result arrives — so a passive launch-time recheck (which also reports
     * "not granted") never pre-empts the first real attempt. Once latched, the Setup row routes to
     * Settings until the grant lands (see [LocationUiState.alwaysRequestAttempted]).
     */
    fun markAlwaysRequested() {
        _uiState.update { it.copy(alwaysRequestAttempted = true) }
    }

    fun updateWhileInUseStatus(granted: Boolean, permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(whileInUseGranted = granted, whileInUsePermanentlyDenied = permanentlyDenied)
        }
        maybeAutoEnableTracking(granted)
    }

    fun updateAlwaysStatus(granted: Boolean, permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(
                alwaysGranted = granted,
                alwaysPermanentlyDenied = permanentlyDenied,
                // A successful grant clears the attempt latch so a future revoke starts fresh.
                alwaysRequestAttempted = if (granted) false else it.alwaysRequestAttempted,
            )
        }
        maybeAutoEnableTracking(granted)
    }

    /** Flip tracking on the first time an explicitly-requested grant lands. */
    private fun maybeAutoEnableTracking(granted: Boolean) {
        if (!granted || !pendingTrackingAutoEnable) return
        val state = _uiState.value
        if (!state.trackingAvailable || state.allowLocationHistory) return
        pendingTrackingAutoEnable = false
        viewModelScope.launch { trackingCoordinator.setAllowLocationHistory(true) }
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
            val manageUrl = domain?.let { "https://$it/owner/circles/$EMERGENCY_LOCATION_CIRCLE_ID" }

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
