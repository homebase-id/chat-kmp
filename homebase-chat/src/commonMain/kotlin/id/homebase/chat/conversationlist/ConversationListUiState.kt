package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.common.OdinId
import id.homebase.api.image.ImageMetadata
import id.homebase.chat.event.EventDescriptor
import id.homebase.api.video.VideoProcessingPhase
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.gallery.GalleryImage
import id.homebase.core.util.ScrollPosition
import id.homebase.core.widget.ReactionDisplayItem
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class ConversationListUiState(
    val activeConversations: ImmutableList<EnrichedConversationUiModel> = persistentListOf(),
    val conversationsContent: ConversationListContentState = ConversationListContentState.Loading,
    val archivedCount: Int = 0,
    val selectedConversationId: Uuid? = null,
    val filterByUnread: Boolean = false,
    val showArchived: Boolean = false,
    val isSearchActive: Boolean = false,
    val ownerSession: OwnerSession? = null,
    val downloadingFiles: Set<String> = emptySet(),
    val connectionStatus: AppConnectionStatus = AppConnectionStatus.Connecting,
    val driveIsSyncing: Boolean = false,
    val hasDriveError: Boolean = false,
    val uiDialog: ConversationListUiDialog? = null,
    val uiEvent: ConversationListUiEvent? = null,
    /** Non-null while a long-ish service op is in flight. Drives the full-screen
     *  scrim+spinner overlay so the user gets visible feedback that something is
     *  happening; otherwise the brief delay between tap and follow-up UI feels
     *  like the app is stuck. The string is the label the overlay should display:
     *  - `chat_conversation_deleting_in_progress` — plain delete
     *  - `chat_conversation_leaving_and_deleting_in_progress` — combined leave+delete
     *  - `chat_introduce_preflight_in_progress` — introduction preflight check
     *  Null means no overlay. Cleared on both success and error. */
    val inFlightOperationLabel: StringResource? = null,
    /** When non-null, the screen should pop the scaffold detail pane (i.e. close the
     *  open conversation). Use a dedicated state field rather than [uiEvent] because
     *  delete fires multiple events back-to-back (close + snackbar) and `uiEvent` is
     *  a single slot — successive sends overwrite each other and the close was being
     *  eaten by the snackbar. The screen calls [closeDetailPaneRequestConsumed] when
     *  it has handled the request. */
    val closeDetailPaneRequest: Uuid? = null,
)

@Immutable
data class MessageListUiState(
    val messages: ImmutableList<MessageListContentModel> = persistentListOf(),
    val decryptedFiles: ImmutableMap<DecryptedFileKey, String> = persistentMapOf(),
    val userDefaultReactions: ImmutableList<String> = persistentListOf(),
    val uploadProgress: ImmutableMap<Uuid, UploadStatus> = persistentMapOf(),
    val isLoadingMessages: Boolean = true,
    val scrollPosition: ScrollPosition? = null,
    val fullScreenOverlay: FullScreenOverlay? = null,
    val replyToMessage: MessageUiModel? = null,
    val battleTargetMessage: MessageUiModel? = null,
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResultMessageIds: List<Uuid> = emptyList(),
    val currentSearchResultIndex: Int = -1,
    val isEditingMessageId: Uuid? = null,
    val isEditingVersionTag: Uuid? = null,
    val ownerSession: OwnerSession? = null,
    val messageReactions: List<ReactionDisplayItem>? = null,
    val reactionDetailsMessageId: Uuid? = null,
    val isReactionsLoading: Boolean = false,
    val downloadingFiles: Set<String> = emptySet(),
    val recordingData: RecordingData? = null,
    val uiSheet: MessageListUiSheet? = null,
    val isSendingMessage: Boolean = false,
    val pendingOutgoing: ImmutableList<PendingOutgoingMessage> = persistentListOf(),
    val highlightedMessageId: Uuid? = null,
    /** True if more messages exist before the loaded window's first message.
     *  Drives the top loading-spinner row and the proximity hook's
     *  loadOlder trigger. */
    val hasOlderMessages: Boolean = false,
    /** True if more messages exist after the loaded window's last message.
     *  Set when the user opens around an anchor (centered window) or after
     *  trim discards the newer slice; drives the bottom spinner, the FAB's
     *  ScrollToLatest branch, and gates auto-follow on incoming messages. */
    val hasNewerMessages: Boolean = false,
    /** A loadOlder fetch is in flight; suppresses re-entry. */
    val isLoadingOlder: Boolean = false,
    /** A loadNewer fetch is in flight; suppresses re-entry. */
    val isLoadingNewer: Boolean = false,
    /** Set when the user taps a reply-preview that points at an Event message;
     *  the screen renders [id.homebase.chat.event.EventDetailDialog] keyed off
     *  this state. Null means no host-level event detail is open. */
    val replyTargetEventDetail: ReplyTargetEventDetail? = null,
    /** One-time "follow my own send to the bottom" token. Set to a fresh value
     *  whenever the user sends a message through the full-screen attachment
     *  editor (image/video/file): closing that overlay tears [ConversationContent]
     *  — and its layout-growth auto-follow effect — out of composition, so the
     *  effect restarts with `previousTotal = 0` and never observes the placeholder
     *  as growth. ConversationContent collects this token on (re)mount, animates to
     *  the latest item, and dispatches [ConversationListUiAction.ConsumeScrollToLatestRequest]
     *  to clear it. Null once consumed, so unrelated remounts (closing the image
     *  viewer) don't re-scroll. */
    val scrollToLatestRequest: Uuid? = null,
)

