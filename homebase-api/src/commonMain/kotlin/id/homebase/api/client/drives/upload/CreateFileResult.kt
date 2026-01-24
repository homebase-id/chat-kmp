package id.homebase.api.client.drives.upload

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class CreateFileResult(
    val fileId: Uuid,
    val driveId: Uuid,
    var globalTransitId: Uuid? = null,
    val recipientStatus: Map<String, TransferUploadStatus>? = null,
    val newVersionTag: Uuid
)


@Serializable
data class UpdateFileResult(
    val fileId: Uuid,
    val driveId: Uuid,
    val globalTransitId: Uuid? = null,
    val recipientStatus: Map<String, TransferUploadStatus>? = null,
    val newVersionTag: Uuid
)
