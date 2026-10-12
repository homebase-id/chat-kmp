package id.homebase.core.filesync

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

class FilesyncPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _iconVisible = MutableStateFlow(readIconVisible())
    val iconVisible: StateFlow<Boolean> = _iconVisible.asStateFlow()

    fun reset() {
        _iconVisible.value = readIconVisible()
    }

    suspend fun setIconVisible(value: Boolean) {
        if (_iconVisible.value == value) return
        keyValue.upsertValue(ICON_VISIBLE_KEY, byteArrayOf(if (value) 1 else 0))
        _iconVisible.value = value
    }

    // Bootstrap-only sync read, as in VaultPreferences: commonMain has no runBlocking on wasmJs.
    private fun readIconVisible(): Boolean {
        val bytes = runCatching {
            keyValue.selectByKeyBootstrapSync(ICON_VISIBLE_KEY) { _, data -> data }
        }.getOrNull()
        return if (bytes == null || bytes.isEmpty()) true else bytes[0].toInt() != 0
    }

    companion object {
        val ICON_VISIBLE_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0a02")
    }
}