/**
 * Snapshot of an Event message resolved from a reply-preview tap. Carries
 * everything [id.homebase.chat.event.EventDetailDialog] needs without holding
 * a reference to the live MessageUiModel — the dialog's RSVP roster fetches
 * fresh reactions, and the counts are aggregated up-front so closing the
 * dialog doesn't re-render the host.
 */
@Immutable
data class ReplyTargetEventDetail(
    val descriptor: EventDescriptor,
    val messageId: Uuid,
    val conversationId: Uuid,
    val ownReactions: ImmutableList<String>,
    val reactionSummary: ReactionSummary?,
    val organizer: OdinId?,
)

@Immutable
data class PendingOutgoingMessage(
    val id: Uuid,
    val conversationId: Uuid,
    val text: String,
    val attachmentCount: Int,
    val sentAt: Instant,
    val replyPreview: ReplyPreview? = null,
)

sealed interface MessageListUiSheet {
    data class ConnectIdentities(
        val identities: List<OdinId>,
        val autoConnectStates: Map<OdinId, AutoConnectRowState> = emptyMap(),
    ) : MessageListUiSheet
    data class ForwardMessage(
        val message: MessageUiModel,
        val recipients: ImmutableList<RecipientGroupModel>,
        val selectedRecipients: ImmutableList<RecipientModel> = persistentListOf(),
        val searchTextState: TextFieldState = TextFieldState(),
    ) : MessageListUiSheet
}

sealed interface AutoConnectRowState {
    data object Connecting : AutoConnectRowState
    data object Succeeded : AutoConnectRowState
    data class Failed(val res: StringResource, val args: List<Any> = emptyList()) : AutoConnectRowState
}

sealed interface UploadStatus {
    data object Preparing : UploadStatus
    data class Processing(val progress: Float, val phase: VideoProcessingPhase = VideoProcessingPhase.COMPRESSING) : UploadStatus
    data object Sending : UploadStatus
    data class Uploading(val progress: Float) : UploadStatus
    data object Completed : UploadStatus
}

@Immutable
data class DecryptedFileKey(
    val fileId: Uuid,
    val payloadKey: String,
)

@Immutable
sealed interface ConversationListContentState {
    data object Loading : ConversationListContentState
    data object Empty : ConversationListContentState
    data class EmptySearch(val query: String) : ConversationListContentState
    data class Items(val list: ImmutableList<ConversationListContentModel>) :
        ConversationListContentState
}

