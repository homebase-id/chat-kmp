package id.homebase.api.client.drives.query

import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Time-based cursor for pagination with optional row ID for tie-breaking
 *
 * Ported from C# Odin.Core.Storage.TimeRowCursor
 */
@Serializable
data class TimeRowCursor(
    val time: UnixTimeUtc,
    val row: Long? = null
) {
    fun toJson(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromJson(jsonString: String): TimeRowCursor {
            try {
                return Json.decodeFromString(jsonString)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid TimeRowCursor JSON: ${jsonString.take(100)}", e)
            }
        }
    }
}

/**
 * Cursor for batch query pagination with boundary management
 *
 * Ported from C# Odin.Core.Storage.QueryBatchCursor
 */
@Serializable
data class QueryBatchCursor(
    val paging: TimeRowCursor? = null,
    val stop: TimeRowCursor? = null,
    val next: TimeRowCursor? = null
) {
    fun clone(): QueryBatchCursor {
        return QueryBatchCursor(
            paging = paging?.copy(),
            stop = stop?.copy(),
            next = next?.copy()
        )
    }

    fun toJson(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromStartPoint(fromTimestamp: UnixTimeUtc): QueryBatchCursor {
            return QueryBatchCursor(
                paging = TimeRowCursor(
                    fromTimestamp,
                    null
                )
            )
        }

        fun fromJson(jsonString: String): QueryBatchCursor {
            try {
                return Json.decodeFromString(jsonString)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid QueryBatchCursor JSON: ${jsonString.take(100)}", e)
            }
        }
    }
}
