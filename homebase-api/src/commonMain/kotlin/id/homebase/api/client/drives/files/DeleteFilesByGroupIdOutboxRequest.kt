package id.homebase.api.client.drives.files

import id.homebase.api.serialization.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class DeleteFilesByGroupIdOutboxRequest(
    @Serializable(with = UuidSerializer::class) val driveId: Uuid,
    val groupIds: List<@Serializable(with = UuidSerializer::class) Uuid>
)
