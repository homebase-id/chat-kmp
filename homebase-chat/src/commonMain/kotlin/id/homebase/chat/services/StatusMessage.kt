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
    ConversationStarted,

    /** Broadcast by the original author of a group when the user clicks "Heal Group".
     *  Carries canonical group identity (originalAuthor, versionTag, title, participants,
     *  admin file versionTag) so recipients can detect a divergent local copy and
     *  hard-delete it on their own server before the next push lands. */
    GroupHealRequested,

    /** Local-only marker written by the receive-side heal handler when it actually
     *  hard-deleted broken local copies. Surfaces the cleanup to the user instead of
     *  letting it happen silently. Never sent over peer — the sender enforces this by
     *  passing `recipientOverride = emptyList()` (see GroupHealService.handleIncomingHealRequest);
     *  a peer-authored copy of this status must NOT be rendered (see MessageMapper). */
    GroupHealLocalCleanup,
}