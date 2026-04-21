package id.homebase.core.ui.screens.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.settings_storage
import id.homebase.resources.storage_cache_coil_memory
import id.homebase.resources.storage_cache_payloads
import id.homebase.resources.storage_cache_profile_images
import id.homebase.resources.storage_cache_profiles
import id.homebase.resources.storage_cache_ram_badge
import id.homebase.resources.storage_cache_size_format
import id.homebase.resources.storage_cache_thumbnails
import id.homebase.resources.storage_cache_unknown
import id.homebase.resources.storage_caches_cleared
import id.homebase.resources.storage_caches_header
import id.homebase.resources.storage_caches_none
import id.homebase.resources.storage_clear_caches
import id.homebase.resources.storage_database
import id.homebase.resources.storage_drive_count_format
import id.homebase.resources.storage_drives_header
import id.homebase.resources.storage_drives_none
import id.homebase.resources.storage_other_header
import id.homebase.resources.storage_total_cache_format
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun StorageSettingsScreen(
    viewModel: StorageSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val cachesClearedMessage = stringResource(MR.string.storage_caches_cleared)

    LaunchedEffect(uiState.uiEvent) {
        when (uiState.uiEvent) {
            null -> {}
            StorageSettingsUiEvent.CachesCleared -> {
                viewModel.eventConsumed()
                snackbarHostState.showSnackbar(cachesClearedMessage)
            }
        }
    }

    StorageSettingsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsUi(
    uiState: StorageSettingsUiState,
    onAction: (StorageSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.settings_storage)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(
                title = stringResource(MR.string.storage_caches_header),
                trailing = {
                    Text(
                        text = stringResource(
                            MR.string.storage_total_cache_format,
                            formatBytes(uiState.totalCacheBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                if (uiState.caches.isEmpty() && uiState.coilMemoryCache == null) {
                    EmptyRow(text = stringResource(MR.string.storage_caches_none))
                } else {
                    Column {
                        uiState.caches.forEachIndexed { index, cache ->
                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            CacheRow(state = cache)
                        }
                        uiState.coilMemoryCache?.let { coil ->
                            if (uiState.caches.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            CacheRow(state = coil, isMemory = true)
                        }
                    }
                }
            }

            Button(
                onClick = { onAction(StorageSettingsUiAction.ClearCachesClicked) },
                enabled = !uiState.isClearing &&
                        (uiState.caches.isNotEmpty() || uiState.coilMemoryCache != null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isClearing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(MR.string.storage_clear_caches))
                }
            }

            SectionHeader(title = stringResource(MR.string.storage_drives_header))

            Card(modifier = Modifier.fillMaxWidth()) {
                if (uiState.drives.isEmpty()) {
                    EmptyRow(text = stringResource(MR.string.storage_drives_none))
                } else {
                    Column {
                        uiState.drives.forEachIndexed { index, drive ->
                            if (index > 0) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            DriveRow(state = drive)
                        }
                    }
                }
            }

            SectionHeader(title = stringResource(MR.string.storage_other_header))

            Card(modifier = Modifier.fillMaxWidth()) {
                SimpleSizeRow(
                    label = stringResource(MR.string.storage_database),
                    sizeBytes = uiState.databaseSizeBytes,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun CacheRow(state: CacheRowState, isMemory: Boolean = false) {
    val fraction = if (state.maxBytes <= 0L) 0f
    else (state.sizeBytes.toFloat() / state.maxBytes.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(cacheLabelRes(state.id)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (isMemory) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(MR.string.storage_cache_ram_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(
                    MR.string.storage_cache_size_format,
                    formatBytes(state.sizeBytes),
                    formatBytes(state.maxBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun SimpleSizeRow(label: String, sizeBytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = formatBytes(sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DriveRow(state: DriveRowState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(MR.string.storage_drive_count_format, state.itemCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun cacheLabelRes(id: String): StringResource = when (id) {
    "public_profiles" -> MR.string.storage_cache_profiles
    "public_images" -> MR.string.storage_cache_profile_images
    "drive_payloads" -> MR.string.storage_cache_payloads
    "drive_thumbnails" -> MR.string.storage_cache_thumbnails
    StorageSettingsViewModel.COIL_MEMORY_ID -> MR.string.storage_cache_coil_memory
    else -> MR.string.storage_cache_unknown
}
