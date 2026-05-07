package id.homebase.chat.dice

import id.homebase.api.sync.database.DatabaseManager
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers the user's last dice-roll selection so the composer reopens with the
 * same `count` and `faces` they last rolled. Stored in the encrypted local
 * key/value store via [DatabaseManager.keyValue].
 *
 * UUID namespace `0a03xx` (Vault reserved `0a01xx`, next add-on `0a02xx`).
 */
class DiceRollPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _lastFaces = MutableStateFlow(readInt(LAST_FACES_KEY, default = DEFAULT_FACES))
    val lastFaces: StateFlow<Int> = _lastFaces.asStateFlow()

    private val _lastCount = MutableStateFlow(readInt(LAST_COUNT_KEY, default = DEFAULT_COUNT))
    val lastCount: StateFlow<Int> = _lastCount.asStateFlow()

    suspend fun setLast(count: Int, faces: Int) {
        if (_lastCount.value != count) {
            keyValue.upsertValue(LAST_COUNT_KEY, encodeInt(count))
            _lastCount.value = count
        }
        if (_lastFaces.value != faces) {
            keyValue.upsertValue(LAST_FACES_KEY, encodeInt(faces))
            _lastFaces.value = faces
        }
    }

    private fun readInt(key: Uuid, default: Int): Int {
        val bytes: ByteArray = runCatching {
            keyValue.selectByKey(key)?.data_
        }.getOrNull() ?: return default
        if (bytes.size != 4) return default
        return (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    private fun encodeInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    companion object {
        const val DEFAULT_FACES = 6
        const val DEFAULT_COUNT = 1
        val LAST_FACES_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0301")
        val LAST_COUNT_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0302")
    }
}
