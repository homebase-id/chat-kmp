package id.homebase.api.client.websockets

import id.homebase.api.common.OdinId
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
data class ConnectionRequestReceivedNotification(
    val sender: OdinId,
    val recipient: OdinId
)
@Serializable
data class ConnectionRequestAcceptedNotification(
    val sender: OdinId,
    val recipient: OdinId
)

@Serializable
data class ConnectionRequestFinalizedNotification(
    val identity: OdinId
)

@Serializable
data class NewFollowerNotification(
    val sender: OdinId
)


@Serializable
data class IntroductionAcceptedNotification(
    val introducerOdinId: OdinId,
    val recipient: OdinId
)

@Serializable
data class Introduction (
    val identities: List<String>,
    val timestamp: Long,
    val message: String
)

@Serializable
data class IntroductionReceivedNotification(
    val introducerOdinId: OdinId,
    val introduction: Introduction
)

