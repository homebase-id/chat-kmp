package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.content.MessageContent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class MessageUiModel(

    val id: Uuid, // uniqueId
    /** GlobalTransitId of the payload - same across all recipients */
    val globalTransitId: Uuid?,
    /** FileId of the payload - different for each server */
    val fileId: Uuid, // fileId
    val conversationId: Uuid, // groupId
    val content: String, // the message
    val userDate: Instant, // User-specified message timestamp (from appData.userDate)
    val modified: Instant?, // When the message was last modified
    val created: Instant, // Server-side creation timestamp
    val originalAuthor: OdinId?,
    /**
     * The wire-level sender of this message file (the identity that uploaded
     * it to the chat drive). Equal to [originalAuthor] for non-forwarded
     * messages; differs when the message is a forward — sender is the
     * forwarder, [originalAuthor] is whoever first wrote the content. Use
     * this (not [originalAuthor]) for the 1:1 XorId test.
     */
    val sender: OdinId?,
    val displayName: String,

    /** The timestamp when this file was marked as read for current user's identity */
    val localReadTimestamp: UnixTimeUtc? = null,

    /** Bare emoji strings the current identity has applied to this
     *  message. The wire format under `localAppData.localReactions` is
     *  JSON `{"emoji":"X"}` per entry; `ChatMessageStream` decodes those
     *  to plain emoji so the UI can compare against `reactionPreview`
     *  entries by simple string match. */
    val ownReactions: ImmutableList<String> = persistentListOf(),

    val isEdited: Boolean = false,
    val messageAppData: MessageAppData, // TODO: Should we copy these up into the message?
    val reactionPreview: ReactionSummary?,
    /** Tiny blurry preview thumbnail of the file */
    val previewThumbnail: EmbeddedThumb?,
    /** List of payload descriptors with metadata */
    val payloads: ImmutableList<PayloadDescriptor>?,

    val keyHeader: KeyHeader,
    val isDeleted: Boolean = false,
    val versionTag: Uuid,

    /** When true the item exists in the local-sync database only, most likey because it
     * was optimistically written but not yet sent */
    val isPendingSend: Boolean,

    /**
     * True when this message's outbox send was permanently dropped
     * (`ChatProtocol.isFailedSendTag`). Distinct from a `Failed`
     * [MessageAppData.deliveryStatus] caused by a server-side per-recipient
     * failure: this one means the local send never settled. Drives the
     * "Failed to send" status + retry affordance in Message Info.
     */
    val isFailedSend: Boolean = false,

    /** Indicates if this was created by the app/system and should be rendered differently **/
    val isStatusMessage: Boolean = false,

    /**
     * Typed rich-content parsed from `appData.dataType` + `appData.content` (event
     * today; poll and doodle later). Null for plain text + media messages — those
     * keep their existing rendering path. When non-null, [content] is set to a
     * short display label suitable for notifications and the conversation-list
     * preview.
     */
    val messageContent: MessageContent? = null,

    val hasMore: Boolean
) {
    fun isAuthoredBy(domain: OdinId?): Boolean = (originalAuthor == domain)

    // null author == self (the server doesn't stamp originalAuthor on own messages).
    fun isFromActiveUser(activeDomain: OdinId?): Boolean =
        originalAuthor == null || originalAuthor == activeDomain

    /**
     * True when this message lives in a 1:1 conversation between [self] and
     * [sender] — i.e. `XorId(self, sender) == conversationId`.
     *
     * Uses [sender], NOT [originalAuthor]: the latter is content provenance
     * and may differ from sender for forwarded messages, which would
     * otherwise misclassify a forwarded 1:1 as a group.
     *
     * Returns false when [sender] is null. Callers wanting a different
     * fallback should test [sender] explicitly.
     */
    fun isOneToOne(self: OdinId): Boolean =
        sender != null && XorIdUtil.isOneToOneWithSender(
            self = self,
            sender = sender,
            messageGroupId = conversationId,
        )
}