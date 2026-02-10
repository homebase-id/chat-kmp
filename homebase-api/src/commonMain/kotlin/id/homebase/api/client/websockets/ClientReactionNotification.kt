package id.homebase.api.client.websockets

import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ClientReactionNotification(
    val odinId: String,
    val fileId: InternalDriveFileId,
    val created: UnixTimeUtc,
    val reactionContent: String
)

@Serializable
data class InternalDriveFileId(
    val driveId: Uuid,
    val fileId: Uuid
)

@Serializable
data class ReactionContent(
    val emoji: String
)