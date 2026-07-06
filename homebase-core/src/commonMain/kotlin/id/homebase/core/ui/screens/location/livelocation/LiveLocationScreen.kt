package id.homebase.core.ui.screens.location.livelocation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.core.ui.screens.location.MapsOffState
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.core.permissions.isLocationPermissionGranted
import id.homebase.resources.MR
import id.homebase.resources.live_location_empty
import id.homebase.resources.live_location_enable_gps_confirm
import id.homebase.resources.live_location_enable_gps_dismiss
import id.homebase.resources.live_location_enable_gps_text
import id.homebase.resources.live_location_enable_gps_title
import id.homebase.resources.live_location_title
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLocationScreen(
    viewModel: LiveLocationViewModel,
    onNavigateBack: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previewProvider = koinInject<LocationPreviewProvider>()

    // Your own dot needs location permission (the map's transient GPS hold arms the tracker only when
    // permitted — history is irrelevant). On each open, if permission is missing, prompt for it; on
    // grant, re-arm GPS so the dot appears without leaving the map or enabling history (#841).
    var permanentlyDenied by remember { mutableStateOf(false) }
    var showEnableGpsDialog by remember { mutableStateOf(false) }
    val permissionsManager = createPermissionsManager { type, status, deniedForever ->
        if (type != PermissionType.LOCATION) return@createPermissionsManager
        if (status == PermissionStatus.GRANTED) {
            showEnableGpsDialog = false
            viewModel.onPermissionGranted()
        } else {
            permanentlyDenied = deniedForever
        }
    }

    // Prompt on entry when un-permitted. Plain remember (not saveable) so a fresh open re-prompts.
    LaunchedEffect(Unit) {
        if (!isLocationPermissionGranted()) showEnableGpsDialog = true
    }
    // Returning from system Settings: if permission was granted there, clear the prompt and arm GPS.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && isLocationPermissionGranted()) {
                showEnableGpsDialog = false
                viewModel.onPermissionGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showEnableGpsDialog) {
        AlertDialog(
            onDismissRequest = { showEnableGpsDialog = false },
            title = { Text(stringResource(MR.string.live_location_enable_gps_title)) },
            text = { Text(stringResource(MR.string.live_location_enable_gps_text)) },
            confirmButton = {
                TextButton(onClick = {
                    // Once permanently denied, the OS won't re-prompt — send the user to Settings.
                    if (permanentlyDenied) permissionsManager.launchSettings()
                    else permissionsManager.askPermission(PermissionType.LOCATION)
                }) { Text(stringResource(MR.string.live_location_enable_gps_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEnableGpsDialog = false }) {
                    Text(stringResource(MR.string.live_location_enable_gps_dismiss))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.live_location_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            when {
                // Maps off → a tile-less canvas is just blank; show a tappable "turn on maps"
                // state instead (#811). Takes precedence over the markers check.
                !uiState.showMapTiles -> MapsOffState(
                    onOpenSetup = onOpenSetup,
                    modifier = Modifier.align(Alignment.Center),
                )

                uiState.markers.isEmpty() -> Text(
                    text = stringResource(MR.string.live_location_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

                else -> LiveLocationMap(
                    markers = uiState.markers,
                    showMapTiles = uiState.showMapTiles,
                    fetchTile = { z, x, y -> previewProvider.getTilePng(z, x, y) },
                )
            }
        }
    }
}