@Immutable
sealed interface ConversationListContentModel {
    data class Conversation(val conversation: EnrichedConversationUiModel) : ConversationListContentModel
    data class Message(val message: MessageUiModel) : ConversationListContentModel
    data class Header(val resource: StringResource) : ConversationListContentModel
}

enum class MessageClusterPosition {
    ALONE,
    START,
    MIDDLE,
    END,
}

@Immutable
sealed class MessageListContentModel(val id: String) {
    data object Header : MessageListContentModel("header")
    data class Section(val date: LocalDate) : MessageListContentModel(date.toString())
    data class System(val text: String, val userDate: Instant, val index: Int) : MessageListContentModel("system-$index")
    data class Message(
        val message: MessageUiModel,
        val clusterPosition: MessageClusterPosition = MessageClusterPosition.ALONE,
    ) : MessageListContentModel(message.id.toString() + message.versionTag.toString() + message.hasMore)

    data object UnreadSeparator : MessageListContentModel("unread-separator")

    /** Spinner row at the top of the list while older messages are loading. */
    data object LoadingOlder : MessageListContentModel("loading-older")

    /** Spinner row at the bottom of the list while newer messages are loading. */
    data object LoadingNewer : MessageListContentModel("loading-newer")
}

@Immutable
data class RecipientGroupModel(
    val recipientType: RecipientType,
    val recipients: List<RecipientModel>
)

enum class RecipientType {
    You,
    Recents,
    Contacts,
    Groups
}

@Immutable
sealed class RecipientModel(val name: String) {
    data class Conversation(val conversation: EnrichedConversationUiModel) : RecipientModel(conversation.getDisplayName())
    data class Contact(val contact: ContactUiModel) : RecipientModel(contact.name)
}

@Immutable
sealed interface FullScreenOverlay {

    data class ViewMessageData(
        val messageId: Uuid,
        val title: String,
        val userDate: Instant,
        val content: String,
        val fileId: Uuid,
        val driveId: Uuid,
        val payloads: List<PayloadDescriptor>,
        val keyHeader: KeyHeader,
        val selectedPayloadKey: String,
    ) : FullScreenOverlay

    data class AttachmentData(
        val selected: Uuid,
        val conversationTitle: String,
        val conversationId: Uuid,
        val attachments: List<AttachmentPendingFile>,
    ) : FullScreenOverlay

    @Immutable
    data class VideoPlayerData(
        val fileId: Uuid,
        val driveId: Uuid,
        val payloadKey: String,
        val keyHeader: KeyHeader,
        val payload: PayloadDescriptor,
        val localFilePath: String? = null,
        val uploadMessageId: Uuid? = null,
    ) : FullScreenOverlay
}

sealed class AttachmentPendingFile(val attachmentId: Uuid) {
    /**
     * @param metadata EXIF / image-file metadata, populated asynchronously after
     *   the picker callback. Null until the read finishes (or the read fails).
     * @param includeLocation Per-image opt-in for embedding GPS coordinates in the
     *   eventual post. Date / camera / dimensions always travel when known —
     *   only the privacy-sensitive bit is gated. Defaults false.
     */
    data class FileImage(
        val id: Uuid,
        val file: PlatformFile,
        val metadata: ImageMetadata? = null,
        val includeLocation: Boolean = false,
    ) : AttachmentPendingFile(id)
    data class FileVideo(
        val id: Uuid,
        val file: PlatformFile,
        val thumbnailBytes: ByteArray? = null,
        val durationMs: Long? = null,
        val trimStartMs: Long? = null,
        val trimEndMs: Long? = null,
    ) : AttachmentPendingFile(id)
    data class File(val id: Uuid, val file: PlatformFile) : AttachmentPendingFile(id)
    data class Gallery(val id: Uuid, val image: GalleryImage) : AttachmentPendingFile(id)
    data class Audio(val id: Uuid, val audioFile: PlatformFile, val waveformFile: PlatformFile?, val lengthSeconds: Int) : AttachmentPendingFile(id)
}


@Immutable
data class RecordingData(
    val file: PlatformFile,
    val conversationId: Uuid,
    val isProcessing: Boolean = false,
)