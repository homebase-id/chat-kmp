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
import id.homebase.core.image.HomebaseImageData
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
    /**
     * Deadline of the chat-list top-bar sharing pin, else null: the later of my outgoing shares
     * (#816) and anyone sharing their live location with me (#1012, quantized live-relay
     * freshness). Computed by `globalLiveSharePinUntilMs`. Raw deadline: the pin composable derives
     * visibility from `now < untilMs` with its own ticker, so a stale past-value after expiry is
     * harmless until the next roster/relay emission. See
     * [MessageListUiState.liveSharePinInConversationUntilMs] for the per-conversation variant.
     */
    val liveSharePinAnyUntilMs: Long? = null,
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
    val isConnected: Boolean = true,
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
    /** Non-null while the sticker-tap bottom sheet is open; null otherwise. */
    val stickerOptionsSheet: StickerOptionsSheetState? = null,
    val downloadingFiles: Set<String> = emptySet(),
    val recordingData: RecordingData? = null,
    val uiSheet: MessageListUiSheet? = null,
    val isSendingMessage: Boolean = false,
    val pendingOutgoing: ImmutableList<PendingOutgoingMessage> = persistentListOf(),
    val highlightedMessageId: Uuid? = null,
    val pinnedMessages: ImmutableList<MessageUiModel> = persistentListOf(),
    val currentPinIndex: Int = 0,
    /** True if more messages exist before the loaded window's first message.
     *  Drives the top loading-spinner row and the proximity hook's
     *  loadOlder trigger. */
    val hasOlderMessages: Boolean = false,
    /** True if more messages exist after the loaded window's last message.
     *  Set when the user opens around an anchor (centered window) or after
     *  trim discards the newer slice; drives the bottom spinner, the FAB's
     *  ScrollToLatest branch, and gates auto-follow on incoming messages. */
    val hasNewerMessages: Boolean = false,
    /**
     * When MY live share FULLY covers this conversation's recipients: the absolute end of that
     * coverage, else null. While set (and in the future), location bubbles hide their "share
     * live location" offers — no invitations to start a share that's already running (#966
     * follow-up). Deliberately a different predicate from [liveSharePinInConversationUntilMs]
     * (ANY-coverage pin) — don't unify them.
     */
    val ownLiveShareUntilMs: Long? = null,
    /**
     * Deadline of the conversation top-bar sharing pin, else null: the later of my outgoing share
     * to ANY participant of this conversation (#816 — one covered group member suffices, while
     * [ownLiveShareUntilMs] / FULL coverage stays null) and a participant sharing their live
     * location with me (#1012, quantized live-relay freshness). Computed by
     * `conversationLiveSharePinUntilMs`. Raw deadline; the pin composable handles expiry itself.
     */
    val liveSharePinInConversationUntilMs: Long? = null,
    /** A loadOlder fetch is in flight; suppresses re-entry. */
    val isLoadingOlder: Boolean = false,
    /** A loadNewer fetch is in flight; suppresses re-entry. */
    val isLoadingNewer: Boolean = false,
    /** Set when the user taps a reply-preview that points at an Event message;
     *  the screen renders [id.homebase.chat.event.EventDetailDialog] keyed off
     *  this state. Null means no host-level event detail is open. */
    val replyTargetEventDetail: ReplyTargetEventDetail? = null,
    /** One-time "follow my own send to the bottom" token — a user's own send must
     *  always scroll the list to show it (#995). Armed with the new message id by
     *  every send arm in MessageActionsHandler (addMessage / replyToMessage /
     *  addMessageWithFiles); a text/reply/location send adds no placeholder, so
     *  ConversationContent waits until the id is actually present in the merged
     *  list (see [resolveOwnSendFollowTarget]) before animating to the latest item,
     *  then dispatches [ConversationListUiAction.ConsumeScrollToLatestRequest] to
     *  clear it. Null once consumed, so unrelated remounts (closing the image
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

/**
 * State for the sticker-tap bottom sheet (WhatsApp-style). Opening a sticker bubble routes
 * here instead of the fullscreen viewer; the sheet offers "Add to my stickers" /
 * "Remove from my stickers" (by [isAlreadySaved]) and "Save to device".
 *
 * [sourceFileId] is the chat message's fileId — used both to remove the saved copy created
 * from this message and to recompute "already saved" when the library changes while the sheet
 * is open. [payloadKey] addresses the sticker image payload for the device-export path.
 * [stickerImage] is the render descriptor for the larger in-sheet preview (built once when the
 * sheet opens, reusing the same drive/keyHeader the bubble used).
 */
