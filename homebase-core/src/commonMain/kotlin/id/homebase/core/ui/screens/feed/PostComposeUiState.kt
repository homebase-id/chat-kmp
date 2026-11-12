package id.homebase.core.ui.screens.feed

import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostAudience
import kotlin.uuid.Uuid

data class ChannelOption(val id: Uuid, val name: String)

data class CircleOption(val id: String, val name: String)

data class PostComposeUiState(
    val caption: String = "",
    val attachments: List<AttachmentPendingFile> = emptyList(),
    val audience: PostAudience = PostAudience.Public,
    val circles: List<CircleOption> = emptyList(),
    val selectedCircleIds: Set<String> = emptySet(),
    val channels: List<ChannelOption> = emptyList(),
    val selectedChannelId: Uuid = FeedProtocol.PublicChannelDriveAlias,
    val selfOdinId: OdinId? = null,
    val selfName: String? = null,
    val isPosting: Boolean = false,
) {
    val canPost: Boolean
        get() = (caption.isNotBlank() || attachments.isNotEmpty()) &&
            !isPosting &&
            (audience != PostAudience.Circles || selectedCircleIds.isNotEmpty())
}
