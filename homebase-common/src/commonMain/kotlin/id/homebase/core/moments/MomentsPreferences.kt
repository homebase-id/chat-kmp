package id.homebase.core.moments

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

class MomentsPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _activated = MutableStateFlow(readBoolean(ACTIVATED_KEY, default = false))
    val activated: StateFlow<Boolean> = _activated.asStateFlow()

    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = false))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    private val _viewMode = MutableStateFlow(readViewMode())
    val viewMode: StateFlow<MomentsViewMode> = _viewMode.asStateFlow()

    suspend fun setActivated(value: Boolean) {
        if (_activated.value == value) return
        keyValue.upsertValue(ACTIVATED_KEY, encode(value))
        _activated.value = value
    }

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
        val bytes: ByteArray = runCatching {
            keyValue.selectByKey(key) { _, data -> data }
        }.getOrNull() ?: return default
        return if (bytes.isEmpty()) default else bytes[0].toInt() != 0
    }

    private fun readViewMode(): MomentsViewMode {
        val bytes: ByteArray = runCatching {
            keyValue.selectByKey(VIEW_MODE_KEY) { _, data -> data }
        }.getOrNull() ?: return MomentsViewMode.Timeline
        if (bytes.isEmpty()) return MomentsViewMode.Timeline
        val name = bytes.decodeToString()
        return MomentsViewMode.entries.firstOrNull { it.name == name }
            ?: MomentsViewMode.Timeline
    }

    private fun encode(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)

    companion object {
        // Stable namespace for Moments. Vault owns 0a01xx; Moments is the next free slot.
        val ACTIVATED_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0201")
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
 */
enum class MomentsViewMode {
    Timeline,
    Album,
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
