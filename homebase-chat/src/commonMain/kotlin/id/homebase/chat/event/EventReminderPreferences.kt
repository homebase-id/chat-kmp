package id.homebase.chat.event

import id.homebase.api.sync.database.DatabaseManager
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long before an event starts the RSVP "Going" self-reminder fires. Defaults to 60 minutes
 * (issue #1116); persisted so a later settings picker can change it without a schema change. No
 * settings UI ships with #1116 — this is the storage + default only.
 *
 * Stored in the encrypted local key/value store via [DatabaseManager.keyValue].
 * UUID namespace `0a05xx` (Vault `0a01xx`, Moments `0a02xx`, Dice/Location `0a03xx`, `0a04xx` taken).
 */
class EventReminderPreferences(private val databaseManager: DatabaseManager) {

    private val keyValue get() = databaseManager.keyValue

    private val _leadMinutes = MutableStateFlow(readInt(LEAD_MINUTES_KEY, default = DEFAULT_LEAD_MINUTES))
    val leadMinutes: StateFlow<Int> = _leadMinutes.asStateFlow()

    /** Lead time in milliseconds — the amount subtracted from the event start to get `sendAt`. */
    val leadMillis: Long get() = _leadMinutes.value * 60_000L

    suspend fun setLeadMinutes(minutes: Int) {
        if (_leadMinutes.value == minutes) return
        keyValue.upsertValue(LEAD_MINUTES_KEY, encodeInt(minutes))
        _leadMinutes.value = minutes
    }

    private fun readInt(key: Uuid, default: Int): Int {
        // Bootstrap-only sync read — seeds the StateFlow at construction (mirrors DiceRollPreferences;
        // commonMain has no runBlocking on wasmJs). See KeyValueWrapper.selectByKeyBootstrapSync.
        val bytes: ByteArray = runCatching {
            keyValue.selectByKeyBootstrapSync(key)?.data_
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
        const val DEFAULT_LEAD_MINUTES = 60
        val LEAD_MINUTES_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a0501")
    }
}
