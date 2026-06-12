package id.homebase.core.ui.screens.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.LocationTracker
import id.homebase.core.location.tracking.LocationTrackingCoordinator
import id.homebase.core.ui.screens.location.devices.LocationDeviceDirectory
import id.homebase.core.ui.screens.location.history.localDayStart
import id.homebase.core.ui.screens.location.history.shiftDay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

class LocationViewModel(
    private val locationPreferences: LocationPreferences,
    private val locationPermissionViewModel: ExtendPermissionViewModel,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val trackingCoordinator: LocationTrackingCoordinator,
    private val pointStore: LocationPointStore,
    private val uploaderService: LocationTrackUploaderService,
    private val deviceDirectory: LocationDeviceDirectory,
    tracker: LocationTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LocationUiState(
            trackingAvailable = tracker.isAvailable,
            trackingEnabled = locationPreferences.trackingEnabled.value,
            activated = locationPreferences.activated.value,
            showMapTiles = locationPreferences.showMapTiles.value,
        )
    )
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LocationUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LocationUiEvent> = _events.asSharedFlow()

    val locationExtendPermissionViewModel: ExtendPermissionViewModel
        get() = locationPermissionViewModel

    init {
        viewModelScope.launch {
            locationPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!locationPreferences.activated.value) {
                        // Auto-activate as soon as the drive is authorized — including the
                        // passive launch-time autoCheck grant. Activation persists the flag
                        // and mounts the drive; it does not move the user.
                        val initiatedByUser = _uiState.value.setupInitiated
                        locationPreferences.setActivated(true)
                        authConnectionCoordinator.mountDrive(locationLabeledDrive)
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
            locationPreferences.activated.collect { activated ->
                _uiState.update { it.copy(activated = activated) }
            }
        }
        viewModelScope.launch {
            locationPreferences.showMapTiles.collect { show ->
                _uiState.update { it.copy(showMapTiles = show) }
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
                    locationPreferences.activated.value -> {
                        _events.tryEmit(LocationUiEvent.Activated)
                    }

                    locationPermissionViewModel.permissionsGranted.value -> {
                        locationPreferences.setActivated(true)
                        authConnectionCoordinator.mountDrive(locationLabeledDrive)
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

    fun updateWhileInUseStatus(granted: Boolean, permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(whileInUseGranted = granted, whileInUsePermanentlyDenied = permanentlyDenied)
        }
    }

    fun updateAlwaysStatus(granted: Boolean, permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(alwaysGranted = granted, alwaysPermanentlyDenied = permanentlyDenied)
        }
    }

    /** Called on screen entry / resume to refresh DB-backed status numbers. */
    fun refresh() {
        viewModelScope.launch {
            pointStore.refresh()
            refreshCounts()
        }
        loadDashboard()
    }

    /** Dashboard data: today's traces (map preview) + the device list. */
    fun loadDashboard() {
        viewModelScope.launch {
            val dayStart = localDayStart(Clock.System.now().toEpochMilliseconds())
            val traces = runCatching { deviceDirectory.loadDayTraces(dayStart, shiftDay(dayStart, 1)) }
                .getOrDefault(emptyList())
            val devices = runCatching { deviceDirectory.loadDevices() }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(todayTraces = traces, devices = devices) }
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
