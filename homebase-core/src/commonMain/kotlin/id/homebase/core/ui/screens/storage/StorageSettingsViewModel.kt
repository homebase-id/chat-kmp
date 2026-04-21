package id.homebase.core.ui.screens.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DatabaseSizeProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

class StorageSettingsViewModel(
    private val publicProfileProviderCached: PublicProfileProviderCached,
    private val driveFileProviderCached: DriveFileProviderCached,
    private val driveSyncManager: DriveSyncManager,
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val imageLoader: ImageLoader,
    private val databaseSizeProbe: DatabaseSizeProbe,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageSettingsUiState())
    val uiState: StateFlow<StorageSettingsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun onAction(action: StorageSettingsUiAction) {
        when (action) {
            StorageSettingsUiAction.Refresh -> load()
            StorageSettingsUiAction.ClearCachesClicked -> clearCaches()
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val profileStats = runCatching { publicProfileProviderCached.getCacheStats() }
                .getOrElse {
                    Logger.w(tag = "StorageSettings", throwable = it) { "profile cache stats failed" }
                    emptyList()
                }
            val driveStats = runCatching { driveFileProviderCached.getCacheStats() }
                .getOrElse {
                    Logger.w(tag = "StorageSettings", throwable = it) { "drive cache stats failed" }
                    emptyList()
                }
            val caches = (profileStats + driveStats).map {
                CacheRowState(id = it.id, sizeBytes = it.sizeBytes, maxBytes = it.maxBytes)
            }
            val total = caches.sumOf { it.sizeBytes }

            val coilRow = imageLoader.memoryCache?.let { mem ->
                CacheRowState(
                    id = COIL_MEMORY_ID,
                    sizeBytes = mem.size,
                    maxBytes = mem.maxSize,
                )
            }

            val dbSize = withContext(Dispatchers.Default) {
                runCatching { databaseSizeProbe.sizeBytes() }
                    .getOrElse {
                        Logger.w(tag = "StorageSettings", throwable = it) { "database size probe failed" }
                        0L
                    }
            }

            val driveRows = loadDriveRows()

            _uiState.update {
                it.copy(
                    caches = caches,
                    coilMemoryCache = coilRow,
                    drives = driveRows,
                    totalCacheBytes = total,
                    databaseSizeBytes = dbSize,
                    isLoading = false,
                )
            }
        }
    }

    private suspend fun loadDriveRows(): List<DriveRowState> {
        val identityId: Uuid = runCatching {
            credentialsManager.requireActiveCredentials().getIdentityId()
        }.getOrElse {
            Logger.w(tag = "StorageSettings", throwable = it) { "no active credentials — cannot load drive counts" }
            return emptyList()
        }

        val drives = driveSyncManager.driveStatuses.value.values
            .map { it.driveId to it.label }
            .sortedBy { it.second }

        return withContext(Dispatchers.Default) {
            val rows = ArrayList<DriveRowState>(drives.size)
            for ((driveId, label) in drives) {
                val count = runCatching {
                    databaseManager.driveMainIndex.countByIdentityAndDrive(identityId, driveId)
                }.getOrElse {
                    Logger.w(tag = "StorageSettings", throwable = it) { "drive count failed for $driveId" }
                    0L
                }
                rows.add(DriveRowState(driveId = driveId, label = label, itemCount = count))
            }
            rows
        }
    }

    private fun clearCaches() {
        if (_uiState.value.isClearing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true) }
            runCatching { publicProfileProviderCached.clearCaches() }
                .onFailure { Logger.w(tag = "StorageSettings", throwable = it) { "profile clearCaches failed" } }
            runCatching { driveFileProviderCached.clearCaches() }
                .onFailure { Logger.w(tag = "StorageSettings", throwable = it) { "drive clearCaches failed" } }
            runCatching { imageLoader.memoryCache?.clear() }
                .onFailure { Logger.w(tag = "StorageSettings", throwable = it) { "coil memory clear failed" } }
            _uiState.update { it.copy(isClearing = false, uiEvent = StorageSettingsUiEvent.CachesCleared) }
            load()
        }
    }

    companion object {
        const val COIL_MEMORY_ID: String = "coil_memory"
    }
}
