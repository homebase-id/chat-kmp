package id.homebase.api.client.drives

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sort field for query batch results
 *
 * Ported from C# Odin.Core.Storage.Database.Identity.Abstractions.QueryBatchSortField
 */
@Serializable
enum class QueryBatchSortField(val value: Int) {
    @SerialName("fileId")
    FileId(0),           // OBSOLETE

    @SerialName("userDate")
    UserDate(1),

    @SerialName("createdDate")
    CreatedDate(2),

    @SerialName("anyChangeDate")
    AnyChangeDate(3),    // By Newly created or modified

    @SerialName("onlyModifiedDate")
    OnlyModifiedDate(4);  // Not yet implemented

    companion object {
        fun fromInt(value: Int): QueryBatchSortField {
            return entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown QueryBatchSortField: $value")
        }
    }
}
