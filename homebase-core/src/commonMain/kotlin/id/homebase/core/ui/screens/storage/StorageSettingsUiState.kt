package id.homebase.core.ui.screens.storage

import kotlin.uuid.Uuid

data class StorageSettingsUiState(
    val caches: List<CacheRowState> = emptyList(),
    val coilMemoryCache: CacheRowState? = null,
    val drives: List<DriveRowState> = emptyList(),
    val totalCacheBytes: Long = 0L,
    val databaseSizeBytes: Long = 0L,
    val isLoading: Boolean = true,
    val isClearing: Boolean = false,
    val uiEvent: StorageSettingsUiEvent? = null,
)

data class CacheRowState(
    val id: String,
    val sizeBytes: Long,
    val maxBytes: Long,
)

data class DriveRowState(
    val driveId: Uuid,
    val label: String,
    val itemCount: Long,
)

sealed interface StorageSettingsUiAction {
    data object Refresh : StorageSettingsUiAction
    data object ClearCachesClicked : StorageSettingsUiAction
}

sealed interface StorageSettingsUiEvent {
    data object CachesCleared : StorageSettingsUiEvent
}
