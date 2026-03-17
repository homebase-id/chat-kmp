package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class ConversationUiModel(
    // TODO: Move the data objects / classes into Conversation.kt ?
    val id: Uuid,
    val name: String,
    var lastMessage: String,
    var timestamp: Instant, // Timestamp of the last message in this convo
    var unreadCount: Int = 0,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val avatarTiny: EmbeddedThumb?,
    val participants: List<OdinId> = listOf(),
    val isPinned: Boolean = false,
    val lastRead: Instant,
    val avatarModel: ConversationAvatarModel,
    var lastMessageDeliveryStatus: Int? = null,
    var lastMessageIsDeleted: Boolean = false,
    var lastMessageFirstPayload: PayloadDescriptor? = null,
    var lastMessageHasMultiplePayloads: Boolean = false,
    var lastMessageIsFromActiveUser: Boolean = false,
    val admins: Set<OdinId>,
) {
    fun isCurrentUserAdmin(odinId: OdinId): Boolean {
        return admins.contains(odinId)
    }

    /** True when there are multiple participants (i.e., a group conversation). */
    val isGroupConversation: Boolean
        get() = participants.size > 2

    val isWithSelf: Boolean
        get() = id == ChatProtocol.ConversationWithYourselfId

    fun updateWithLatestMessage(msg: MessageUiModel, activeUserDomain: OdinId?) {
        // TODO: Should we also increase unread count here if it's a new message?
        if (msg.created >= timestamp) {
            lastMessage = msg.content.truncateToCodePoints(40)
            timestamp = msg.created
            lastMessageDeliveryStatus = msg.messageAppData.deliveryStatus
            lastMessageIsDeleted = msg.isDeleted
            lastMessageFirstPayload = msg.payloads?.firstOrNull()
            lastMessageHasMultiplePayloads = (msg.payloads?.size ?: 0) > 1
            lastMessageIsFromActiveUser = msg.isAuthoredBy(activeUserDomain)
        }
    }

    fun getDisplay(): String {
        if (name.isEmpty() || name.isBlank()) {
            return participants.first().domainName
        }
        return name
    }

    fun missingConnections(): List<OdinId> {
        // TODO - get participants list of members you are not connected to
        return listOf()
    }
}
