package id.homebase.api.client.drives.upload

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class UpdateLocalMetadataTagsOutboxRequest(
    val file: FileIdFileIdentifier,
    val versionTag: String?,
    val tags: List<String>?
)

@Serializable
data class UpdateLocalAppdataContentOutboxRequest(
    val driveId: Uuid,
    val fileId: Uuid,
    val versionTag: String?,
    val content: String?,
    val iv: String?
)
