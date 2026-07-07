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
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.livelocation.LiveLocationShareService
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.EMERGENCY_LOCATION_CIRCLE_ID
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.contactbook.locatableContacts
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.emergency.EmergencyLocateService
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val temporalDriveReadProvider: TemporalDriveReadProvider,
    private val credentialsManager: CredentialsManager,
    private val receiveStore: LiveLocationReceiveStore,
    private val liveShareService: LiveLocationShareService,
    private val conversationService: ConversationService,
    private val emergencyLocateService: EmergencyLocateService,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
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
                    .map { contactService.resolveByOdinId(OdinId(it)) }
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

    /** Runs the periodic locatable-verify loop while the section is expanded; null when collapsed. */
    private var locatableVerifyJob: Job? = null

    /**
     * Expanded: start the link-freshness loop — an immediate [verifyLocatablePass], then one every
     * [LOCATE_VERIFY_TTL_MS] so ages and states stay current while the section is open. Every
     * expand (re-)starts the loop; the per-row TTL inside the pass is what makes a quick re-expand
     * cheap. The expand-triggered pass shows spinners (visible feedback that a verify is running);
     * the periodic follow-ups are silent so the open list doesn't flash a spinner every minute.
     * Collapsed: cancel the loop (and any in-flight verifies with it) and sweep Loading
     * placeholders — a cancelled first verify must not leave a stuck Loading that blocks the next
     * expand forever (needsReverify(Loading) == false).
     *
     * A sibling collector re-runs a silent pass whenever the app comes online, so a row that
     * timed out to Unreachable while offline clears the moment the connection arrives instead of
     * waiting out the TTL (#998). Rows still inside their online-wait are in Loading →
     * needsReverify == false → the reconnect pass skips them while their own in-flight verify
     * wakes on the same transition.
     */
    private fun setLocatableExpanded(expanded: Boolean) {
        if (expanded) {
            if (locatableVerifyJob?.isActive == true) return
            locatableVerifyJob = viewModelScope.launch {
                launch {
                    authConnectionCoordinator.isOnline
                        .drop(1) // skip the replayed current value; react to transitions only
                        .filter { it }
                        .collect { verifyLocatablePass(showSpinner = false) }
                }
                var expandTriggered = true
                while (true) {
                    verifyLocatablePass(showSpinner = expandTriggered)
                    expandTriggered = false
                    delay(LOCATE_VERIFY_TTL_MS)
                }
            }
        } else {
            locatableVerifyJob?.cancel()
            locatableVerifyJob = null
            _uiState.update { s ->
                s.copy(
                    whoICanLocateStatus =
                        s.whoICanLocateStatus.filterValues { it != LocateVerifyStatus.Loading },
                )
            }
        }
    }

    /**
     * One preflight pass over the "who I can locate" entries: does the peer still grant us temporal
     * read access to their location drive, and how fresh is their newest data? Members are verified
     * in parallel child coroutines and the pass returns once all resolve, so loop iterations never
     * overlap a still-running verify. Members with a verify in flight or a result younger than
     * [LOCATE_VERIFY_TTL_MS] are skipped (#950). [showSpinner] (the expand-triggered pass) marks
     * each verifying row Loading so the user sees the verify happen; the periodic follow-up passes
     * pass false and keep the old value visible until the new result lands, so an open list never
     * flashes spinners every minute. A row with no prior result always spins. Each verify first
     * waits up to [LOCATE_VERIFY_ONLINE_WAIT_MS] for the app to be online (#998) — a no-op when it
     * already is — so an expand right after cold start spins instead of flashing broken clouds. A
     * network/parse failure after that is inconclusive → [LocateVerifyStatus.Unreachable]
     * (disconnected icon, retried after the TTL), never [Broken]; this mirrors
     * [EmergencyContactReconciler]'s leave-untouched rule.
     */
    private suspend fun verifyLocatablePass(showSpinner: Boolean) {
        val now = Clock.System.now().toEpochMilliseconds()
        coroutineScope {
            _uiState.value.whoICanLocate.forEach { member ->
                val key = member.odinId.domainName
                val current = _uiState.value.whoICanLocateStatus[key]
                if (!current.needsReverify(now)) return@forEach
                if (showSpinner || current == null) {
                    _uiState.update {
                        it.copy(whoICanLocateStatus = it.whoICanLocateStatus + (key to LocateVerifyStatus.Loading))
                    }
                }
                launch {
                    // Just-opened app: hold the row in Loading for up to
                    // LOCATE_VERIFY_ONLINE_WAIT_MS while the connection comes up, so a
                    // not-online-yet expand spins instead of flashing broken clouds (#998).
                    // Already online → first{} returns immediately; still offline after the
                    // wait → the verify throws and records Unreachable as before.
                    withTimeoutOrNull(LOCATE_VERIFY_ONLINE_WAIT_MS) {
                        authConnectionCoordinator.isOnline.first { it }
                    }
                    val status = try {
                        temporalDriveReadProvider.verifyTemporalAccess(member.odinId, locationDrive)
                    } catch (e: CancellationException) {
                        throw e // never record a cancelled verify as Unreachable
                    } catch (_: Exception) {
                        null
                    }
                    val verifiedAt = Clock.System.now().toEpochMilliseconds()
                    _uiState.update {
                        val next = when {
                            // Inconclusive (threw) → Unreachable; the TTL retries it next pass.
                            status == null ->
                                it.whoICanLocateStatus + (key to LocateVerifyStatus.Unreachable(verifiedAt))
                            // Gate on hasAccess alone (windowSeconds is not a reliable discriminator, #875).
                            !status.hasAccess ->
                                it.whoICanLocateStatus + (key to LocateVerifyStatus.Broken(verifiedAt))
                            // newestFileModified == 0 (ZeroTime) means no files yet → Active(null) = "no data".
                            else -> it.whoICanLocateStatus + (key to LocateVerifyStatus.Active(
                                newestModifiedMs = status.newestFileModified.milliseconds.takeIf { ms -> ms > 0 },
                                verifiedAtMs = verifiedAt,
                            ))
                        }
                        it.copy(whoICanLocateStatus = next)
                    }
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

            is LocationUiAction.SetLocatableExpanded -> setLocatableExpanded(action.expanded)

            is LocationUiAction.ConfirmEmergencyLocate -> confirmEmergencyLocate(action)

            // Permission requests are dispatched at the screen level (the
            // PermissionsManager is composition-scoped); the VM only receives
            // status updates via updatePermissionStatus.
            LocationUiAction.RequestWhileInUseClicked,
            LocationUiAction.RequestAlwaysClicked,
            LocationUiAction.OpenSystemSettingsClicked -> Unit
        }
    }

    /**
     * Emergency locate confirm: send the request notice FIRST (durable accountability — the
     * peer is told even if the subsequent read fails, deliberately), then fetch their history
     * over the temporal API into the memory-only store, then navigate to the peer viewer.
     */
    private fun confirmEmergencyLocate(action: LocationUiAction.ConfirmEmergencyLocate) {
        if (_uiState.value.locateSubmitInFlight) return
        val status = _uiState.value.whoICanLocateStatus[action.odinId]
        if (status !is LocateVerifyStatus.Active) return
        _uiState.update { it.copy(locateSubmitInFlight = true) }
        viewModelScope.launch {
            try {
                val peer = OdinId(action.odinId)
                conversationService.sendEmergencyLocateRequest(
                    recipient = peer,
                    explanation = action.explanation,
                    windowHours = action.windowHours,
                    ambush = action.ambush,
                )
                val result = emergencyLocateService.fetch(
                    peer = peer,
                    displayName = action.name,
                    windowMs = action.windowHours * 3_600_000L,
                )
                when (result) {
                    is EmergencyLocateService.FetchResult.Success ->
                        _events.tryEmit(LocationUiEvent.OpenPeerHistory(action.odinId, action.name))
                    else -> _events.tryEmit(LocationUiEvent.LocateFetchFailed)
                }
            } finally {
                _uiState.update { it.copy(locateSubmitInFlight = false) }
            }
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
