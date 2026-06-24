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

    /** Posted by the poll creator when they close a poll via [id.homebase.chat.poll.PollDetailDialog].
     *  Carries [StatusMessageData.pollQuestion] and [StatusMessageData.pollMessageId] so
     *  the system line can reference the question. */
    PollEnded,

    /** Sent by a user (the message's `originalAuthor`) when they designate the recipient as one of
     *  their emergency contacts. Renders a system line ("X added you as an emergency contact") AND
     *  drives a receive-side side-effect (wired in AppModule) that records — in the recipient's own
     *  contact app-data — that the recipient can now locate the SENDER (core `setICanLocate`). */
    EmergencyContactDesignated,

    /** Mirror of [EmergencyContactDesignated], sent when the author removes the recipient from their
     *  emergency circle. Renders "X removed you as an emergency contact" AND drives a receive-side
     *  side-effect that clears the recipient's can-locate flag for the SENDER (core `clearICanLocate`). */
    EmergencyContactRevoked,
}
