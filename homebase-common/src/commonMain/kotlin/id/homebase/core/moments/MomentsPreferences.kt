package id.homebase.core.moments

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

class MomentsPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    // Activation is no longer a local preference flag — it's derived from the mounted
    // drive (see MomentsViewModel.isActivated), which reflects the cross-device
    // DriveRegistry. The old 0a0201 key is abandoned (no migration needed).

    // Visible by default — Moments shows in the bottom nav out of the box; tapping
    // it before onboarding routes to the activation flow (see AppNavHost.openMoments).
    // Users can still hide it from Settings (persists false, overriding this default).
    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = true))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    private val _viewMode = MutableStateFlow(readViewMode())
    val viewMode: StateFlow<MomentsViewMode> = _viewMode.asStateFlow()

    suspend fun setIconVisible(value: Boolean) {
        if (_iconVisible.value == value) return
        keyValue.upsertValue(ICON_VISIBLE_KEY, encode(value))
        _iconVisible.value = value
    }

    suspend fun setViewMode(value: MomentsViewMode) {
        if (_viewMode.value == value) return
        keyValue.upsertValue(VIEW_MODE_KEY, value.name.encodeToByteArray())
        _viewMode.value = value
    }

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        // Bootstrap-only sync read — seeds the StateFlow at construction. See
        // KeyValueWrapper.selectByKeyBootstrapSync for the rationale (commonMain
        // has no runBlocking on wasmJs).
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun readViewMode(): MomentsViewMode {
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(VIEW_MODE_KEY) { _, data -> data }
        }.getOrNull() ?: return MomentsViewMode.Timeline
        if (bytes.isEmpty()) return MomentsViewMode.Timeline
        val name = bytes.decodeToString()
        return MomentsViewMode.entries.firstOrNull { it.name == name }
            ?: MomentsViewMode.Timeline
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Stable namespace for Moments. Vault owns 0a01xx; Moments is the next free slot.
        // 0a0201 (former ACTIVATED_KEY) is retired — activation now derives from the drive.
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0202")
        val VIEW_MODE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0203")
    }
}

/**
 * How the moments feed is ordered.
 *
 * - [Timeline] sorts by the file's server-side creation timestamp — i.e. when
 *   the post was published. This is the default; new posts always land at the
 *   top regardless of when the photo was originally taken.
 * - [Album] sorts by `userDate` (EXIF "captured at" when available, falling
 *   back to the post timestamp) — useful for browsing a photo-album-style view
 *   ordered by when the moments actually happened.
 * - [Reels] is an immersive full-screen vertical-pager browse of the same
 *   posts (Instagram-Reels style), ordered like [Timeline] (newest posted
 *   first). It's the same per-page detail experience reached by tapping a
 *   moment, surfaced as a standing view mode rather than a navigation push.
 */
enum class MomentsViewMode {
    Timeline,
    Album,
    Reels,
}

/**
 * Album-view zoom level. Controls both the grid density (more columns per
 * higher zoom-out level) and the date-group granularity (Day → Month → Year).
 *
 * Session-only state — intentionally not persisted alongside [MomentsViewMode].
 * View mode is a long-lived user preference ("I prefer Album mode"); the zoom
 * level is browsing state that should reset to the most detailed view each
 * time the user lands on the Album.
 */
enum class MomentsAlbumZoom {
    Day,
    Month,
    Year,
}
