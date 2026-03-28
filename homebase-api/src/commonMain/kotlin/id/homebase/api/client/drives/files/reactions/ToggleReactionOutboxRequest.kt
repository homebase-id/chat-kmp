package id.homebase.api.client.drives.files.reactions

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ToggleReactionOutboxRequest(
    val driveId: Uuid,
    val fileId: Uuid,
    val reaction: String,
    val recipients: List<OdinId>,
)
