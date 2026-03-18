package id.homebase.chat.services.convo

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import kotlin.uuid.Uuid
import kotlin.time.Instant

@Immutable
data class EnrichedConversationUiModel(
    val conversation: ConversationUiModel,
    val participants: List<ContactUiModel>,
    val missingConnections: List<OdinId>,
) {

    // ===== Passthroughs (BACKWARD COMPAT) =====

    val id: Uuid
        get() = conversation.id

    val name: String
        get() = conversation.name

    val lastMessage: String
        get() = conversation.lastMessage

    val timestamp: Instant
        get() = conversation.timestamp

    val unreadCount: Int
        get() = conversation.unreadCount

    val avatarInitials: String
        get() = conversation.avatarInitials

    val avatarUrl: String
        get() = conversation.avatarUrl

    val avatarTiny get() = conversation.avatarTiny

    val isPinned: Boolean
        get() = conversation.isPinned

    val lastRead: Instant
        get() = conversation.lastRead

    val avatarModel get() = conversation.avatarModel

    val lastMessageDeliveryStatus: Int?
        get() = conversation.lastMessageDeliveryStatus

    val lastMessageIsDeleted: Boolean
        get() = conversation.lastMessageIsDeleted

    val lastMessageFirstPayload get() = conversation.lastMessageFirstPayload

    val lastMessageHasMultiplePayloads: Boolean
        get() = conversation.lastMessageHasMultiplePayloads

    val lastMessageIsFromActiveUser: Boolean
        get() = conversation.lastMessageIsFromActiveUser

    val admins get() = conversation.admins

    val conversationState get() = conversation.conversationState

    val isGroupConversation: Boolean
        get() = conversation.isGroupConversation

    val isWithSelf: Boolean
        get() = conversation.isWithSelf

    fun isCurrentUserAdmin(odinId: OdinId): Boolean =
        conversation.isCurrentUserAdmin(odinId)

    fun getDisplay(): String =
        conversation.getDisplay()

}