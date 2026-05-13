package id.homebase.api.client.drives.files

import kotlinx.serialization.Serializable

@Serializable
data class InboxStatus(
    val totalItems: Int,
    val poppedCount: Int,
    val oldestItemTimestamp: Long
)
