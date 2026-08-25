package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import kotlin.uuid.Uuid

data class VideoPlayerData(
    val fileId: Uuid,
    val driveId: Uuid,
    val payloadKey: String,
    val keyHeader: KeyHeader,
    val descriptorContent: String?,
    /** Set together for a followed identity's post: the bytes live on their drive, addressed by gtid. */
    val remoteOdinId: OdinId? = null,
    val globalTransitId: Uuid? = null,
)