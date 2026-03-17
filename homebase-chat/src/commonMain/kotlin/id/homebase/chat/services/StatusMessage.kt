package id.homebase.chat.services

import kotlinx.serialization.Serializable

@Serializable
enum class StatusMessage() {
    ConversationTitleUpdated,
    ConversationPhotoUpdated,
    ConversationMemberAdded,
    ConversationMemberRemoved,
    ConversationAdminAdded,
    ConversationAdminRemoved,
    GroupConversationStarted,

    ConversationMemberLeft
}