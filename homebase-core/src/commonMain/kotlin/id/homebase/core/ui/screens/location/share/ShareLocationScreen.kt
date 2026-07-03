package id.homebase.core.ui.screens.location.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.core.location.LIVE_SHARE_DURATION_OPTIONS
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.core.permissions.isLocationPermissionGranted
import id.homebase.core.ui.screens.location.MapsOffState
import id.homebase.core.ui.screens.location.map.TiledMapView
import id.homebase.core.ui.screens.location.map.rememberMapCameraState
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.cd_location_pin
import id.homebase.resources.live_location_enable_gps_confirm
import id.homebase.resources.live_location_enable_gps_dismiss
import id.homebase.resources.live_location_enable_gps_text
import id.homebase.resources.live_location_enable_gps_title
import id.homebase.resources.live_share_duration_prompt
import id.homebase.resources.live_share_enable_location_body
import id.homebase.resources.live_share_enable_location_cta
import id.homebase.resources.live_share_enable_location_title
import id.homebase.resources.menu_back
import id.homebase.resources.share_location_comment_hint
import id.homebase.resources.share_location_live_banner
import id.homebase.resources.share_location_recenter_cd
import id.homebase.resources.share_location_resolving
import id.homebase.resources.share_location_send_cd
import id.homebase.resources.share_location_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Full-screen share-location screen (#966): pan the map under a fixed center pin (the address
 * follows), re-center on GPS from the upper-right button, optionally start a live share via the
 * bottom banner's duration menu, and send with an always-present comment row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLocationScreen(
    viewModel: ShareLocationViewModel,
    onNavigateBack: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previewProvider = koinInject<LocationPreviewProvider>()
    val snackbarHostState = remember { SnackbarHostState() }

    // Location permission prompt on entry — NON-blocking: denial still allows picking a point by
    // panning and sending a static pin; only the GPS conveniences (entry fix, re-center) and the
    // live share (re-gated by LiveShareReadiness) need it. Same lifecycle dance as the live map.
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
    LaunchedEffect(Unit) {
        if (!isLocationPermissionGranted()) showEnableGpsDialog = true
    }
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

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ShareLocationUiEvent.MessageSent -> onNavigateBack()
                is ShareLocationUiEvent.ShowError ->
                    snackbarHostState.showSnackbar(getString(event.res))
            }
        }
    }

    if (showEnableGpsDialog) {
        AlertDialog(
            onDismissRequest = { showEnableGpsDialog = false },
            title = { Text(stringResource(MR.string.live_location_enable_gps_title)) },
            text = { Text(stringResource(MR.string.live_location_enable_gps_text)) },
            confirmButton = {
                TextButton(onClick = {
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

    // Live share blocked: location add-on not set up / permission missing → setup CTA.
    if (uiState.showEnableLocationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissEnableLocationDialog() },
            title = { Text(stringResource(MR.string.live_share_enable_location_title)) },
            text = { Text(stringResource(MR.string.live_share_enable_location_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissEnableLocationDialog()
                    onOpenSetup()
                }) { Text(stringResource(MR.string.live_share_enable_location_cta)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissEnableLocationDialog() }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.share_location_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            if (!uiState.showMapTiles) {
                MapsOffState(
                    onOpenSetup = onOpenSetup,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val camera = rememberMapCameraState()

                TiledMapView(
                    bbox = uiState.initialBbox?.toDoubleArray(),
                    showMapTiles = true,
                    fetchTile = { z, x, y -> previewProvider.getTilePng(z, x, y) },
                    resetViewportOn = uiState.initialBboxKey,
                    cameraState = camera,
                )

                // Pin follows the viewport center; the VM debounces the geocode, so per-gesture
                // restarts of this effect are cheap.
                val center = camera.centerUnit
                LaunchedEffect(center) {
                    center?.let { (x, y) ->
                        viewModel.onMapCenterChanged(x, y, camera.isUserPositioned)
                    }
                }
                // One-shot GPS re-center handed back from the VM.
                LaunchedEffect(uiState.recenterTarget) {
                    uiState.recenterTarget?.let { target ->
                        camera.centerOn(target.unitX, target.unitY)
                        viewModel.recenterConsumed()
                    }
                }

                // Fixed center pin. Offset up half its size so the pin TIP marks the center.
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = stringResource(MR.string.cd_location_pin),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .offset(y = (-20).dp),
                )

                // Address chip — resolves as the user pans.
                val addressText = when {
                    uiState.isResolvingAddress -> stringResource(MR.string.share_location_resolving)
                    else -> uiState.address
                }
                if (addressText.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(start = 16.dp, top = 12.dp, end = 72.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 2.dp,
                    ) {
                        Text(
                            text = addressText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                // GPS re-center — upper-right corner.
                FilledTonalIconButton(
                    onClick = { viewModel.onRecenter() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                ) {
                    if (uiState.isAcquiringFix) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = stringResource(MR.string.share_location_recenter_cd),
                        )
                    }
                }
            }

            // Bottom controls: live-share banner + the always-present comment/send row.
            // imePadding lifts the bar above the keyboard while typing a comment.
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column {
                    var durationMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !uiState.isSending) { durationMenuExpanded = true }
                                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(MR.string.share_location_live_banner),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        DropdownMenu(
                            expanded = durationMenuExpanded,
                            onDismissRequest = { durationMenuExpanded = false },
                        ) {
                            Text(
                                text = stringResource(MR.string.live_share_duration_prompt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            HorizontalDivider()
                            LIVE_SHARE_DURATION_OPTIONS.forEach { (labelRes, durationMs) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    onClick = {
                                        durationMenuExpanded = false
                                        viewModel.startLiveShare(durationMs)
                                    },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = uiState.comment,
                            onValueChange = { viewModel.onCommentChanged(it) },
                            placeholder = { Text(stringResource(MR.string.share_location_comment_hint)) },
                            modifier = Modifier.weight(1f),
                            maxLines = 3,
                            shape = MaterialTheme.shapes.large,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        FilledIconButton(
                            onClick = { viewModel.sendStaticPin() },
                            enabled = uiState.pinLat != null && !uiState.isSending,
                        ) {
                            if (uiState.isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(MR.string.share_location_send_cd),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
