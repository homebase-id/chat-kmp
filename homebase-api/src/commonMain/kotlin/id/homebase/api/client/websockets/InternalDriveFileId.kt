package id.homebase.api.client.websockets

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class InternalDriveFileId(
    val driveId: Uuid,
    val fileId: Uuid
)