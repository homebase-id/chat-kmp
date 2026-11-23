package id.homebase.core.calling

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/** Per-device: the "seen" marker is not synced, so a missed call badge can reappear on another device. */
class CallsPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _iconVisible = MutableStateFlow(readBoolean(ICON_VISIBLE_KEY, default = true))
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    private val _lastSeenMissedAtMs = MutableStateFlow(readLong(LAST_SEEN_MISSED_AT_KEY))
    val lastSeenMissedAtMs: StateFlow<Long> = _lastSeenMissedAtMs.asStateFlow()

    suspend fun setIconVisible(value: Boolean) {
        if (_iconVisible.value == value) return
        keyValue.upsertValue(ICON_VISIBLE_KEY, byteArrayOf(if (value) 1 else 0))
        _iconVisible.value = value
    }

    suspend fun setLastSeenMissedAtMs(value: Long) {
        if (_lastSeenMissedAtMs.value == value) return
        keyValue.upsertValue(LAST_SEEN_MISSED_AT_KEY, value.toString().encodeToByteArray())
        _lastSeenMissedAtMs.value = value
    }

    private fun readBytes(key: Uuid): ByteArray? = runCatching {
        keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
    }.getOrNull()

    private fun readBoolean(key: Uuid, default: Boolean): Boolean {
        val bytes = readBytes(key)?.takeIf { it.isNotEmpty() } ?: return default
        return bytes[0].toInt() != 0
    }

    private fun readLong(key: Uuid): Long = readBytes(key)?.decodeToString()?.toLongOrNull() ?: 0L

    companion object {
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0c01")
        val LAST_SEEN_MISSED_AT_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0c02")
    }
}
