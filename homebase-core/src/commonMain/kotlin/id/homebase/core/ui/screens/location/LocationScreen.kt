package id.homebase.core.ui.screens.location

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.resources.MR
import id.homebase.resources.location_label
import id.homebase.resources.location_settings
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    viewModel: LocationViewModel,
    onNavigateToSettings: () -> Unit,
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

    Scaffold(
        topBar = {
            // No back arrow: Location is a top-level destination reached from the
            // bottom nav bar (which stays visible here), matching Vault/Moments.
            TopAppBar(
                title = { Text(stringResource(MR.string.location_label)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(MR.string.location_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LocationContent(
            uiState = uiState,
            innerPadding = innerPadding,
            onAction = { action ->
                when (action) {
                    LocationUiAction.RequestWhileInUseClicked ->
                        permissionsManager.askPermission(PermissionType.LOCATION)

                    LocationUiAction.RequestAlwaysClicked ->
                        permissionsManager.askPermission(PermissionType.LOCATION_ALWAYS)

                    LocationUiAction.OpenSystemSettingsClicked ->
                        permissionsManager.launchSettings()

                    else -> viewModel.onAction(action)
                }
            },
        )
    }
}
