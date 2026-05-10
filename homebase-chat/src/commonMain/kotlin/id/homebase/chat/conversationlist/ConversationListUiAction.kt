package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.services.renderer.PayloadRenderer
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.gallery.GalleryImage
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.Uuid

sealed interface ConversationListUiAction {
    data class ConversationClicked(val conversationId: Uuid, val messageId: Uuid?) :
        ConversationListUiAction

    data object BackClicked : ConversationListUiAction
    data object ArchiveBackClicked : ConversationListUiAction
    data object SearchClicked : ConversationListUiAction
    data object SearchBackClicked : ConversationListUiAction
    data object SearchMessagesClicked : ConversationListUiAction
    data object SearchMessagesBackClicked : ConversationListUiAction
    data object SearchMessagesNavigateNext : ConversationListUiAction
    data object SearchMessagesNavigatePrevious : ConversationListUiAction
    data object NewConversationClicked : ConversationListUiAction
    data object ClearSelection : ConversationListUiAction

    /** The screen has handled [ConversationListUiState.closeDetailPaneRequest] (popped
     *  the scaffold detail pane); clear it so it doesn't fire again on next recompose. */
    data object CloseDetailPaneRequestConsumed : ConversationListUiAction

    /** Combined leave-and-delete for a group conversation the user is still in.
     *  Service-side: calls [ConversationService.leaveGroup] then
     *  [ConversationService.deleteConversation] in sequence so the user does not
     *  have to do both in two separate UI flows. */
    data class ConfirmLeaveAndDeleteConversation(val conversationId: Uuid) : ConversationListUiAction

    /** Three outcomes from the [ConversationListUiDialog.IntroducePreflight]
     *  dialog. The dialog presents the user with non-Ready recipients and these
     *  actions are how they choose what to do. The original `message` is passed
     *  through so the VM doesn't have to reach back into UI state for it. */
    data class IntroduceSendAnyway(val conversationId: Uuid, val message: String) : ConversationListUiAction

    /** Send only to the recipients that came back as
     *  [id.homebase.api.client.connections.IntroductionPreflightStatus.Ready]
     *  in the preflight result; skip the others. */
    data class IntroduceSendReadyOnly(
        val conversationId: Uuid,
        val readyRecipients: List<id.homebase.api.common.OdinId>,
        val message: String,
    ) : ConversationListUiAction

    /** Dismiss the preflight dialog without sending. Equivalent to closing the dialog. */
    data object IntroduceCancel : ConversationListUiAction
    data object FilterByUnreadClicked : ConversationListUiAction
    data object ClearFilterByUnreadClicked : ConversationListUiAction
    data object ShowArchivedMessagesClicked : ConversationListUiAction
    data class ConnectIdentities(val identities: List<OdinId>) : ConversationListUiAction
    data class ConnectToIdentity(val odinId: OdinId) : ConversationListUiAction
    data class AutoConnect(val odinId: OdinId) : ConversationListUiAction
    data class OpenConnectionRequestInOwnerConsole(val odinId: OdinId) : ConversationListUiAction
    data class OpenSendConnectionRequestDialog(val odinId: OdinId) : ConversationListUiAction
    data object DismissSheet : ConversationListUiAction
    data class SendMessage(
        val conversationId: Uuid,
        val payloadRenderers: List<PayloadRenderer> = emptyList(),
    ) : ConversationListUiAction

    data class SendFile(
        val conversationId: Uuid,
        val message: String,
        val attachments: List<AttachmentPendingFile>
    ) : ConversationListUiAction

    data class AttachGalleryItem(
        val conversationId: Uuid,
        val files: List<GalleryImage>,
    ) : ConversationListUiAction

    data class AttachPlatformFile(
        val conversationId: Uuid,
        val files: List<PlatformFile>,
        val isImage: Boolean = false,
    ) : ConversationListUiAction

    data class UnAttachFile(
        val conversationId: Uuid,
        val id: Uuid,
    ) : ConversationListUiAction

    data class ShareMedia(val messageId: Uuid, val payloadKey: String) : ConversationListUiAction

    data class ShareMessage(val message: MessageUiModel) : ConversationListUiAction
    data class DownloadMedia(val messageId: Uuid, val payloadKey: String) : ConversationListUiAction
    data class DownloadVideoMedia(
        val fileId: Uuid,
        val payloadKey: String,
        val keyHeader: KeyHeader,
        val payload: PayloadDescriptor,
    ) : ConversationListUiAction

    data class SaveFile(val file: AttachmentPendingFile) : ConversationListUiAction

    data class MediaClicked(val message: MessageUiModel, val payloadKey: String) :
        ConversationListUiAction

    data class ShowMoreClicked(val conversationId: Uuid, val messageId: Uuid) :
        ConversationListUiAction

    data object CloseFullScreenOverlay : ConversationListUiAction

