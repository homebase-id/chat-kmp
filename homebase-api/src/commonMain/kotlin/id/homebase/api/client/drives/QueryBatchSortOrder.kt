package id.homebase.homebasekmppoc.prototype.lib.drives

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sort order for query batch results
 *
 * Ported from C# Odin.Core.Storage.Database.Identity.Abstractions.QueryBatchSortOrder
 */
@Serializable
enum class QueryBatchSortOrder(val value: Int) {
    @SerialName("default")
    Default(0),      // NewestFirst

    @SerialName("newestFirst")
    NewestFirst(1),

    @SerialName("oldestFirst")
    OldestFirst(2);

    companion object {
        fun fromInt(value: Int): id.homebase.homebasekmppoc.prototype.lib.drives.QueryBatchSortOrder {
            return entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown QueryBatchSortOrder: $value")
        }
    }
}
