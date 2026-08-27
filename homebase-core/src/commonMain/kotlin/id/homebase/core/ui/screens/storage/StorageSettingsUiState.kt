package id.homebase.core.ui.screens.storage

import id.homebase.api.image.MediaQuality
import id.homebase.resources.MR
import id.homebase.resources.settings_media_quality_high
import id.homebase.resources.settings_media_quality_standard
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

data class StorageSettingsUiState(
    val mediaQuality: MediaQuality = MediaQuality.STANDARD,
    val caches: List<CacheRowState> = emptyList(),
    val coilMemoryCache: CacheRowState? = null,
    val drives: List<DriveRowState> = emptyList(),
    val totalCacheBytes: Long = 0L,
    val databaseSizeBytes: Long = 0L,
    // Size of any leftover `coil3_disk_cache` directory. Should always be 0 in
    // a correctly-configured build (we disable Coil's default disk cache and
    // rewire the SingletonImageLoader to our instance). Non-zero means
    // something is bypassing our cache layer — either a stale install from
    // before the SingletonImageLoader was wired (the user can press "Clear
    // caches" to delete it) or a regression that reintroduced Coil's default
    // disk cache somewhere. Rendered as a red warning row.
    val orphanCoilDiskBytes: Long = 0L,
    // Aggregate size of every top-level cache-directory entry the app does not
    // track or cap — FFmpeg scratch (hls_*, compressed_*, ffmpeg-segmented-*),
    // decrypted downloads, share temp files, the legacy hbvid_preload dir, etc.
    // See CacheAudit. Surfaced so the gap between this and the tracked caches is
    // visible without adb. Excludes the separately-shown orphanCoilDiskBytes.
    val otherCacheBytes: Long = 0L,
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
    data class SetMediaQuality(val quality: MediaQuality) : StorageSettingsUiAction
}

val MediaQuality.label: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.settings_media_quality_standard
        MediaQuality.HIGH -> MR.string.settings_media_quality_high
    }

sealed interface StorageSettingsUiEvent {
    data object CachesCleared : StorageSettingsUiEvent
}
