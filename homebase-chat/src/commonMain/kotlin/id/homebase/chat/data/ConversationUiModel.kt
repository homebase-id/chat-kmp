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
    val lastMessageDeliveryStatus: Int? = null,
    val lastMessageIsDeleted: Boolean = false,
    val lastMessageFirstPayload: PayloadDescriptor? = null,
    val lastMessageHasMultiplePayloads: Boolean = false,
    val lastMessageIsFromActiveUser: Boolean = false,
    val admins: Set<OdinId>,
    val conversationState: ConversationState = ConversationState.Active,
) {
    fun isCurrentUserAdmin(odinId: OdinId): Boolean {
        return admins.contains(odinId)
    }

    /** True when there are multiple participants (i.e., a group conversation). */
    val isGroupConversation: Boolean
        get() = participants.size > 2

    val isWithSelf: Boolean
        get() = id == ChatProtocol.ConversationWithYourselfId


    fun getDisplayName(): String {
        if (name.isEmpty() || name.isBlank()) {
            return participants.firstOrNull()?.domainName ?: ""
        }
        return name
    }

    companion object {
        fun ConversationUiModel.updateWithLatestMessage(
            msg: MessageUiModel,
            activeUserDomain: OdinId?
        ): ConversationUiModel {
            // TODO: Should we also increase unread count here if it's a new message?
            if (msg.userDate >= timestamp) {
                return this.copy(
                    lastMessage = msg.content.truncateToCodePoints(40),
                    timestamp = msg.userDate,
                    lastMessageDeliveryStatus = msg.messageAppData.deliveryStatus,
                    lastMessageIsDeleted = msg.isDeleted,
                    lastMessageFirstPayload = msg.payloads?.firstOrNull(),
                    lastMessageHasMultiplePayloads = (msg.payloads?.size ?: 0) > 1,
                    lastMessageIsFromActiveUser = msg.isAuthoredBy(activeUserDomain),
                )
            }
            return this
        }
    }
}
