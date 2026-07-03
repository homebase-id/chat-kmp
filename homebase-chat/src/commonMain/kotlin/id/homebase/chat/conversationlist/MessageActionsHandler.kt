package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.image.ImageHeaderParser
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.conversationlist.ConversationListUiDialog.DeleteMessage
import id.homebase.chat.conversationlist.ConversationListUiDialog.DiscardDraft
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.upload.PayloadBundle
import id.homebase.chat.services.MAX_REACTIONS_PER_USER_PER_MESSAGE
import id.homebase.chat.services.ReplyContext
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.builder.toImageAttachmentInput
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.builder.LocationPreviewPayloadBuilder
import id.homebase.chat.services.renderer.LocationPreviewRenderer
import id.homebase.chat.services.renderer.PayloadRenderer
import id.homebase.chat.services.renderer.toCombinedPayloadBundle
import id.homebase.chat.services.renderer.toMessageDataType
import id.homebase.api.client.drives.files.reactions.ToggleReactionResultType
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.core.emoji.EmojiNormalization.distinctByEmoji
import id.homebase.core.settings.UserPreferences
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.resolveContentType
import id.homebase.core.widget.ReactionDisplayItem
import id.homebase.resources.MR
import id.homebase.resources.chat_message_forwarded
import id.homebase.resources.chat_reactions_limit_reached
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ConversationListViewModel"

/**
 * Handles message-action arms (send / edit / delete / react / scroll-to /
 * mark-as-read / forward / reply / battle-dice / reaction-details) extracted
 * from `ConversationListViewModel.onAction`.
 *
 * Owns [pendingMessageId] (the in-flight outgoing-message uniqueId) and the
 * sender helpers ([addMessageWithFiles] in particular) that
 * [AttachmentHandler.handleStopRecording] reaches into for the audio-send
 * path. The VM's `selectConversation` calls [processPendingSharedContent].
 *
 * Behavior is byte-identical to the previous in-VM implementation; the move
 * is purely structural — see PR 4b-2 in `lucky-chasing-valley.md` for context.
 */
