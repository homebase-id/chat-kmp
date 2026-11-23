package id.homebase.core.calling

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/** Per-device: the "seen" marker is not synced, so a missed call badge can reappear on another device. */
class CallsPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _lastSeenMissedAtMs = MutableStateFlow(readLong(LAST_SEEN_MISSED_AT_KEY))
    val lastSeenMissedAtMs: StateFlow<Long> = _lastSeenMissedAtMs.asStateFlow()

    suspend fun setLastSeenMissedAtMs(value: Long) {
        if (_lastSeenMissedAtMs.value == value) return
        keyValue.upsertValue(LAST_SEEN_MISSED_AT_KEY, value.toString().encodeToByteArray())
        _lastSeenMissedAtMs.value = value
    }

    private fun readBytes(key: Uuid): ByteArray? = runCatching {
        keyValue.selectByKeyBootstrapSync(key) { _, data -> data }
    }.getOrNull()

    private fun readLong(key: Uuid): Long = readBytes(key)?.decodeToString()?.toLongOrNull() ?: 0L

    companion object {
        // 0a0c01 (bottom-bar icon flag) and 0a0c03 (beta enable flag) are retired, never reuse.
        val LAST_SEEN_MISSED_AT_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0c02")
    }
}