    /**
     * Persist where the user is reading in this conversation. The anchor is
     * the uniqueId of the topmost visible message (resolved by the pane from
     * its [androidx.compose.foundation.lazy.LazyListState]); the offset is
     * pixel offset within that anchor item. On next open we look the anchor
     * up against the freshly-loaded message list to recover the index — that
     * keeps the saved position meaningful even when new messages arrived
     * between sessions or when paging eventually shifts indices on prepend.
     *
     * `anchorMessageId == null` means the pane couldn't resolve the visible
     * window to a real message (empty list, all-non-message rows) and the VM
     * should ignore the dispatch.
     */
    data class SaveScrollPosition(
        val conversationId: Uuid,
        val anchorMessageId: Uuid?,
        val firstVisibleItemScrollOffset: Int,
    ) : ConversationListUiAction

    data object ClearScrollTrigger : ConversationListUiAction
    data object ClearHighlightedMessage : ConversationListUiAction

    data class ShowConversationSettings(val conversation: ConversationUiModel) :
        ConversationListUiAction

    data class DeleteConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ConfirmDeleteConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ArchiveConversation(val conversationId: Uuid) : ConversationListUiAction

    data class UnarchiveConversation(val conversationId: Uuid) : ConversationListUiAction


    data class ClearConversation(val conversationId: Uuid) : ConversationListUiAction

    data class IntroduceEveryone(val conversationId: Uuid) : ConversationListUiAction
    data class ShowContactInfo(val odinId: String) : ConversationListUiAction
    data class ShowMessageInfo(val message: MessageUiModel) : ConversationListUiAction
    data class ReplyToMessage(val message: MessageUiModel) : ConversationListUiAction
    data class BattleDiceRoll(val message: MessageUiModel) : ConversationListUiAction
    data object CancelBattleDiceRoll : ConversationListUiAction
    data class ForwardMessage(val message: MessageUiModel) : ConversationListUiAction
    data class ForwardMessageSend(val message: MessageUiModel, val recipients: List<RecipientModel>) : ConversationListUiAction
    data class ForwardMessageSelectRecipient(val recipient: RecipientModel) : ConversationListUiAction
    data object CancelReplyToMessage : ConversationListUiAction
    data class EditMessage(val messageId: Uuid, val versionTag: Uuid, val ignoreDraft: Boolean) :
        ConversationListUiAction

    data object EditMessageSave : ConversationListUiAction
    data object CancelEditMessage : ConversationListUiAction
    data class DeleteMessage(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForMe(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForEveryone(val messageId: Uuid) : ConversationListUiAction

    data class TogglePinConversation(val conversationId: Uuid) : ConversationListUiAction
    data class AcceptRejoin(val conversationId: Uuid) : ConversationListUiAction
    data class DeclineRejoin(val conversationId: Uuid) : ConversationListUiAction
    data class MarkAsRead(val conversationId: Uuid, val messageIds: List<Uuid>? = null) : ConversationListUiAction
    data class ToggleReaction(val conversationId: Uuid, val messageId: Uuid, val reaction: String) :
        ConversationListUiAction

    data class ShowReactionDetails(val messageId: Uuid) : ConversationListUiAction
    data class DecryptFile(val messageId: Uuid, val payloadKey: String) : ConversationListUiAction
    data class ScrollToMessageId(val messageId: Uuid) : ConversationListUiAction
    data object HideReactionDetails : ConversationListUiAction
    data class StartRecording(val conversationId: Uuid) : ConversationListUiAction
    data object StopRecording : ConversationListUiAction
    data object CancelRecording : ConversationListUiAction
    data object ShowRecordingHelp : ConversationListUiAction

    data class AttachClipboardImage(
        val conversationId: Uuid,
        val imageBytes: ByteArray,
    ) : ConversationListUiAction

    /** User tapped the crop button on an image attachment. */
    data class RequestCropAttachment(
        val conversationId: Uuid,
        val attachmentId: Uuid,
    ) : ConversationListUiAction

    /** Cropper finished — replace the attachment with the cropped bytes. */
    data class ApplyCropResult(
        val conversationId: Uuid,
        val attachmentId: Uuid,
        val croppedBytes: ByteArray,
    ) : ConversationListUiAction

    /** User tapped the draw button on an image attachment. */
    data class RequestDrawAttachment(
        val conversationId: Uuid,
        val attachmentId: Uuid,
    ) : ConversationListUiAction

    /** Draw editor finished — replace the attachment with the painted bytes. */
    data class ApplyDrawResult(
        val conversationId: Uuid,
        val attachmentId: Uuid,
        val paintedBytes: ByteArray,
    ) : ConversationListUiAction

    /** Inline trim scrubber moved — store the chosen range, or null to clear. */
    data class ApplyTrimResult(
        val conversationId: Uuid,
        val attachmentId: Uuid,
        val trimStartMs: Long?,
        val trimEndMs: Long?,
    ) : ConversationListUiAction

    data class BlockUser(val authorOdinId: OdinId) : ConversationListUiAction
    data object ReportContent : ConversationListUiAction
}