internal class MessageActionsHandler(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ConversationListUiState>,
    private val messagesUiState: MutableStateFlow<MessageListUiState>,
    private val messageInputTextState: RichTextState,
    private val chatMessageStream: ChatMessageStream,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val chatMessageActionService: ChatMessageActionService,
    private val conversationService: ConversationService,
    private val contactService: ContactService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val localVideoContextStore: LocalAttachmentContextStore,
    private val shareContentProcessor: ShareContentProcessor,
    private val userPreferences: UserPreferences,
    private val sendEvent: (ConversationListUiEvent) -> Unit,
    private val dispatch: (ConversationListUiAction) -> Unit,
    private val ensureThumbnail: suspend (AttachmentPendingFile.FileVideo) -> AttachmentPendingFile.FileVideo,
) {

    /**
     * Tracks the uniqueId of an in-flight outgoing message so the message-stream
     * collector in CLVM can clear its anchor reference once the message lands
     * locally. VM's loadMessagesForConversation reads/clears this; handler arms
     * write it on send.
     */
    internal var pendingMessageId: Uuid? = null

    fun handleSendMessage(action: ConversationListUiAction.SendMessage) {
        val hasMessage = !messageInputTextState.annotatedString.isBlank()
        // User-initiated attachments (location, contact, etc.) enable send even with no
        // text. Link previews don't — they're auto-detected from typed URLs and only
        // ride along when there's a text message to send.
        val hasUserInitiatedAttachment = action.payloadRenderers.any {
            it !is id.homebase.chat.services.renderer.LinkPreviewRenderer
        }
        if (hasMessage || hasUserInitiatedAttachment) {
            messagesUiState.update { it.copy(isSendingMessage = true) }
            val content = messageInputTextState.toMarkdown().trimEnd()
            val replyTo = messagesUiState.value.replyToMessage
            if (replyTo != null) {
                replyToMessage(
                    conversationId = action.conversationId,
                    replyTo = replyTo,
                    content = content,
                    payloadRenderers = action.payloadRenderers,
                )
            } else {
                addMessage(
                    conversationId = action.conversationId,
                    content = content,
                    payloadRenderers = action.payloadRenderers,
                )
            }
            // Input is cleared inside addMessage/replyToMessage after
            // the send is successfully queued.
        }
    }

    fun handleEditMessage(action: ConversationListUiAction.EditMessage) {
        scope.launch {
            try {
                if (!action.ignoreDraft && messageInputTextState.annotatedString.isNotBlank()) {
                    uiState.update {
                        it.copy(
                            uiDialog = DiscardDraft(action.messageId, action.versionTag)
                        )
                    }
                    return@launch
                }

                chatMessageStream.getMessage(action.messageId)?.let { message ->
                    messagesUiState.update {
                        it.copy(
                            isEditingMessageId = action.messageId,
                            isEditingVersionTag = action.versionTag,
                            replyToMessage = null
                        )
                    }
                    messageInputTextState.setMarkdown(message.content)
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to edit message: ${e.message}"
                }
                sendEvent(
                    ShowErrorMessage(
                        "Failed to edit message: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleEditMessageSave() {
        messagesUiState.update { it.copy(isSendingMessage = true) }
        messagesUiState.value.isEditingMessageId?.let { messageId ->
            editMessage(
                messageId = messageId,
                versionTag = messagesUiState.value.isEditingVersionTag ?: Uuid.NIL,
                content = messageInputTextState.toMarkdown().trimEnd(),
            )
        }
    }

    fun handleCancelEditMessage() {
        messageInputTextState.clear()
        messagesUiState.update {
            it.copy(
                isEditingMessageId = null,
                isEditingVersionTag = null
            )
        }
    }

    fun handleDeleteMessage(action: ConversationListUiAction.DeleteMessage) {
        val messages = messagesUiState.value.messages.mapNotNull {
            if (it is MessageListContentModel.Message) it.message else null
        }
        val message = messages.firstOrNull { it.id == action.messageId } ?: return
        val isCurrentUserMessage =
            message.originalAuthor?.domainName == uiState.value.ownerSession?.odinId?.domainName
        val isWithSelf =
            message.conversationId == ChatProtocol.ConversationWithYourselfId
        uiState.update {
            it.copy(
                uiDialog = DeleteMessage(
                    messageId = action.messageId,
                    allowDeleteForEveryone = isCurrentUserMessage && !isWithSelf
                )
            )
        }
    }

    fun handleScrollToMessageId(action: ConversationListUiAction.ScrollToMessageId) {
        scope.launch {
            try {
                val indexOfMessageForScroll = messagesUiState.value.messages.indexOfLast {
                    it is MessageListContentModel.Message && it.message.id == action.messageId
                }

                if (indexOfMessageForScroll != -1) {
                    messagesUiState.update {
                        it.copy(
                            scrollPosition =
                                ScrollPosition(
                                    firstVisibleItemIndex = indexOfMessageForScroll,
                                    triggerScroll = true
                                ),
                            highlightedMessageId = action.messageId,
                        )
                    }
                }
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to scroll to message: ${e.message}"))
            }
        }
    }

    fun handleClearHighlightedMessage() {
        messagesUiState.update { it.copy(highlightedMessageId = null) }
    }

    /**
     * Tap on the inline reply-preview that's pinned above a message bubble.
     * For an Event target we want to open the detail dialog directly (the
     * user's intent is "show me that event", not "scroll me to it"); for any
     * other content kind we keep today's scroll-to-message behavior.
     *
     * Resolution path: [ChatMessageStream.getMessage] short-circuits to the
     * paginated in-memory window first and falls through to a single
     * `selectHomebaseFileByUnique` DB read on miss — so the Event-out-of-window
     * case still works without scrolling history into view.
     */
    fun handleOpenReplyTarget(action: ConversationListUiAction.OpenReplyTarget) {
        scope.launch {
            try {
                val target = chatMessageStream.getMessage(action.messageId)
                val event = target?.messageContent as? MessageContent.Event
                val descriptor = event?.descriptor
                if (target != null && descriptor != null) {
                    messagesUiState.update {
                        it.copy(
                            replyTargetEventDetail = ReplyTargetEventDetail(
                                descriptor = descriptor,
                                messageId = target.id,
                                conversationId = target.conversationId,
                                ownReactions = target.ownReactions,
                                reactionSummary = target.reactionPreview,
                                organizer = target.originalAuthor,
                            )
                        )
                    }
                } else {
                    handleScrollToMessageId(
                        ConversationListUiAction.ScrollToMessageId(action.messageId)
                    )
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) {
                    "Failed to open reply target ${action.messageId}: ${e.message}"
                }
                handleScrollToMessageId(
                    ConversationListUiAction.ScrollToMessageId(action.messageId)
                )
            }
        }
    }

    fun handleDismissEventDetailFromReply() {
        messagesUiState.update { it.copy(replyTargetEventDetail = null) }
    }

    fun handleDeleteMessageForEveryone(action: ConversationListUiAction.DeleteMessageForEveryone) {
        scope.launch {
            try {
                chatMessageActionService.deleteMessage(
                    action.messageId, deleteForEveryone = true
                )
            } catch (e: Exception) {
                sendEvent(
                    ShowErrorMessage(
                        "Failed to delete message for everyone: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleDeleteMessageForMe(action: ConversationListUiAction.DeleteMessageForMe) {
        scope.launch {
            try {
                chatMessageActionService.deleteMessage(
                    action.messageId, deleteForEveryone = false
                )
            } catch (e: Exception) {
                sendEvent(
                    ShowErrorMessage(
                        "Failed to delete message for me: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleMarkAsRead(action: ConversationListUiAction.MarkAsRead) {
        scope.launch {
            try {
                if (action.messages == null) {
                    chatMessageActionService.markAllAsRead(action.conversationId)
                } else {
                    chatMessageActionService.markAsReadByFiles(
                        action.conversationId,
                        action.messages
                    )
                }
            } catch (e: Exception) {
                sendEvent(
                    ShowErrorMessage(
                        "Failed to mark message as read: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleToggleReaction(action: ConversationListUiAction.ToggleReaction) {
        scope.launch {
            if (action.reaction.isEmpty()) return@launch

            // Mirror the server-side cap of MAX_REACTIONS_PER_USER_PER_MESSAGE
            // distinct reactions per user per message. Adding past the cap is
            // a 400 (UnhandledScenario / "Too many Reactions") at upload, so
            // block it before we optimistically write or hit the outbox.
            // Removes (toggling an emoji the user already has) are always
            // allowed.
            var targetMessage: MessageUiModel? = null
            for (item in messagesUiState.value.messages) {
                if (item is MessageListContentModel.Message &&
                    item.message.id == action.messageId
                ) {
                    targetMessage = item.message
                    break
                }
            }
            if (targetMessage != null) {
                // ownReactions holds bare emoji (decoded by
                // ChatMessageStream from the wire's
                // `{"emoji":"X"}`); compare directly so removes
                // at the cap aren't misread as adds.
                val alreadyHasIt = action.reaction in targetMessage.ownReactions
                if (!alreadyHasIt &&
                    targetMessage.ownReactions.size >= MAX_REACTIONS_PER_USER_PER_MESSAGE
                ) {
                    sendEvent(ShowInfoMessage(MR.string.chat_reactions_limit_reached))
                    return@launch
                }
            }

            val previousReactions = messagesUiState.value.messageReactions
            try {
                messagesUiState.update { state ->
                    if (state.reactionDetailsMessageId != action.messageId) {
                        return@update state
                    }
                    val ownerSession = state.ownerSession
                        ?: return@update state
                    val ownerOdinId = ownerSession.odinId.domainName
                    val current = state.messageReactions ?: emptyList()
                    val hasReaction = current.any {
                        it.odinId == ownerOdinId && it.emoji == action.reaction
                    }
                    val updated = if (hasReaction) {
                        current.filterNot {
                            it.odinId == ownerOdinId && it.emoji == action.reaction
                        }
                    } else {
                        current + ReactionDisplayItem(
                            odinId = ownerOdinId,
                            displayName = ownerSession.displayName ?: ownerOdinId,
                            emoji = action.reaction,
                        )
                    }
                    if (updated.isEmpty()) {
                        state.copy(
                            messageReactions = null,
                            reactionDetailsMessageId = null,
                        )
                    } else {
                        state.copy(messageReactions = updated)
                    }
                }

                val result = chatMessageActionService.toggleReaction(
                    action.conversationId,
                    action.messageId,
                    action.reaction
                )

                // Only promote to the top of the quick-react popup when this
                // toggle ADDED the reaction. A remove must not bump the just-
                // removed emoji to the front of the user's preferred list.
                if (result.resultType == ToggleReactionResultType.Added) {
                    val promoted = (listOf(action.reaction) +
                        messagesUiState.value.userDefaultReactions)
                        .distinctByEmoji()
                        .toPersistentList()
                    messagesUiState.update {
                        it.copy(userDefaultReactions = promoted)
                    }
                    userPreferences.preferredUserReactions = promoted.take(6)
                }
            } catch (e: Exception) {
                messagesUiState.update { it.copy(messageReactions = previousReactions) }
                sendEvent(
                    ShowErrorMessage(
                        "Failed to toggle reaction: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleSendFile(action: ConversationListUiAction.SendFile) {
        messagesUiState.update { it.copy(scrollPosition = null, isSendingMessage = true) }
        val replyTo = messagesUiState.value.replyToMessage

        addMessageWithFiles(
            conversationId = action.conversationId,
            content = action.message.trimEnd(),
            files = action.attachments,
            replyTo = replyTo,
        )
        // Input is cleared inside addMessageWithFiles after
        // the send is successfully queued.
    }

    fun handleReplyToMessage(action: ConversationListUiAction.ReplyToMessage) {
        messagesUiState.update { it.copy(replyToMessage = action.message) }
    }

    fun handleBattleDiceRoll(action: ConversationListUiAction.BattleDiceRoll) {
        messagesUiState.update { it.copy(battleTargetMessage = action.message) }
    }

    fun handleCancelBattleDiceRoll() {
        messagesUiState.update { it.copy(battleTargetMessage = null) }
    }

    fun handleForwardMessage(action: ConversationListUiAction.ForwardMessage) {
        scope.launch {
            try {
                val allConversations = uiState.value.activeConversations
                val allContacts = contactService.contacts.value

                val selfConversation =
                    allConversations.firstOrNull { it.conversation.isWithSelf }
                val nonSelfConversations =
                    allConversations.filter { !it.conversation.isWithSelf }
                val recentConversations = nonSelfConversations
                    .filter { !it.conversation.isGroupConversation }
                    .take(5)
                val groupConversations = nonSelfConversations
                    .filter { it.conversation.isGroupConversation }
                    .sortedBy { it.getDisplayName().lowercase() }
                val sortedContacts = allContacts.sortedBy { it.name.lowercase() }

                val recipientGroups = buildList {
                    if (selfConversation != null) {
                        add(
                            RecipientGroupModel(
                                recipientType = RecipientType.You,
                                recipients = listOf(
                                    RecipientModel.Conversation(
                                        selfConversation
                                    )
                                )
                            )
                        )
                    }
                    if (recentConversations.isNotEmpty()) {
                        add(
                            RecipientGroupModel(
                                recipientType = RecipientType.Recents,
                                recipients = recentConversations.map {
                                    RecipientModel.Conversation(
                                        it
                                    )
                                }
                            ))
                    }
                    if (sortedContacts.isNotEmpty()) {
                        add(
                            RecipientGroupModel(
                                recipientType = RecipientType.Contacts,
                                recipients = sortedContacts.map { RecipientModel.Contact(it) }
                            ))
                    }
                    if (groupConversations.isNotEmpty()) {
                        add(
                            RecipientGroupModel(
                                recipientType = RecipientType.Groups,
                                recipients = groupConversations.map {
                                    RecipientModel.Conversation(
                                        it
                                    )
                                }
                            ))
                    }
                }

                messagesUiState.update {
                    it.copy(
                        uiSheet = MessageListUiSheet.ForwardMessage(
                            message = action.message,
                            recipients = recipientGroups.toPersistentList(),
                            selectedRecipients = persistentListOf(),
                        )
                    )
                }
            } catch (e: Exception) {
                Logger.e("Failed to open forward message sheet", e)
                sendEvent(
                    ShowErrorMessage(
                        "Failed to open forward message sheet: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleForwardMessageSelectRecipient(action: ConversationListUiAction.ForwardMessageSelectRecipient) {
        val updatedSheet =
            (messagesUiState.value.uiSheet as? MessageListUiSheet.ForwardMessage)?.let {
                if (it.selectedRecipients.contains(action.recipient)) {
                    val newSelected = it.selectedRecipients - action.recipient
                    it.copy(selectedRecipients = newSelected.toPersistentList())
                } else {
                    val newSelected = it.selectedRecipients + action.recipient
                    it.copy(selectedRecipients = newSelected.toPersistentList())
                }
            }
        messagesUiState.update { it.copy(uiSheet = updatedSheet) }
    }

    fun handleForwardMessageSend(action: ConversationListUiAction.ForwardMessageSend) {
        messagesUiState.update { it.copy(isSendingMessage = true) }
        scope.launch {
            try {
                val conversationIds = action.recipients.map { recipientModel ->
                    when (recipientModel) {
                        is RecipientModel.Contact -> {
                            conversationService.createConversation(
                                recipients = listOf(recipientModel.contact.odinId),
                                title = "",
                                payloadBundle = null,
                            ).conversationId
                        }

                        is RecipientModel.Conversation -> recipientModel.conversation.conversation.id
                    }
                }
                chatMessageSenderService.forwardMessage(
                    sourceMessageUniqueId = action.message.id,
                    targetConversationIds = conversationIds
                )
                sendEvent(ShowInfoMessage(MR.string.chat_message_forwarded))
            } catch (e: Exception) {
                Logger.e("Failed to send forward message", e)
                sendEvent(
                    ShowErrorMessage(
                        "Failed to send forward message: ${e.message}"
                    )
                )
            } finally {
                messagesUiState.update { it.copy(isSendingMessage = false) }
            }
        }
    }

    fun handleCancelReplyToMessage() {
        messagesUiState.update { it.copy(replyToMessage = null) }
    }

    fun handleShowReactionDetails(action: ConversationListUiAction.ShowReactionDetails) {
        messagesUiState.update { it.copy(reactionDetailsMessageId = action.messageId) }
        loadReactionDetails(action.messageId)
    }

    fun handleHideReactionDetails() {
        messagesUiState.update { it.copy(messageReactions = null, isReactionsLoading = false, reactionDetailsMessageId = null) }
    }

    /**
     * Awaits in-flight thumbnail extraction for any FileVideo entries (set up
     * by [AttachmentHandler.extractThumbnailAsync]) and dispatches the message
     * with attachments. Exposed as `internal` because [AttachmentHandler.handleStopRecording]
     * calls it for the audio-send path.
     */
    internal fun addMessageWithFiles(
        conversationId: Uuid,
        content: String,
        files: List<AttachmentPendingFile>,
        replyTo: MessageUiModel? = null,
    ) {
        val sentAt = UnixTimeUtc.now()
        scope.launch {
            // If any FileVideo entries still have a thumbnail extraction in flight (the
            // user hit Send before the background poster task finished), wait on it once
            // so the message envelope ships with a poster frame.
            val resolvedFiles = files.map { f ->
                if (f is AttachmentPendingFile.FileVideo) ensureThumbnail(f) else f
            }
            val attachments = mutableListOf<AttachmentInput>()
            resolvedFiles.forEach { attachment ->
                when (attachment) {
                    is AttachmentPendingFile.File -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.file.toUploadPath(fileOperationsProvider),
                                contentType = resolveContentType(
                                    fileName = attachment.file.name,
                                    platformMimeType = attachment.file.mimeType()?.toString(),
                                ),
                                displayName = attachment.file.name,
                            )
                        )
                    }

                    is AttachmentPendingFile.FileImage -> {
                        attachments.add(
                            attachment.file.toImageAttachmentInput(fileOperationsProvider)
                                .copy(forceSticker = attachment.forceSticker),
                        )
                    }

                    is AttachmentPendingFile.FileVideo -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.file.toUploadPath(fileOperationsProvider),
                                contentType = resolveContentType(
                                    fileName = attachment.sourceFileName ?: attachment.file.name,
                                    platformMimeType = attachment.file.mimeType()?.toString(),
                                ),
                                displayName = attachment.sourceFileName ?: attachment.file.name,
                                trimStartMs = attachment.trimStartMs,
                                trimEndMs = attachment.trimEndMs,
                                // Web: the blob: URL becomes the ffmpeg compress INPUT (read in JS,
                                // no Kotlin copy / base64). Null on native. Not revoked here — it
                                // also backs the sent bubble's local preview; freed on logout/reload.
                                inputBlobUrl = attachment.playablePath,
                            )
                        )
                    }

                    is AttachmentPendingFile.Gallery -> {
                        var filePath = attachment.image.file.toUploadPath(fileOperationsProvider)
                        var contentType = resolveContentType(
                            fileName = attachment.image.fileName,
                            platformMimeType = attachment.image.file.mimeType()?.toString(),
                        )
                        if (contentType == "image/heic" || contentType == "image/heif") {
                            val heicBytes = fileOperationsProvider.readFileBytes(filePath)
                            val jpegBytes = convertHeicToJpeg(heicBytes)
                            if (jpegBytes != null) {
                                filePath = fileOperationsProvider.writeBytesToTempFile(
                                    jpegBytes,
                                    "heic_converted_",
                                    ".jpg"
                                )
                                contentType = "image/jpeg"
                            }
                        }
                        attachments.add(
                            AttachmentInput(
                                filePath = filePath,
                                contentType = contentType,
                                displayName = attachment.image.fileName,
                                forceSticker = attachment.forceSticker,
                            )
                        )
                    }

                    is AttachmentPendingFile.Audio -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.audioFile.toUploadPath(fileOperationsProvider),
                                contentType = resolveContentType(
                                    fileName = attachment.audioFile.name,
                                    platformMimeType = attachment.audioFile.mimeType()?.toString(),
                                ),
                                displayName = attachment.audioFile.name,
                                waveformFile = attachment.waveformFile?.toUploadPath(fileOperationsProvider),
                                audioLengthSeconds = attachment.lengthSeconds,
                            )
                        )
                    }
                }
            }

            val newMessageId = Uuid.random()
            Logger.d(tag = TAG) { "addMessageWithFiles: message=$newMessageId conversation=$conversationId files=${files.size}" }

            // Store a local preview context per attachment (keyed by the payload key the
            // MessageAttachmentBuilder will emit), so both the placeholder and the eventual
            // real bubble can render the local preview without fetching from the server.
            // Populate local contexts synchronously with what we have on hand, so the
            // placeholder shows immediately. For videos the thumbnail bytes give us the
            // aspect for free; for images we compute aspect asynchronously below and
            // re-put once we have it — avoids blocking the placeholder on image I/O.
            val imagePathsToRefine = mutableListOf<Pair<String, String>>()
            resolvedFiles.forEachIndexed { index, file ->
                val payloadKey = "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                val ctx: LocalAttachmentContext? = when (file) {
                    is AttachmentPendingFile.FileVideo -> {
                        val bytes = file.thumbnailBytes
                        if (bytes != null) {
                            val aspect = runCatching {
                                val size = ImageUtils.getNaturalSize(bytes)
                                if (size.pixelWidth > 0 && size.pixelHeight > 0)
                                    size.pixelWidth.toFloat() / size.pixelHeight.toFloat()
                                else null
                            }.getOrNull()
                            LocalAttachmentContext.Video(
                                thumbnailBytes = bytes,
                                // On web file.file.toString() is a bare filename (no "://"), which
                                // the web fileExists() heuristic rejects → the store drops the
                                // context → the bubble degrades to a generic "1 attachment" with no
                                // thumb/progress. playablePath is the blob: URL (contains "://") and
                                // is a real local-preview source there. Native: == file.toString().
                                localFilePath = file.playablePath ?: file.file.toString(),
                                aspectRatio = aspect,
                                trimStartMs = file.trimStartMs,
                                trimEndMs = file.trimEndMs,
                                durationMs = file.durationMs,
                            )
                        } else null
                    }
                    is AttachmentPendingFile.FileImage -> {
                        val path = file.file.toString()
                        imagePathsToRefine += payloadKey to path
                        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null)
                    }
                    is AttachmentPendingFile.Gallery -> {
                        val path = file.image.file.toString()
                        imagePathsToRefine += payloadKey to path
                        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null)
                    }
                    is AttachmentPendingFile.File -> null
                    is AttachmentPendingFile.Audio -> null
                }
                if (ctx != null) {
                    localVideoContextStore.put(newMessageId, payloadKey, ctx)
                }
            }

            // Refine image aspect ratios off the main path. Try a header-only parser
            // first so we don't allocate the full image bytes just to read width/height;
            // fall back to the full-bytes decoder for formats we can't sniff (e.g. HEIC).
            if (imagePathsToRefine.isNotEmpty()) {
                scope.launch {
                    imagePathsToRefine.forEach { (payloadKey, path) ->
                        val aspect = runCatching {
                            val header = fileOperationsProvider.readFileHeaderBytes(path)
                            val size = ImageHeaderParser.parse(header)
                                ?: ImageUtils.getNaturalSize(fileOperationsProvider.readFileBytes(path))
                            if (size.pixelWidth > 0 && size.pixelHeight > 0)
                                size.pixelWidth.toFloat() / size.pixelHeight.toFloat()
                            else null
                        }.getOrNull()
                        if (aspect != null) {
                            localVideoContextStore.put(
                                newMessageId,
                                payloadKey,
                                LocalAttachmentContext.Image(localFilePath = path, aspectRatio = aspect),
                            )
                        }
                    }
                }
            }

            pendingMessageId = newMessageId

            val placeholder = PendingOutgoingMessage(
                id = newMessageId,
                conversationId = conversationId,
                text = content,
                attachmentCount = files.size,
                sentAt = Instant.fromEpochMilliseconds(sentAt.milliseconds),
                replyPreview = replyTo?.toReplyPreview(),
            )

            // Register the placeholder, register upload progress, clear the
            // composer, and close the overlay BEFORE the heavy work so the
            // user sees a "Preparing…" bubble in the chat immediately.
            messagesUiState.update { state ->
                state.copy(
                    uploadProgress = (state.uploadProgress + (newMessageId to UploadStatus.Preparing)).toPersistentMap(),
                    pendingOutgoing = (state.pendingOutgoing + placeholder).toPersistentList(),
                    fullScreenOverlay = null,
                    isSendingMessage = false,
                    replyToMessage = if (replyTo != null) null else state.replyToMessage,
                    // Closing fullScreenOverlay remounts ConversationContent with the
                    // placeholder already present, so its layout-growth auto-follow can't
                    // see growth. This token tells the freshly-mounted list to follow our
                    // own send down to the bottom. See MessageListUiState.scrollToLatestRequest.
                    scrollToLatestRequest = newMessageId,
                )
            }
            // NOTE: do NOT revoke the videos' blob: URLs here — they're now the ffmpeg compress
            // INPUT and are consumed by the async upload (revoked in writeFileFromUrl once written
            // into MEMFS). Unattach/dismiss still revoke for never-sent attachments.
            messageInputTextState.clear()

            scope.launch {
                try {
                    val bundle = MessageAttachmentBuilder.build(
                        attachments = attachments,
                        fileOperationsProvider = fileOperationsProvider,
                        payloadKeyFactory = { index, _ ->
                            "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                        })

                    if (replyTo != null) {
                        Logger.d(tag = TAG) { "addMessageWithFiles: reply message=$newMessageId conversation=$conversationId replyTo=${replyTo.id}" }
                        chatMessageSenderService.replyToMessage(
                            messageUniqueId = newMessageId,
                            conversationId = conversationId,
                            replyTo = replyTo.toReplyPreview(),
                            messageText = content,
                            previousMessageUniqueId = null,
                            payloadBundle = bundle,
                            userDate = sentAt,
                        )
                    } else {
                        chatMessageSenderService.sendNewMessage(
                            messageUniqueId = newMessageId,
                            conversationId = conversationId,
                            messageText = content,
                            previousMessageUniqueId = null,
                            payloadBundle = bundle,
                            userDate = sentAt,
                        )
                    }
                    // Real optimistic bubble has landed — drop the placeholder.
                    messagesUiState.update { state ->
                        state.copy(
                            pendingOutgoing = state.pendingOutgoing
                                .filterNot { it.id == newMessageId }
                                .toPersistentList(),
                        )
                    }
                    jumpToLatestAfterOwnSend(conversationId)
                } catch (e: Throwable) {
                    // Throwable, not Exception: a missing platform impl throws kotlin.NotImplementedError
                    // (an Error, not an Exception). Catching only Exception let that escape, killing the
                    // coroutine and leaving the bubble stuck on "Preparing" forever. Re-throw
                    // CancellationException so structured-concurrency cancellation still propagates.
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    Logger.e(
                        throwable = e,
                        tag = TAG
                    ) { "addMessageWithFiles failed for message=$newMessageId conversation=$conversationId" }
                    messagesUiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress - newMessageId).toPersistentMap(),
                            pendingOutgoing = state.pendingOutgoing
                                .filterNot { it.id == newMessageId }
                                .toPersistentList(),
                            replyToMessage = replyTo ?: state.replyToMessage,
                        )
                    }
                    sendEvent(
                        ShowErrorMessage(
                            // Fail soft: a disposable pre-encryption source was swept/evicted (or its
                            // content://`/`ph:// grant revoked) before send. No outbox row was enqueued
                            // — the right fix is to re-pick, not retry.
                            if (e is SourceUnavailableException)
                                "That attachment is no longer available — please pick it again."
                            else
                                "Failed to send file(s): ${e.message}"
                        )
                    )
                }
            }
        }
    }

    /**
     * Checks for pending shared content (from iOS share extension handoff)
     * and sends it to the given conversation automatically.
     */
    internal suspend fun processPendingSharedContent(
        conversationId: Uuid,
        trigger: ConversationLoadTrigger,
    ) {
        // Only a share-intent navigation may auto-send the pending descriptor. Any other
        // open of this conversation (notification tap, ordinary navigation) must NOT consume
        // it — otherwise a descriptor left on disk from an earlier share would be re-sent the
        // next time its target conversation is opened. A leftover descriptor is harmless: a
        // ShareIntent navigation is always preceded by the share extension writing a *fresh*
        // descriptor (it overwrites), so a stale one can never be the thing consumed here.
        if (trigger != ConversationLoadTrigger.ShareIntent) return

        val descriptor = shareContentProcessor.readPendingContent() ?: return
        // Only process if the target conversation matches
        if (descriptor.targetConversationId != conversationId.toString()) return

        Logger.i(tag = "ConversationListViewModel") {
            "Processing shared content: type=${descriptor.contentType}, files=${descriptor.fileNames.size}"
        }

        try {
            val text = descriptor.text ?: descriptor.url ?: ""

            if (descriptor.fileNames.isEmpty()) {
                // Text/URL only
                addMessage(conversationId, text)
            } else {
                // Build AttachmentInput list from shared files
                val attachments =
                    descriptor.fileNames.zip(descriptor.mimeTypes).map { (name, mime) ->
                        val filePath = shareContentProcessor.resolveFilePath(name)
                        AttachmentInput(
                            filePath = filePath,
                            contentType = mime,
                            displayName = name,
                        )
                    }

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId

                val bundle = MessageAttachmentBuilder.build(
                    attachments = attachments,
                    fileOperationsProvider = fileOperationsProvider,
                    payloadKeyFactory = { index, _ ->
                        "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                    }
                )

                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = text,
                    previousMessageUniqueId = null,
                    payloadBundle = bundle,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceUnavailableException) {
            // Shared-in source vanished before send (no outbox row enqueued) — re-pick.
            Logger.w(tag = "ConversationListViewModel") { "Shared content source unavailable: ${e.path}" }
            sendEvent(ShowErrorMessage("That shared file is no longer available — please share it again."))
        } catch (e: Exception) {
            Logger.e(tag = "ConversationListViewModel") { "Failed to send shared content: ${e.message}" }
            sendEvent(ShowErrorMessage("Failed to send shared content: ${e.message}"))
        } finally {
            shareContentProcessor.cleanup()
        }
    }

    private fun editMessage(messageId: Uuid, versionTag: Uuid, content: String) {
        scope.launch {
            try {
                chatMessageSenderService.updateMessage(
                    messageId = messageId,
                    versionTag = versionTag,
                    content = content
                )
                messagesUiState.update {
                    it.copy(
                        isEditingMessageId = null,
                        isEditingVersionTag = null
                    )
                }
                messageInputTextState.clear()
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to edit message: ${e.message}"))
            } finally {
                messagesUiState.update { it.copy(isSendingMessage = false) }
            }
        }
    }

    /**
     * After the user sends their own message, jump to the latest page if the
     * loaded window is paged up in history ([MessageListUiState.hasNewerMessages]
     * == true — they scrolled up, or the window was trimmed past MAX_WINDOW_SIZE).
     *
     * In that state the optimistic message was gated out of the window by
     * ChatMessageStream's `hasNewerMessages` guard, so it isn't visible. Reusing
     * the [ConversationListUiAction.ScrollToLatest] arm reloads the newest page
     * (which now contains the just-written optimistic message) and lands on it at
     * the bottom — matching Signal/WhatsApp, where typing while scrolled up moves
     * you down to your message. A no-op when already at the latest (the normal
     * auto-follow in ConversationContent handles that case).
     *
     * Call only after the send's suspend call returns, so the optimistic DB write
     * the reload re-reads has completed.
     */
    private fun jumpToLatestAfterOwnSend(conversationId: Uuid) {
        if (messagesUiState.value.hasNewerMessages) {
            dispatch(ConversationListUiAction.ScrollToLatest(conversationId))
        }
    }

    private fun addMessage(
        conversationId: Uuid,
        content: String,
        payloadRenderers: List<PayloadRenderer> = emptyList(),
    ) {
        scope.launch {
            try {
                val payloadBundle = payloadRenderers.toCombinedPayloadBundle(fileOperationsProvider)

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                registerLocalPreviewContexts(newMessageId, payloadBundle)
                Logger.d(tag = TAG) { "addMessage: message=$newMessageId conversation=$conversationId" }

                // Location is a typed kind (= Event): the coordinate descriptor rides in the header
                // (appData), the map PNG stays a chat_loc payload. Sending it through the typed path
                // is what lets a live-share toggle edit the descriptor via updateMessage().
                val locationPreview =
                    payloadRenderers.filterIsInstance<LocationPreviewRenderer>().firstOrNull()?.preview
                if (locationPreview != null && payloadRenderers.size == 1) {
                    // Carry the user's typed caption in the descriptor (a typed message has no separate
                    // text body); cap it so the header descriptor stays well under the 7 KB budget.
                    val caption = content.trim().ifBlank { null }?.truncateToCodePoints(2000)
                    chatMessageSenderService.sendNewTypedMessage(
                        messageUniqueId = newMessageId,
                        conversationId = conversationId,
                        content = MessageContent.Location(
                            LocationPreviewPayloadBuilder.descriptorFor(locationPreview).copy(caption = caption)
                        ),
                        previousMessageUniqueId = null,
                        payloadBundle = payloadBundle,
                    )
                } else {
                    chatMessageSenderService.sendNewMessage(
                        messageUniqueId = newMessageId,
                        conversationId = conversationId,
                        messageText = content,
                        previousMessageUniqueId = null,
                        payloadBundle = payloadBundle,
                        dataType = payloadRenderers.toMessageDataType(),
                    )
                }
                messageInputTextState.clear()
                jumpToLatestAfterOwnSend(conversationId)
                Logger.d(tag = TAG) { "addMessage: complete message=$newMessageId" }
            } catch (e: Exception) {
                Logger.e(
                    throwable = e,
                    tag = TAG
                ) { "addMessage failed for conversation=$conversationId" }
                sendEvent(ShowErrorMessage("Failed to send message: ${e.message}"))
            } finally {
                messagesUiState.update { it.copy(isSendingMessage = false) }
            }
        }
    }

    /**
     * Register the plaintext local copy of a link preview's OG image so the optimistic bubble can
     * render it crisp via AsyncImage while the payload uploads — instead of the 20px embedded
     * tinyThumb. [PayloadFile.filePath] is the same temp file that feeds the encryption pipeline,
     * which reads it and writes ciphertext to a *separate* file (see PayloadBundleEncryptionService),
     * so it stays plaintext and decodable for the upload window. A present
     * [PayloadFile.previewThumbnail] is the signal that a real raster image was produced — the
     * no-image case writes a 1-byte sentinel we must not hand to Coil. Keyed by the link payload key
     * so MediaItem can look it up by (messageId, payloadKey).
     */
    private fun registerLocalPreviewContexts(messageId: Uuid, bundle: PayloadBundle?) {
        bundle?.payloads?.forEach { payload ->
            if (payload.key == ChatProtocol.PAYLOAD_KEY_LINKS && payload.previewThumbnail != null) {
                localVideoContextStore.put(
                    messageId,
                    payload.key,
                    LocalAttachmentContext.Image(localFilePath = payload.filePath, aspectRatio = null),
                )
            }
        }
    }

    private fun MessageUiModel.toReplyPreview() = ReplyPreview(
        replyUniqueId = id,
        authorOdinId = originalAuthor?.domainName ?: "null",
        message = content.truncateToCodePoints(80),
        previewThumbnail = previewThumbnail,
        context = (messageContent as? MessageContent.Event)?.descriptor
            ?.let { ReplyContext.event(it.startUtcMs) },
    )

    private fun replyToMessage(
        conversationId: Uuid,
        replyTo: MessageUiModel,
        content: String,
        payloadRenderers: List<PayloadRenderer> = emptyList(),
    ) {
        scope.launch {
            try {
                val payloadBundle = payloadRenderers.toCombinedPayloadBundle(fileOperationsProvider)

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                registerLocalPreviewContexts(newMessageId, payloadBundle)
                Logger.d(tag = TAG) { "replyToMessage: message=$newMessageId conversation=$conversationId replyTo=${replyTo.id}" }

                chatMessageSenderService.replyToMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    replyTo = replyTo.toReplyPreview(),
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle,
                    dataType = payloadRenderers.toMessageDataType(),
                )
                messageInputTextState.clear()
                messagesUiState.update { it.copy(replyToMessage = null) }
                jumpToLatestAfterOwnSend(conversationId)
                Logger.d(tag = TAG) { "replyToMessage: complete message=$newMessageId" }
            } catch (e: Exception) {
                Logger.e(
                    throwable = e,
                    tag = TAG
                ) { "replyToMessage failed for conversation=$conversationId" }
                sendEvent(ShowErrorMessage("Failed to send reply: ${e.message}"))
            } finally {
                messagesUiState.update { it.copy(isSendingMessage = false) }
            }
        }
    }

    private fun loadReactionDetails(messageId: Uuid) {
        messagesUiState.update { it.copy(isReactionsLoading = true, messageReactions = emptyList()) }
        scope.launch {
            try {
                val rawReactions = chatMessageActionService.getReactions(messageId)
                val reactions = rawReactions.map { reaction ->
                    val displayName = contactService.resolveByOdinId(reaction.odinId).name
                    ReactionDisplayItem(
                        odinId = reaction.odinId.domainName,
                        displayName = displayName,
                        emoji = reaction.emoji,
                    )
                }
                messagesUiState.update {
                    it.copy(messageReactions = reactions, isReactionsLoading = false)
                }
            } catch (_: Exception) {
                messagesUiState.update { it.copy(isReactionsLoading = false, messageReactions = null) }
            }
        }
    }
}
