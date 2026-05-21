package id.homebase.core.ui.screens.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.cache.CacheStats
import id.homebase.common.util.formatBytes
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.settings_storage
import id.homebase.resources.storage_orphan_coil_body
import id.homebase.resources.storage_orphan_coil_title
import id.homebase.resources.storage_cache_coil_memory
import id.homebase.resources.storage_cache_payloads
import id.homebase.resources.storage_cache_profile_images
import id.homebase.resources.storage_cache_profiles
import id.homebase.resources.storage_cache_ram_badge
import id.homebase.resources.storage_cache_size_format
import id.homebase.resources.storage_cache_thumbnails
import id.homebase.resources.storage_cache_unavailable
import id.homebase.resources.storage_cache_unhealthy_body
import id.homebase.resources.storage_cache_unhealthy_title
import id.homebase.resources.storage_cache_unknown
import id.homebase.resources.storage_caches_cleared
import id.homebase.resources.storage_caches_header
import id.homebase.resources.storage_caches_none
import id.homebase.resources.storage_clear_caches
import id.homebase.resources.storage_database
import id.homebase.resources.storage_defragment_button
import id.homebase.resources.storage_drive_count_format
import id.homebase.resources.storage_drives_header
import id.homebase.resources.storage_drives_none
import id.homebase.resources.storage_other_cache_files
import id.homebase.resources.storage_other_header
import id.homebase.resources.storage_total_cache_format
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun StorageSettingsScreen(
    viewModel: StorageSettingsViewModel,
    onBackClick: () -> Unit,
    onNavigateToDefragmenter: () -> Unit,
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
        onNavigateToDefragmenter = onNavigateToDefragmenter,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsUi(
    uiState: StorageSettingsUiState,
    onAction: (StorageSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToDefragmenter: () -> Unit,
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                        if (uiState.caches.isNotEmpty()) {
                            DiskCachesBlock(caches = uiState.caches)
                        }
                        uiState.coilMemoryCache?.let { coil ->
                            if (uiState.caches.isNotEmpty()) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                            CoilMemoryRow(state = coil)
                        }
                    }
                }
            }

            if (uiState.orphanCoilDiskBytes > 0L) {
                OrphanCoilCacheWarning(bytes = uiState.orphanCoilDiskBytes)
            }

            val hasUnhealthyCache = uiState.caches.any { it.sizeBytes == CacheStats.UNAVAILABLE }
            if (hasUnhealthyCache) {
                UnhealthyCacheWarning()
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

            Button(
                onClick = onNavigateToDefragmenter,
                enabled = uiState.drives.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(MR.string.storage_defragment_button))
            }

            SectionHeader(title = stringResource(MR.string.storage_other_header))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SimpleSizeRow(
                        label = stringResource(MR.string.storage_database),
                        sizeBytes = uiState.databaseSizeBytes,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SimpleSizeRow(
                        label = stringResource(MR.string.storage_other_cache_files),
                        sizeBytes = uiState.otherCacheBytes,
                    )
                }
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
private fun DiskCachesBlock(caches: List<CacheRowState>) {
    // Skip unavailable caches from the usage-bar math — their sizeBytes is the
    // -1L sentinel (see CacheStats.UNAVAILABLE) and would poison totals.
    val healthy = caches.filter { it.sizeBytes != CacheStats.UNAVAILABLE }
    val totalMax = healthy.sumOf { it.maxBytes }
    val totalUsed = healthy.sumOf { it.sizeBytes }
    val freeFraction = if (totalMax > 0L)
        ((totalMax - totalUsed).toFloat() / totalMax.toFloat()).coerceAtLeast(0f)
    else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            for (cache in healthy) {
                val w = if (totalMax > 0L)
                    (cache.sizeBytes.toFloat() / totalMax.toFloat()).coerceAtLeast(0f)
                else 0f
                if (w > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(w)
                            .fillMaxHeight()
                            .background(cacheColor(cache.id)),
                    )
                }
            }
            if (freeFraction > 0f) {
                Box(
                    modifier = Modifier
                        .weight(freeFraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (cache in caches) {
                CacheLegendRow(cache)
            }
        }
    }
}

@Composable
private fun CacheLegendRow(state: CacheRowState) {
    val unavailable = state.sizeBytes == CacheStats.UNAVAILABLE
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (unavailable) MaterialTheme.colorScheme.error
                    else cacheColor(state.id)
                ),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = stringResource(cacheLabelRes(state.id)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (unavailable) {
            Text(
                text = stringResource(MR.string.storage_cache_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
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
    }
}

@Composable
private fun CoilMemoryRow(state: CacheRowState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(cacheLabelRes(state.id)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(MR.string.storage_cache_ram_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
}

@Composable
private fun cacheColor(id: String): Color = when (id) {
    "public_profiles"  -> MaterialTheme.colorScheme.primary
    "public_images"    -> MaterialTheme.colorScheme.tertiary
    "drive_payloads"   -> MaterialTheme.colorScheme.secondary
    "drive_thumbnails" -> MaterialTheme.colorScheme.errorContainer
    else               -> MaterialTheme.colorScheme.outline
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

@Composable
private fun OrphanCoilCacheWarning(bytes: Long) {
    ErrorCard(
        title = stringResource(MR.string.storage_orphan_coil_title),
        body = stringResource(MR.string.storage_orphan_coil_body, formatBytes(bytes)),
    )
}

@Composable
private fun UnhealthyCacheWarning() {
    ErrorCard(
        title = stringResource(MR.string.storage_cache_unhealthy_title),
        body = stringResource(MR.string.storage_cache_unhealthy_body),
    )
}

@Composable
private fun ErrorCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
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
