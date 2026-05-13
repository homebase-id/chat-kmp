package id.homebase.api.client.peer

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class FileExistsOnPeerResponse(
    val exists: Boolean,
    val versionTag: Uuid? = null,
)
