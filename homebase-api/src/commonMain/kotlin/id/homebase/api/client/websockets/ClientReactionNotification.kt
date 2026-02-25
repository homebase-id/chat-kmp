package id.homebase.api.client.websockets

import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.serialization.Serializable

@Serializable
data class ClientReactionNotification(
    val odinId: String,
    val fileId: InternalDriveFileId,
    val created: UnixTimeUtc,
    val reactionContent: String
)