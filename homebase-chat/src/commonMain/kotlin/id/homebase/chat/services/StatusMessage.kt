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

    /** Visible-only twin of [EmergencyContactDesignated], sent immediately after it in the same
     *  conversation. [EmergencyContactDesignated] itself never reaches the receiver's chat history —
     *  [id.homebase.core.contactbook.EmergencyContactReceiveService] soft-deletes it on receipt so a
     *  re-delivery can't re-apply a stale designation. This twin carries no side-effect and is never
     *  matched by [id.homebase.chat.services.convo.ConversationStream]'s designation dispatcher, so it
     *  is never consumed — it renders the normal "X added you as an emergency contact" status line and
     *  stays in history like any other status message. */
    EmergencyContactDesignatedNotice,

    /** Visible-only twin of [EmergencyContactRevoked] — see [EmergencyContactDesignatedNotice]. */
    EmergencyContactRevokedNotice,

    /** Sent by an emergency contact (the message's `originalAuthor`) when they activate the
     *  emergency locate function against the recipient and retrieve their location history.
     *  Carries [StatusMessageData.emergencyLocateExplanation] (the requester's justification),
     *  [StatusMessageData.emergencyLocateWindowHours], and optionally
     *  [StatusMessageData.emergencyLocateEmbargoUntilMs] — the "Ambush" flag: while
     *  `now < embargoUntilMs` the RECIPIENT's client does not render this message
     *  (MessageMapper returns null), so a captor inspecting the victim's phone sees nothing
     *  for 24h. Render-only on receive — no side-effect handler.
     *
     *  Note: the recipient's server independently fires TemporalDriveAccessedNotification the
     *  moment the requester reads the drive (odin-core, 1h-throttled, no justification text) —
     *  the embargo cannot suppress that. A deferred-alert option on the server temporal API is
     *  a possible future extension. */
    EmergencyLocateRequested,
}
