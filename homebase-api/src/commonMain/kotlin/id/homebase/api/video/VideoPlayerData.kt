package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlin.uuid.Uuid

data class VideoPlayerData(
    val fileId: Uuid,
    val driveId: Uuid,
    val payloadKey: String,
    val keyHeader: KeyHeader,
    val descriptorContent: String?,
)