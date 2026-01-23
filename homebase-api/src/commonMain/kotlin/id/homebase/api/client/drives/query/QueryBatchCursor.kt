package id.homebase.homebasekmppoc.prototype.lib.drives.query

import id.homebase.homebasekmppoc.prototype.lib.core.time.UnixTimeUtc
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
        fun fromJson(jsonString: String): id.homebase.homebasekmppoc.prototype.lib.drives.query.TimeRowCursor {
            return Json.decodeFromString(jsonString)
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
    val paging: id.homebase.homebasekmppoc.prototype.lib.drives.query.TimeRowCursor? = null,
    val stop: id.homebase.homebasekmppoc.prototype.lib.drives.query.TimeRowCursor? = null,
    val next: id.homebase.homebasekmppoc.prototype.lib.drives.query.TimeRowCursor? = null
) {
    constructor(jsonString: String) : this() {
        val decoded = Json.decodeFromString<id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor>(jsonString)
        // Note: In Kotlin, we can't reassign val properties after construction
        // The decoded object would need to be used directly or this pattern refactored
    }

    fun clone(): id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor {
        return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor(
            paging = paging?.copy(),
            stop = stop?.copy(),
            next = next?.copy()
        )
    }

    fun toJson(): String {
        return Json.encodeToString(this)
    }

    companion object {
        fun fromStartPoint(fromTimestamp: UnixTimeUtc): id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor {
            return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor(
                paging = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.query.TimeRowCursor(
                    fromTimestamp,
                    null
                )
            )
        }

        fun fromJson(jsonString: String): id.homebase.homebasekmppoc.prototype.lib.drives.query.QueryBatchCursor {
            return Json.decodeFromString(jsonString)
        }
    }
}