@Immutable
data class StickerOptionsSheetState(
    val messageId: Uuid,
    val payloadKey: String,
    val sourceFileId: Uuid,
    val isAlreadySaved: Boolean,
    val stickerImage: HomebaseImageData,
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

    /** Full list of pinned messages for the open conversation (the "see all" panel). */
    data object PinnedMessages : MessageListUiSheet
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
    // NOTE: do NOT add message.hasMore to this key. When "Read more" downloads a
    // spilled body, hasMore flips true->false; if it were in the key the LazyColumn
    // item would be recreated mid-tap, resetting the bubble's bodyExpanded state and
    // re-showing "Read more" on the now-full body (a double "Read more"). The body
    // already refreshes on download because textState is remember(message.content)
    // (MessageBubbleRaw) — the historical hasMore-in-key reset (commit 297c4830) was
    // only needed back when RichTextState was keyless.
    ) : MessageListContentModel(message.id.toString() + message.versionTag.toString())

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
        /**
         * Whether the payloads are encrypted. Chat media always is (hence the default);
         * a public feed post ships plaintext with no per-payload IV, which the viewer
         * must render rather than mistake for a still-uploading attachment.
         */
        val isEncrypted: Boolean = true,
        /**
         * Author identity to read the payloads from **over peer**, when the bytes live on a
         * followed identity's drive rather than the local one (a feed post from someone you
         * follow). Null for local media — chat and own posts. Requires [globalTransitId];
         * see [id.homebase.core.image.HomebaseImageData.isOverPeer].
         */
        val remoteOdinId: OdinId? = null,
        /** Cross-identity id of the file on [remoteOdinId]'s drive, required for an over-peer read. */
        val globalTransitId: Uuid? = null,
    ) : FullScreenOverlay

    data class AttachmentData(
        val selected: Uuid,
        val conversationTitle: String,
        val conversationId: Uuid,
        val attachments: List<AttachmentPendingFile>,
        /**
         * Attachment ids whose background removal is currently running. The editor's
         * "Remove background" tool shows a spinner and disables itself for these, so the
         * multi-hundred-ms (first call: model download / warm-up) on-device segmentation
         * pass doesn't read as a dead button. Keyed by id so the spinner follows the
         * specific image across pager swipes. Added/removed (in a `finally`) by
         * [AttachmentHandler.handleRemoveBackground].
         */
        val processingAttachmentIds: Set<Uuid> = emptySet(),
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
        /**
         * Whether the payload is encrypted. Chat video always is (hence the default); a public
         * feed post's video is plaintext and carries no per-payload IV, which the paused-state
         * poster must not mistake for a not-yet-uploaded payload. Playback itself never reads
         * the IV — the drive provider decrypts (or doesn't) off the response header.
         */
        val isEncrypted: Boolean = true,
    ) : FullScreenOverlay

    @Immutable
    data class PdfViewerData(
        val messageId: Uuid,
        val fileId: Uuid,
        val payloadKey: String,
        val title: String,
        val userDate: Instant,
    ) : FullScreenOverlay
}

sealed class AttachmentPendingFile(val attachmentId: Uuid) {
    /**
     * @param metadata EXIF / image-file metadata, populated asynchronously after
     *   the picker callback. Null until the read finishes (or the read fails).
     * @param includeLocation Per-image opt-in for embedding GPS coordinates in the
     *   eventual post. Date / camera / dimensions always travel when known —
     *   only the privacy-sensitive bit is gated. Defaults false.
     * @param forceSticker Per-image opt-in to send as a sticker (transparent
     *   cut-out render), threaded into [id.homebase.chat.services.builder.AttachmentInput.forceSticker]
     *   at send time so it skips the auto alpha probe. Set true by the
     *   background-remover tool so intent survives a re-encode that flattens
     *   fringe alpha; also the carrier for the manual "Send as sticker" toggle.
     *   Ephemeral editor state, like [includeLocation]. Defaults false.
     */
    data class FileImage(
        val id: Uuid,
        val file: PlatformFile,
        val metadata: ImageMetadata? = null,
        val includeLocation: Boolean = false,
        /**
         * Force this image to be sent as a sticker (transparent cut-out render),
         * bypassing the automatic alpha probe. Set when re-staging a saved sticker
         * from the tray; threaded into [AttachmentInput.forceSticker] at send time.
         */
        val forceSticker: Boolean = false,
    ) : AttachmentPendingFile(id)
    data class FileVideo(
        val id: Uuid,
        val file: PlatformFile,
        // Real source filename (with extension) for when [file]'s own name is uninformative — e.g.
        // an iOS quick-switch gallery pick whose PlatformFile path is a bare PHAsset localIdentifier
        // ("<uuid>/L0/001", no extension). Used at send time to resolve the video content-type and
        // display name; null falls back to file.name. Without it the content-type resolves to
        // application/octet-stream and the video is uploaded as a generic file instead of routing
        // through the video pipeline (the "quick-switch video sends as a file" bug).
        val sourceFileName: String? = null,
        val thumbnailBytes: ByteArray? = null,
        val durationMs: Long? = null,
        val trimStartMs: Long? = null,
        val trimEndMs: Long? = null,
        // okio-readable path for the decoder/player. On web this is the materialized okio path
        // (a browser-picked PlatformFile has no path, so file.toString() isn't readable); on
        // native it equals file.toString(). Populated by AttachmentHandler.extractThumbnailAsync.
        val playablePath: String? = null,
    ) : AttachmentPendingFile(id)
    data class File(val id: Uuid, val file: PlatformFile) : AttachmentPendingFile(id)
    data class Gallery(
        val id: Uuid,
        val image: GalleryImage,
        /** See [FileImage.forceSticker]. */
        val forceSticker: Boolean = false,
    ) : AttachmentPendingFile(id)
    data class Audio(val id: Uuid, val audioFile: PlatformFile, val waveformFile: PlatformFile?, val lengthSeconds: Int) : AttachmentPendingFile(id)
}


@Immutable
data class RecordingData(
    val file: PlatformFile,
    val conversationId: Uuid,
    val isProcessing: Boolean = false,
)