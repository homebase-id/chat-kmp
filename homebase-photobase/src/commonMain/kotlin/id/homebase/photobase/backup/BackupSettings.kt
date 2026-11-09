package id.homebase.photobase.backup

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@Serializable
data class BackupSettings(
    val enabled: Boolean = false,
    val albumIds: Set<String> = emptySet(),
    val wifiOnly: Boolean = true,
    val videosOnCellular: Boolean = false,
    val chargingOnly: Boolean = false,
    val pushIntervalMs: Long = DEFAULT_PUSH_INTERVAL.inWholeMilliseconds,
    /** "Only back up new items from now": older assets become SKIPPED. Null backs up everything. */
    val onlyNewFromMs: Long? = null,
    /** The order of the first backup of a library. After that, new photos always go first. */
    val newestFirst: Boolean = true,
) {
    companion object {
        val DEFAULT_PUSH_INTERVAL: Duration = 1.hours
        val MIN_PUSH_INTERVAL: Duration = 30.minutes
    }
}

/** Per identity: lives in KeyValue, which logout wipes. */
class BackupSettingsStore(private val databaseManager: DatabaseManager) {
    private val keyValue get() = databaseManager.keyValue

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()

    fun reset() {
        _settings.value = read()
    }

    suspend fun update(transform: (BackupSettings) -> BackupSettings) {
        val next = transform(_settings.value).let {
            it.copy(pushIntervalMs = it.pushIntervalMs.coerceAtLeast(BackupSettings.MIN_PUSH_INTERVAL.inWholeMilliseconds))
        }
        keyValue.upsertValue(SETTINGS_KEY, OdinSystemSerializer.serialize(next).encodeToByteArray())
        _settings.value = next
    }

    // Bootstrap-only sync read, as in VaultPreferences: commonMain has no runBlocking on wasmJs.
    private fun read(): BackupSettings {
        val bytes = runCatching { keyValue.selectByKeyBootstrapSync(SETTINGS_KEY) { _, data -> data } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return BackupSettings()
        return OdinSystemSerializer.deserialize(bytes.decodeToString())
    }

    private companion object {
        val SETTINGS_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000b0a01")
    }
}
