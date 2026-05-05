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
    val lastMessage: String,
    val latestMessageTimestamp: Instant, // Timestamp of the last message in this convo
    val unreadCount: Int = 0,
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
    val isGroup: Boolean = false,
    val isLegacyGroup: Boolean = false,
    val exitedAt: Instant? = null,
    /** Server-stamped last-modified time of the conversation file (fileMetadata.updated). */
    val fileUpdated: Instant = Instant.fromEpochMilliseconds(0),
) {
    fun isCurrentUserAdmin(odinId: OdinId): Boolean {
        return admins.contains(odinId)
    }

    /** True when this conversation is a group — either tagged or legacy (>2 participants). */
    val isGroupConversation: Boolean
        get() = isGroup || isLegacyGroup

    val isWithSelf: Boolean
        get() = id == ChatProtocol.ConversationWithYourselfId


    fun getDisplayName(): String {
        if (isGroupConversation) return name

        if (name.isEmpty() || name.isBlank()) {
            return participants.firstOrNull()?.domainName ?: ""
        }
        return name
    }

    companion object {
        /**
         * Patches the conversation's last-message fields if [msg] is at least
         * as recent as the current [latestMessageTimestamp].
         *
         * The "recency" comparison is driven by [latestTimestampOverrideMs]
         * when the caller knows the SQL-side userDate (e.g. from
         * `selectAllConversationPlusLastMessage`'s `msgUserDate` projection,
         * or from `HomebaseFile.sqlUserDateMs()` for files just written to
         * `DriveMainIndex`). This avoids the clamp inside
         * `ChatMessageStream.mapToMessageData`, which can drop
         * `MessageUiModel.userDate` below the SQL column when a peer's
         * `appData.userDate` exceeds `metadata.transitCreated` — and that
         * mismatch is what stuck Monica's unread badge at 1: the unread SQL
         * counted the row using the SQL userDate, but every read code path
         * advanced `lastReadTime` to the clamped (smaller) value.
         *
         * Falls back to `msg.userDate` when no override is supplied, matching
         * legacy behaviour for callers that haven't been migrated.
         */
        fun ConversationUiModel.updateWithLatestMessage(
            msg: MessageUiModel,
            activeUserDomain: OdinId?,
            latestTimestampOverrideMs: Long? = null,
        ): ConversationUiModel {
            val candidate = latestTimestampOverrideMs
                ?.let { Instant.fromEpochMilliseconds(it) }
                ?: msg.userDate
            if (candidate >= latestMessageTimestamp) {
                return this.copy(
                    lastMessage = msg.content.truncateToCodePoints(40),
                    latestMessageTimestamp = candidate,
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
