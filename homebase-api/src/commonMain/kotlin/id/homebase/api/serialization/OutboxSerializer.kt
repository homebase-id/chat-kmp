package id.homebase.api.serialization

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.Outbox
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class OutboxDecodeException(
    val requestType: String,
    val rowId: Long,
    cause: Throwable,
) : Exception("Undecodable outbox row $rowId ($requestType): ${cause.message}", cause)

// Local persistence, not C# server compat: a row the current code cannot read exactly must fail, never decode to defaults.
object OutboxSerializer {
    val json = Json(from = OdinSystemSerializer.json) {
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    inline fun <reified T> serialize(value: T): String = json.encodeToString(value)

    inline fun <reified T> decode(row: Outbox): T =
        try {
            json.decodeFromString<T>(row.json.decodeToString())
        } catch (e: Exception) {
            if (e !is SerializationException && e !is IllegalArgumentException) throw e
            val type = T::class.simpleName ?: "unknown"
            Logger.e("OutboxSerializer: cannot decode outbox row ${row.rowId} as $type: ${e.message}", e)
            throw OutboxDecodeException(type, row.rowId, e)
        }
}
