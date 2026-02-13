package id.homebase.core.widget

import androidx.compose.runtime.Immutable
import id.homebase.api.client.websockets.InternalDriveFileId
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import kotlin.uuid.Uuid

@Immutable
data class EmojiReaction(
    val messageId: Uuid,
    val emoji: String,
    val odinId: OdinId,
    val created: UnixTimeUtc
)