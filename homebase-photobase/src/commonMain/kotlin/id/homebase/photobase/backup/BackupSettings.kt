package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CancellationException
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
    val photoQuality: MediaQuality = MediaQuality.ORIGINAL,
    /** At Original, the video as recorded plus a 1080p copy to play (UPLOADS.md section 3.6). */
    val videoQuality: MediaQuality = MediaQuality.HD,
    /** Set while Photos isn't installed, so only a new install asks the owner to confirm the qualities. */
    val qualityUnconfirmed: Boolean = false,
) {
    companion object {
        val DEFAULT_PUSH_INTERVAL: Duration = 1.hours
        val MIN_PUSH_INTERVAL: Duration = 30.minutes
    }
}

class BackupSettingsUnreadable : IllegalStateException("backup settings couldn't be read")

/** Per identity: lives in KeyValue, which logout wipes. */
class BackupSettingsStore(private val databaseManager: DatabaseManager) {
    private val keyValue get() = databaseManager.keyValue

    private val _settings = MutableStateFlow(BackupSettings())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()

    private val _unreadable = MutableStateFlow(false)

    /** The stored settings couldn't be read: [settings] holds the defaults, and [update] refuses. */
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    // A retry started before a reset() reads the identity that was signed in then.
    private var generation = 0

    init {
        reset()
    }

    // Bootstrap-only sync read, as in VaultPreferences: commonMain has no runBlocking on wasmJs.
    fun reset() {
        generation++
        apply(decode(runCatching { keyValue.selectByKeyBootstrapSync(SETTINGS_KEY) { _, data -> data } }))
    }

    /** Reads again after a failure, on the read lane; true when the settings are back. */
    suspend fun retryIfUnreadable(): Boolean {
        if (!_unreadable.value) return false
        val started = generation
        val read = runCatching { keyValue.selectByKey(SETTINGS_KEY) { _, data -> data } }
        (read.exceptionOrNull() as? CancellationException)?.let { throw it }
        if (started != generation) return false
        apply(decode(read))
        return !_unreadable.value
    }

    /** Throws [BackupSettingsUnreadable] while the stored settings can't be read: saving the defaults would replace them. */
    suspend fun update(transform: (BackupSettings) -> BackupSettings) {
        if (_unreadable.value) throw BackupSettingsUnreadable()
        val next = transform(_settings.value).let {
            it.copy(pushIntervalMs = it.pushIntervalMs.coerceAtLeast(BackupSettings.MIN_PUSH_INTERVAL.inWholeMilliseconds))
        }
        keyValue.upsertValue(SETTINGS_KEY, OdinSystemSerializer.serialize(next).encodeToByteArray())
        _settings.value = next
    }

    // A read that failed may work next time; bytes that don't decode won't, so they give way to the defaults.
    private fun decode(read: Result<ByteArray?>): BackupSettings? {
        read.exceptionOrNull()?.let {
            Logger.w(tag = TAG, throwable = it) { "couldn't read the backup settings" }
            return null
        }
        val bytes = read.getOrNull()
        if (bytes == null || bytes.isEmpty()) return BackupSettings()
        return runCatching { OdinSystemSerializer.deserialize<BackupSettings>(bytes.decodeToString()) }
            .onFailure { Logger.e(tag = TAG, throwable = it) { "stored backup settings don't decode; using the defaults" } }
            .getOrDefault(BackupSettings())
    }

    // The settings first: nothing may see them readable while they still hold the defaults.
    private fun apply(stored: BackupSettings?) {
        _settings.value = stored ?: BackupSettings()
        _unreadable.value = stored == null
    }

    private companion object {
        const val TAG = "BackupSettingsStore"
        val SETTINGS_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000b0a01")
    }
}
