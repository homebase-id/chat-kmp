package id.homebase.core.ui.screens.location

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.resources.MR
import id.homebase.resources.location_consent_agree
import id.homebase.resources.location_consent_decline
import id.homebase.resources.location_consent_text
import id.homebase.resources.location_consent_title
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.core.util.getUriHandler
import id.homebase.resources.location_history_title
import id.homebase.resources.location_label
import id.homebase.resources.location_menu_dashboard
import id.homebase.resources.location_menu_find_device
import id.homebase.resources.location_menu_more
import id.homebase.resources.location_menu_setup
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    viewModel: LocationViewModel,
    onNavigateToHistory: () -> Unit,
    onNavigateToFindDevice: (Uuid?) -> Unit,
    onNavigateToLiveMap: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionsManager = createPermissionsManager { type, status, isPermanentlyDenied ->
        when (type) {
            PermissionType.LOCATION ->
                viewModel.updateWhileInUseStatus(
                    granted = status == PermissionStatus.GRANTED,
                    permanentlyDenied = isPermanentlyDenied,
                )

            PermissionType.LOCATION_ALWAYS ->
                viewModel.updateAlwaysStatus(
                    granted = status == PermissionStatus.GRANTED,
                    permanentlyDenied = isPermanentlyDenied,
                )

            else -> {}
        }
    }

    suspend fun recheckPermissions() {
        // Re-query both rather than trusting per-key callbacks: the LOCATION
        // request maps two manifest permissions (fine + coarse) to one type,
        // and iOS may not call back at all on a silently kept status.
        viewModel.updateWhileInUseStatus(
            granted = permissionsManager.isPermissionGranted(PermissionType.LOCATION),
            permanentlyDenied = false,
        )
        viewModel.updateAlwaysStatus(
            granted = permissionsManager.isPermissionGranted(PermissionType.LOCATION_ALWAYS),
            permanentlyDenied = false,
        )
    }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        recheckPermissions()
    }

    // Best-effort activity/motion permission for the pedometer: requested once,
    // after location is granted and the disclosure (which covers steps) was
    // accepted. Denial is ignored — location tracking and the rest of the
    // feature continue, steps just stay null.
    var activityRequested by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.whileInUseGranted, uiState.disclosureAccepted) {
        if (uiState.whileInUseGranted && uiState.disclosureAccepted && !activityRequested) {
            activityRequested = true
            if (!permissionsManager.isPermissionGranted(PermissionType.ACTIVITY)) {
                permissionsManager.askPermission(PermissionType.ACTIVITY)
            }
        }
    }

    // Returning from the system settings screen must refresh both permission rows.
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeTick) {
        if (resumeTick > 0) {
            viewModel.refresh()
            recheckPermissions()
        }
    }

    // Google Play prominent disclosure: the consent dialog must be accepted
    // before the first location permission request or tracking enable. Holds
    // the intercepted action and replays it on Agree.
    var pendingConsentAction by remember { mutableStateOf<LocationUiAction?>(null) }

    // Emergency locate: the contact whose request panel is open (tap on an Active
    // "who you can locate" row). Closed on dismiss and on the VM's terminal events
    // (navigate-to-viewer / fetch-failed snackbar, both handled in AppNavHost).
    var pendingLocate by remember { mutableStateOf<ContactUiModel?>(null) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is LocationUiEvent.OpenPeerHistory || event is LocationUiEvent.LocateFetchFailed) {
                pendingLocate = null
            }
        }
    }

    fun execute(action: LocationUiAction) {
        when (action) {
            LocationUiAction.RequestWhileInUseClicked -> {
                // Arm auto-enable so the grant turns tracking on by itself.
                viewModel.armTrackingAutoEnableOnGrant()
                permissionsManager.askPermission(PermissionType.LOCATION)
            }

            LocationUiAction.RequestAlwaysClicked -> {
                viewModel.armTrackingAutoEnableOnGrant()
                // Latch the attempt so that, if this request is silently denied (Android 11+ never
                // re-shows the background dialog), the row routes to Settings instead of looping.
                viewModel.markAlwaysRequested()
                permissionsManager.askPermission(PermissionType.LOCATION_ALWAYS)
            }

            LocationUiAction.OpenSystemSettingsClicked ->
                permissionsManager.launchSettings()

            else -> viewModel.onAction(action)
        }
    }

    fun needsConsent(action: LocationUiAction): Boolean {
        if (uiState.disclosureAccepted) return false
        return action == LocationUiAction.RequestWhileInUseClicked ||
            action == LocationUiAction.RequestAlwaysClicked ||
            (action as? LocationUiAction.SetAllowLocationHistory)?.enabled == true
    }

    // Body switcher. The default is grant-gated: Setup until the add-on is activated
    // with both location grants, Dashboard after — independent of the "Track my location"
    // toggle (a granted user with tracking off still wants the dashboard, bug #822).
    // The three-dot menu lets the user override either way (null = follow default);
    // the override resets when the screen leaves the back stack.
    var dashboardOverride by remember { mutableStateOf<Boolean?>(null) }
    val defaultsToDashboard = isDashboard(
        activated = uiState.activated,
        trackerAvailable = uiState.trackingAvailable,
        // Both location grants required before defaulting to the dashboard — "while
        // using the app" alone keeps Setup as the default to finish the always grant.
        permissionsComplete = uiState.whileInUseGranted && uiState.alwaysGranted,
        setupOverride = false,
    )
    val showDashboard = dashboardOverride ?: defaultsToDashboard
    val previewProvider = koinInject<LocationPreviewProvider>()
    val uriHandler = getUriHandler()

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // No back arrow: Location is a top-level destination reached from the
            // bottom nav bar (which stays visible here), matching Vault/Moments.
            TopAppBar(
                title = { Text(stringResource(MR.string.location_label)) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(MR.string.location_menu_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.string.location_menu_find_device)) },
                            onClick = {
                                menuOpen = false
                                onNavigateToFindDevice(null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.string.location_history_title)) },
                            onClick = {
                                menuOpen = false
                                onNavigateToHistory()
                            },
                        )
                        // Dashboard is viewable once the drive is mounted — even
                        // before tracking permissions are complete — so always offer
                        // the toggle post-activation.
                        if (uiState.activated) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (showDashboard) MR.string.location_menu_setup
                                            else MR.string.location_menu_dashboard
                                        )
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    dashboardOverride = !showDashboard
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (showDashboard) {
            LocationDashboardContent(
                uiState = uiState,
                innerPadding = innerPadding,
                fetchTile = { z, x, y -> previewProvider.getTilePng(z, x, y) },
                onOpenHistory = onNavigateToHistory,
                onOpenLiveMap = onNavigateToLiveMap,
                onStopSharingWith = { execute(LocationUiAction.StopSharingWith(it)) },
                onStopSharingWithEveryone = { execute(LocationUiAction.StopSharingWithEveryone) },
                onOpenDevice = { onNavigateToFindDevice(it) },
                onOpenSetup = { dashboardOverride = false },
                onManageEmergencyAccess = {
                    uiState.emergencyManageUrl?.let { uriHandler.openUrl(it) }
                },
                onLocatableExpandedChange = { execute(LocationUiAction.SetLocatableExpanded(it)) },
                onLocateContact = { pendingLocate = it },
            )
        } else {
            LocationContent(
                uiState = uiState,
                innerPadding = innerPadding,
                onAction = { action ->
                    if (needsConsent(action)) pendingConsentAction = action else execute(action)
                },
            )
        }
    }

    pendingLocate?.let { contact ->
        val status = uiState.whoICanLocateStatus[contact.odinId.domainName]
        val newestMs = (status as? LocateVerifyStatus.Active)?.newestModifiedMs
        EmergencyLocateSheet(
            contact = contact,
            lastPointAgeMs = newestMs?.let {
                (Clock.System.now().toEpochMilliseconds() - it).coerceAtLeast(0)
            },
            submitting = uiState.locateSubmitInFlight,
            onDismiss = { pendingLocate = null },
            onConfirm = { explanation, windowHours, ambush ->
                viewModel.onAction(
                    LocationUiAction.ConfirmEmergencyLocate(
                        odinId = contact.odinId.domainName,
                        name = contact.name,
                        explanation = explanation,
                        windowHours = windowHours,
                        ambush = ambush,
                    )
                )
            },
        )
    }

    pendingConsentAction?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingConsentAction = null },
            title = { Text(stringResource(MR.string.location_consent_title)) },
            text = { Text(stringResource(MR.string.location_consent_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.acceptDisclosure()
                    pendingConsentAction = null
                    execute(pending)
                }) { Text(stringResource(MR.string.location_consent_agree)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingConsentAction = null }) {
                    Text(stringResource(MR.string.location_consent_decline))
                }
            },
        )
    }
}
