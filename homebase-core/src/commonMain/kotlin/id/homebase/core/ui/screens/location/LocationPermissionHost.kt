package id.homebase.core.ui.screens.location

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.resources.MR
import id.homebase.resources.location_consent_agree
import id.homebase.resources.location_consent_decline
import id.homebase.resources.location_consent_text
import id.homebase.resources.location_consent_title
import org.jetbrains.compose.resources.stringResource

/**
 * Owns the location permission plumbing every Location screen with a grant/toggle needs:
 * the platform PermissionsManager, the entry/resume recheck, the one-shot activity-permission
 * request, and the Google Play prominent-disclosure consent dialog. [content] receives a
 * dispatcher that routes permission actions to the manager and everything else to the VM,
 * intercepting consent-gated actions first.
 */
@Composable
fun LocationPermissionHost(
    viewModel: LocationViewModel,
    uiState: LocationUiState,
    onVisible: () -> Unit = { viewModel.refresh() },
    content: @Composable (dispatch: (LocationUiAction) -> Unit) -> Unit,
) {
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
        onVisible()
        recheckPermissions()
    }

    // Best-effort activity/motion permission for the pedometer: requested once,
    // after location is granted and the disclosure (which covers steps) was
    // accepted. Denial is ignored — steps just stay null.
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
            onVisible()
            recheckPermissions()
        }
    }

    // Google Play prominent disclosure: the consent dialog must be accepted before the first
    // location permission request or tracking enable. Holds the intercepted action and replays
    // it on Agree.
    var pendingConsentAction by remember { mutableStateOf<LocationUiAction?>(null) }

    fun execute(action: LocationUiAction) {
        when (action) {
            LocationUiAction.RequestWhileInUseClicked -> {
                viewModel.armTrackingAutoEnableOnGrant()
                permissionsManager.askPermission(PermissionType.LOCATION)
            }

            LocationUiAction.RequestAlwaysClicked -> {
                viewModel.armTrackingAutoEnableOnGrant()
                // Latch the attempt: Android 11+ never re-shows the background dialog, so the
                // Setup row routes to Settings instead of looping.
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

    content { action ->
        if (needsConsent(action)) pendingConsentAction = action else execute(action)
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
