package id.homebase.core.ui.screens.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.cache.CacheStats
import id.homebase.api.client.drives.cache.DriveFileProviderCached
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.file.CacheAudit
import id.homebase.api.file.CacheSweeper
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.directorySizeBytes
import id.homebase.api.file.systemFileSystem
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.sync.DriveRegistry
import id.homebase.api.sync.database.DatabaseSizeProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid

class StorageSettingsViewModel(
    private val publicProfileProviderCached: PublicProfileProviderCached,
    private val driveFileProviderCached: DriveFileProviderCached,
    private val driveSyncManager: DriveSyncManager,
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val imageLoader: ImageLoader,
    private val databaseSizeProbe: DatabaseSizeProbe,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveRegistry: DriveRegistry,
) : ViewModel() {

    private val fileSystem = systemFileSystem

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
            // Unavailable caches (sizeBytes == CacheStats.UNAVAILABLE, -1L) must
            // not contribute to the total — they represent caches that could
            // not be opened, not caches with negative size.
            val total = caches.sumOf { if (it.sizeBytes == CacheStats.UNAVAILABLE) 0L else it.sizeBytes }

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

            val orphanCoilBytes = withContext(Dispatchers.Default) {
                probeOrphanCoilDiskCache()
            }

            // Everything in the cache directory the app neither tracks nor caps.
            // The orphan Coil dir is part of that untracked total but gets its
            // own red warning row, so subtract it here to avoid double-counting.
            val otherCacheBytes = withContext(Dispatchers.Default) {
                runCatching {
                    CacheAudit.audit(fileOperationsProvider.getCacheDirectory()).untrackedBytes
                }.getOrElse {
                    Logger.w(tag = "StorageSettings", throwable = it) { "cache audit failed" }
                    0L
                }
            }

            _uiState.update {
                it.copy(
                    caches = caches,
                    coilMemoryCache = coilRow,
                    drives = driveRows,
                    totalCacheBytes = total,
                    databaseSizeBytes = dbSize,
                    orphanCoilDiskBytes = orphanCoilBytes,
                    otherCacheBytes = (otherCacheBytes - orphanCoilBytes).coerceAtLeast(0L),
                    isLoading = false,
                )
            }
        }
    }

    /**
     * Measure the size of Coil's default disk cache directory (`coil3_disk_cache`)
     * if it exists. In a correctly-configured build this is always 0 — we set
     * `.diskCache(null)` on our ImageLoader and wire the SingletonImageLoader
     * to that instance, so nothing should write there. A non-zero value means
     * either a leftover directory from an older build or a regression that
     * reintroduced Coil's default disk cache somewhere. Logged loudly so the
     * anomaly is visible in logs too.
     */
    private fun probeOrphanCoilDiskCache(): Long {
        return runCatching {
            val path = "${fileOperationsProvider.getCacheDirectory()}/coil3_disk_cache".toPath()
            if (!fileSystem.exists(path)) return@runCatching 0L
            val total = fileSystem.directorySizeBytes(path)
            if (total > 0L) {
                Logger.e(tag = "StorageSettings") {
                    "orphan coil3_disk_cache detected: $total bytes at $path — " +
                        "Coil's default disk cache should be off and the directory should be empty/deleted"
                }
            }
            total
        }.getOrElse {
            Logger.w(tag = "StorageSettings", throwable = it) { "orphan coil disk probe failed" }
            0L
        }
    }

    private suspend fun loadDriveRows(): List<DriveRowState> {
        val identityId: Uuid = runCatching {
            credentialsManager.requireActiveCredentials().getIdentityId()
        }.getOrElse {
            Logger.w(tag = "StorageSettings", throwable = it) { "no active credentials — cannot load drive counts" }
            return emptyList()
        }

        val mountedDrives = driveSyncManager.driveStatuses.value.values
            .map { it.driveId to it.label }
        val mountedIds = mountedDrives.map { it.first }.toSet()
        val optionalDrives = try { driveRegistry.loadDrives() }
            catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            .filter { it.drive.alias !in mountedIds }
            .map { it.drive.alias to it.label }
        val drives = (mountedDrives + optionalDrives).sortedBy { it.second }

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
            // CacheSweeper absorbs the role of "delete orphan coil3_disk_cache" + adds
            // diagnostic logging for any other untracked entries — replaces the
            // standalone safeDeleteRecursively("coil3_disk_cache") line that used to
            // live here.
            runCatching {
                CacheSweeper.sweepUntracked(CacheAudit.audit(fileOperationsProvider.getCacheDirectory()))
            }.onFailure {
                Logger.w(tag = "StorageSettings", throwable = it) { "post-clear cache sweep failed" }
            }
            _uiState.update { it.copy(isClearing = false, uiEvent = StorageSettingsUiEvent.CachesCleared) }
            load()
        }
    }

    companion object {
        const val COIL_MEMORY_ID: String = "coil_memory"
    }
}
