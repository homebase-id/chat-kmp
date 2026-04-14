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

    ConversationMemberLeft,
    ConversationMemberDeclinedRejoin,

    /** Posted when a 1:1 conversation is freshly created — currently emitted by the
     *  connection-request send flow so both sides see an opening status entry. */
    ConversationStarted
}