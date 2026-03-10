package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.conversationlist.ConversationListUiDialog.DeleteMessage
import id.homebase.chat.conversationlist.ConversationListUiDialog.DiscardDraft
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateBack
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToContactInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToConversationSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToGroupSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToMessageInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToNewConversation
import id.homebase.chat.conversationlist.ConversationListUiEvent.OpenFile
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareFile
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareText
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.LinkPreviewPayloadBuilder
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.audio.AudioPlayer
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.resources.MR
import id.homebase.resources.chat_group_introduce_everyone_status
import id.homebase.resources.chat_message_audio_recording_help
import id.homebase.resources.chat_search_result_conversations
import id.homebase.resources.chat_search_result_messages
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.name
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@OptIn(FlowPreview::class)
class ConversationListViewModel(
    savedStateHandle: SavedStateHandle,
    private val conversationStream: ConversationStream,
    private val chatMessageStream: ChatMessageStream,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val chatMessageActionService: ChatMessageActionService,
    private val conversationService: ConversationService,
    private val userPreferences: UserPreferences,
    private val fileOperationsProvider: FileOperationsProvider,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val audioRecorder: AudioRecorder,
    private val audioPlayer: AudioPlayer,
    private val eventBus: EventBus,
) : ViewModel() {

    val ownerSession = ownerSessionRepository.user

    val chatListRoute = savedStateHandle.toRoute<Route.ChatList>()

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val _messagesUiState = MutableStateFlow(MessageListUiState())
    val messagesUiState: StateFlow<MessageListUiState> = _messagesUiState.asStateFlow()

    val conversationSearchTextState = TextFieldState()
    val messageInputTextState = RichTextState().applyDefaultStyling()
    var currentConversationJob: Job? = null
    var pendingMessageId: Uuid? = null

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
                _messagesUiState.update { it.copy(ownerSession = session) }
            }
        }

        viewModelScope.launch {
            conversationStream.start()
            conversationStream.conversations.collect { conversations ->
                val sorted = conversations.sortedByDescending { it.timestamp }.toPersistentList()
                _uiState.value = _uiState.value.copy(activeConversations = sorted)
                updateListContent()
            }
        }

        viewModelScope.launch {
            // TODO - restore any draft message stored for conversation here
            messageInputTextState.setMarkdown("")
        }

        // Load initial message if conversation is set
        viewModelScope.launch {
            chatListRoute.conversationId?.let { loadMessagesForConversation(Uuid.parse(it), null) }
        }

        // Listen for search query changes
        viewModelScope.launch {
            snapshotFlow { conversationSearchTextState.text.toString() }.debounce(300)
                .collectLatest { updateListContent() }
        }

        // Set connected state
        viewModelScope.launch {
            authConnectionCoordinator.connectionState
                .collectLatest { state ->
                    _uiState.update { it.copy(driveIsConnected = state.isConnected) }
                }
        }

        // Set isConnecting state
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.DriveEvent }.collectLatest { state ->
                if (state is BackendEvent.DriveEvent.SyncAllCompleted || state is BackendEvent.DriveEvent.Completed) {
                    _uiState.update { it.copy(driveIsSyncing = false) }
                } else if (state is BackendEvent.DriveEvent.Started || state is BackendEvent.DriveEvent.SyncAllStarted) {
                    _uiState.update { it.copy(driveIsSyncing = true) }
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    fun dialogClosed() {
        _uiState.update { it.copy(uiDialog = null) }
    }

    fun onAction(action: ConversationListUiAction) {
        when (action) {
            is ConversationListUiAction.ConversationClicked -> {
                loadMessagesForConversation(action.conversationId, action.messageId)
            }

            is ConversationListUiAction.BackClicked -> {
                sendEvent(NavigateBack)
            }

            is ConversationListUiAction.SearchClicked -> {
                _uiState.update { it.copy(isSearchActive = true) }
            }

            is ConversationListUiAction.SearchBackClicked -> {
                _uiState.update { it.copy(isSearchActive = false) }
            }

            is ConversationListUiAction.NewConversationClicked -> {
                _uiState.value = _uiState.value.copy(
                    uiEvent = NavigateToNewConversation
                )
            }

            is ConversationListUiAction.ClearSelection -> {
                // Clear the selected conversation when user navigates back to list
                _uiState.update { it.copy(selectedConversationId = null) }
            }

            is ConversationListUiAction.FilterByUnreadClicked -> {
                _uiState.update { it.copy(filterByUnread = true) }
                updateListContent()
            }

            is ConversationListUiAction.ClearFilterByUnreadClicked -> {
                _uiState.update { it.copy(filterByUnread = false) }
                updateListContent()
            }

            is ConversationListUiAction.SendMessage -> {
                val hasMessage = !messageInputTextState.annotatedString.isBlank()
                if (hasMessage) {
                    val content = messageInputTextState.toMarkdown()
                    val replyTo = _messagesUiState.value.replyToMessage
                    if (replyTo != null) {
                        replyToMessage(
                            conversationId = action.conversationId,
                            replyTo = replyTo,
                            content = content,
                            linkPreview = action.linkPreview
                        )
                        _messagesUiState.update { it.copy(replyToMessage = null) }
                    } else {
                        addMessage(
                            conversationId = action.conversationId,
                            content = content,
                            linkPreview = action.linkPreview
                        )
                    }
                    messageInputTextState.clear()
                }
            }

            is ConversationListUiAction.SaveScrollPosition -> {
                _messagesUiState.update {
                    it.copy(
                        scrollPosition = ScrollPosition(
                            firstVisibleItemIndex = action.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = action.firstVisibleItemScrollOffset,
                        )
                    )
                }

                // Persist to user settings
                viewModelScope.launch {
                    userPreferences.setConversationScrollIndex(
                        action.conversationId.toString(), action.firstVisibleItemIndex
                    )
                    userPreferences.setConversationScrollOffset(
                        action.conversationId.toString(), action.firstVisibleItemScrollOffset
                    )
                }
            }

            is ConversationListUiAction.EditMessage -> {
                viewModelScope.launch {
                    try {
                        if (!action.ignoreDraft && messageInputTextState.annotatedString.isNotBlank()) {
                            _uiState.update {
                                it.copy(
                                    uiDialog = DiscardDraft(action.messageId, action.versionTag)
                                )
                            }
                            return@launch
                        }

                        chatMessageStream.getMessage(action.messageId)?.let { message ->
                            _messagesUiState.update {
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

            is ConversationListUiAction.EditMessageSave -> {
                _messagesUiState.value.isEditingMessageId?.let { messageId ->
                    editMessage(
                        messageId = messageId,
                        versionTag = _messagesUiState.value.isEditingVersionTag ?: Uuid.NIL,
                        content = messageInputTextState.annotatedString.toString(),
                    )
                }
            }

            is ConversationListUiAction.CancelEditMessage -> {
                messageInputTextState.clear()
                _messagesUiState.update {
                    it.copy(
                        isEditingMessageId = null,
                        isEditingVersionTag = null
                    )
                }
            }

            is ConversationListUiAction.DeleteMessage -> {
                val messages = _messagesUiState.value.messages.mapNotNull {
                    if (it is MessageListContentModel.Message) it.message else null
                }
                val message = messages.firstOrNull { it.id == action.messageId } ?: return
                val isCurrentUserMessage =
                    message.originalAuthor?.domainName == _uiState.value.ownerSession?.odinId?.domainName
                _uiState.update {
                    it.copy(
                        uiDialog = DeleteMessage(
                            messageId = action.messageId,
                            allowDeleteForEveryone = isCurrentUserMessage
                        )
                    )
                }
            }

            is ConversationListUiAction.ShareMedia -> {
                viewModelScope.launch {
                    try {
                        val messageModel =
                            _messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
                                .find { it.message.id == action.messageId } ?: return@launch
                        val message = messageModel.message
                        val payload =
                            message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                        val payloadIv = Base64.decode(
                            payload.iv ?: throw IllegalStateException(
                                "encrypted payload requires key header"
                            )
                        )
                        val bytes = chatMessageActionService.getPayloadBytes(
                            message.fileId,
                            action.payloadKey,
                            KeyHeader(payloadIv, message.keyHeader.aesKey)
                        )
                        if (bytes != null) {
                            var extension = payload.contentType?.substringAfter("/") ?: "bin"
                            extension = when (extension) {
                                "jpeg" -> "jpg"
                                else -> extension
                            }
                            val tempPath = fileOperationsProvider.writeBytesToTempFile(
                                bytes, "share_", ".$extension"
                            )
                            sendEvent(ShareFile(tempPath))
                        } else {
                            sendEvent(
                                ShowErrorMessage(
                                    "Failed to download file for sharing"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to share media: ${e.message}"
                        }
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to share: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.ShareMessage -> {
                val message = action.message
                val filteredPayloads = message.payloads?.filter {
                    !listOf(
                        ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB,
                        ChatProtocol.DEFAULT_PAYLOAD_KEY,
                        ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY
                    ).contains(it.key)
                }
                val hasMedia = !filteredPayloads.isNullOrEmpty()
                if (hasMedia) {
                    // Share the first media payload as a file
                    val payload = filteredPayloads.first()
                    onAction(ConversationListUiAction.ShareMedia(message.id, payload.key))
                } else {
                    // Text-only message
                    val text = message.content
                    if (text.isNotBlank()) {
                        sendEvent(ShareText(text))
                    }
                }
            }

            is ConversationListUiAction.DownloadMedia -> {
                val message =
                    _messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
                        .map { it.message }.find { it.id == action.messageId } ?: return

                val fileKey = "${message.id}_${action.payloadKey}"

                // 1. Add to downloadingFiles set
                _uiState.update { it.copy(downloadingFiles = it.downloadingFiles + fileKey) }

                viewModelScope.launch {
                    try {

                        val payload =
                            message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                        val payloadIv = Base64.decode(
                            payload.iv ?: throw IllegalStateException(
                                "encrypted payload requires key header"
                            )
                        )
                        val fileBytes = chatMessageActionService.getPayloadBytes(
                            message.fileId,
                            action.payloadKey,
                            KeyHeader(payloadIv, message.keyHeader.aesKey)
                        )

                        val fileName = payload.descriptorContent ?: payload.key

                        if (fileBytes != null) {
                            var extension = payload.contentType?.substringAfter("/") ?: "bin"
                            extension = when (extension) {
                                "jpeg" -> "jpg"
                                else -> extension
                            }
                            val tempFile = fileOperationsProvider.writeBytesToTempFile(
                                fileBytes, fileName, ".$extension"
                            )
                            sendEvent(OpenFile(tempFile))
                        } else {
                            sendEvent(
                                ShowErrorMessage(
                                    "Could not download file"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Error downloading file: ${e.message}"
                            )
                        )
                    } finally {
                        // 4. Remove from downloadingFiles set
                        _uiState.update {
                            it.copy(downloadingFiles = it.downloadingFiles - fileKey)
                        }
                    }
                }
            }

            is ConversationListUiAction.SaveFile -> {
                sendEvent(ShowErrorMessage("Not implemented yet"))
            }

            is ConversationListUiAction.DeleteMessageForEveryone -> {
                viewModelScope.launch {
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

            is ConversationListUiAction.DeleteMessageForMe -> {
                viewModelScope.launch {
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

            is ConversationListUiAction.MarkAsRead -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.markAsRead(listOf(action.messageId))
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to mark message as read: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.ToggleReaction -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.toggleReaction(
                            action.conversationId,
                            action.messageId,
                            action.reaction
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to toggle reaction: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.SendFile -> {
                _messagesUiState.update { it.copy(scrollPosition = null, fullScreenOverlay = null) }

                addMessageWithFiles(
                    conversationId = action.conversationId,
                    content = action.message,
                    files = action.attachments,
                )
                messageInputTextState.clear()
            }

            is ConversationListUiAction.AttachPlatformFile -> {
                viewModelScope.launch {
                    try {
                        val newFiles = action.files.map {
                            if (action.isImage) AttachmentPendingFile.FileImage(
                                Uuid.generateV7(),
                                it
                            )
                            else AttachmentPendingFile.File(Uuid.generateV7(), it)
                        }
                        val conversation = _uiState.value.activeConversations.find {
                            it.id == action.conversationId
                        }
                        if (newFiles.isEmpty() || conversation == null) return@launch

                        val overlay = _messagesUiState.value.fullScreenOverlay
                        val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                            overlay.copy(
                                attachments = overlay.attachments + newFiles,
                            )
                        } else {
                            FullScreenOverlay.AttachmentData(
                                conversationTitle = conversation.name,
                                conversationId = action.conversationId,
                                selected = newFiles.last().attachmentId,
                                attachments = newFiles,
                            )
                        }

                        _messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = newOverlay,
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to attach file(s): ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.AttachGalleryItem -> {
                viewModelScope.launch {
                    try {
                        val newFiles = action.files.map {
                            AttachmentPendingFile.Gallery(Uuid.generateV7(), it)
                        }
                        val conversation = _uiState.value.activeConversations.find {
                            it.id == action.conversationId
                        }
                        if (newFiles.isEmpty() || conversation == null) return@launch

                        val overlay = _messagesUiState.value.fullScreenOverlay
                        val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                            overlay.copy(
                                attachments = overlay.attachments + newFiles,
                            )
                        } else {
                            FullScreenOverlay.AttachmentData(
                                conversationTitle = conversation.name,
                                conversationId = action.conversationId,
                                selected = newFiles.last().attachmentId,
                                attachments = newFiles,
                            )
                        }

                        _messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = newOverlay,
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to attach file(s): ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.UnAttachFile -> {
                viewModelScope.launch {
                    try {
                        val fullScreenOverlay = _messagesUiState.value.fullScreenOverlay
                        if (fullScreenOverlay == null || fullScreenOverlay !is FullScreenOverlay.AttachmentData) return@launch

                        val newFiles = fullScreenOverlay.attachments.filter {
                            it.attachmentId != action.id
                        }
                        _messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = fullScreenOverlay.copy(attachments = newFiles),
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to unattach file", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to unattach file: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.MediaClicked -> {
                viewModelScope.launch {
                    try {
                        val selectedPayload =
                            action.message.payloads?.firstOrNull { it.key == action.payloadKey }
                                ?: return@launch
                        val contentType = selectedPayload.contentType ?: ""
                        when {
                            contentType.startsWith("image/") -> {
                                Logger.d("Image clicked: ${action.message.id}:${action.payloadKey}")

                                _messagesUiState.update {
                                    it.copy(
                                        fullScreenOverlay = FullScreenOverlay.ViewMessageData(
                                            messageId = action.message.id,
                                            title = action.message.originalAuthor?.domainName
                                                ?: "null",
                                            created = action.message.created,
                                            content = action.message.content,
                                            fileId = action.message.fileId,
                                            driveId = chatTargetDrive.alias,
                                            payloads = action.message.payloads,
                                            selectedPayloadKey = action.payloadKey,
                                            keyHeader = action.message.keyHeader,
                                        )
                                    )
                                }
                            }

                            contentType.startsWith("video/") || contentType == "application/vnd.apple.mpegurl" -> {
                            }

                            contentType.startsWith("audio/") -> {}
                            contentType.startsWith("application/") || contentType.startsWith("text/") || contentType.startsWith(
                                "message/"
                            ) -> {
                                onAction(
                                    ConversationListUiAction.DownloadMedia(
                                        action.message.id, action.payloadKey
                                    )
                                )
                            }

                            else -> {
                                onAction(
                                    ConversationListUiAction.DownloadMedia(
                                        action.message.id, action.payloadKey
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to handle media click", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to handle media click: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.CloseFullScreenOverlay -> {
                _messagesUiState.update { it.copy(fullScreenOverlay = null) }
            }

            is ConversationListUiAction.ReplyToMessage -> {
                _messagesUiState.update { it.copy(replyToMessage = action.message) }
            }

            is ConversationListUiAction.CancelReplyToMessage -> {
                _messagesUiState.update { it.copy(replyToMessage = null) }
            }

            is ConversationListUiAction.ShowReactionDetails -> {
                loadReactionDetails(action.messageId)
            }

            is ConversationListUiAction.HideReactionDetails -> {
                _messagesUiState.update { it.copy(messageReactions = null) }
            }

            is ConversationListUiAction.ShowContactInfo -> {
                // ignore if click on own contact
                if (action.odinId == uiState.value.ownerSession?.odinId?.domainName) return
                _uiState.update {
                    it.copy(
                        uiEvent = NavigateToContactInfo((action.odinId))
                    )
                }
            }

            is ConversationListUiAction.ShowMessageInfo -> {
                _uiState.update {
                    it.copy(
                        uiEvent = NavigateToMessageInfo((action.message))
                    )
                }
            }

            /* Conversation options */
            is ConversationListUiAction.ShowConversationSettings -> {
                if (action.conversation.isGroupConversation) {
                    _uiState.update {
                        it.copy(
                            uiEvent = NavigateToGroupSettings(
                                (action.conversation.id.toString())
                            )
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            uiEvent = NavigateToConversationSettings(
                                (action.conversation.id.toString())
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.IntroduceEveryone -> {
                introduceEveryone(action.conversationId)
            }

            is ConversationListUiAction.ArchiveConversation -> {
                // TODO
                println("Unhandled: $action")
            }

            is ConversationListUiAction.ClearConversation -> {
                // TODO
                println("Unhandled: $action")
            }

            is ConversationListUiAction.DeleteConversation -> {
                // TODO
                println("Unhandled: $action")
            }

            /* Audio recording */
            is ConversationListUiAction.ShowRecordingHelp -> {
                sendEvent(ShowInfoMessage(MR.string.chat_message_audio_recording_help))
            }

            is ConversationListUiAction.StartRecording -> {
                viewModelScope.launch {
                    try {
                        val file =
                            PlatformFile(FileKit.filesDir, "recording-${Uuid.random()}.mp3")
                        audioRecorder.startRecording(file.toString())
                        _messagesUiState.update {
                            it.copy(
                                recordingData = RecordingData(file = file, conversationId = action.conversationId)
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(ShowErrorMessage("Failed to start recording: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.StopRecording -> {
                viewModelScope.launch {
                    try {
                        audioRecorder.stopRecording()
                        _messagesUiState.value.recordingData?.let { recordingData ->
                            addMessageWithFiles(
                                conversationId = recordingData.conversationId,
                                content = "",
                                files = listOf(AttachmentPendingFile.File(Uuid.random(), recordingData.file)),
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(ShowErrorMessage("Failed to send recording: ${e.message}"))
                    }
                    _messagesUiState.update { it.copy(recordingData = null) }
                }
            }

            is ConversationListUiAction.CancelRecording -> {
                viewModelScope.launch {
                    try {
                        audioRecorder.stopRecording()
                        _messagesUiState.value.recordingData?.file?.delete(mustExist = false)
                    } catch (_: Exception) {
                        // ignore
                    }
                    _messagesUiState.update { it.copy(recordingData = null) }
                }
            }
        }
    }

    private fun introduceEveryone(conversationId: Uuid) {
        viewModelScope.launch {
            val defaultMessage =
                "${_uiState.value.ownerSession?.displayName ?: "Unknown"} has added you to group chat"
            conversationService.introduceEveryone(conversationId, defaultMessage)
            sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
        }
    }

    private fun updateListContent() {
        viewModelScope.launch {
            try {
                val searchQuery = conversationSearchTextState.text.toString()
                val filterByUnread = uiState.value.filterByUnread
                val conversationsPool =
                    if (filterByUnread) uiState.value.activeConversations.filter {
                        it.unreadCount > 0 || it.id == uiState.value.selectedConversationId
                    }
                    else uiState.value.activeConversations

                if (searchQuery.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            conversationsContent = if (conversationsPool.isEmpty()) ConversationListContentState.Empty
                            else ConversationListContentState.Items(conversationsPool.map { conv ->
                                ConversationListContentModel.Conversation(conv)
                            }.toPersistentList())
                        )
                    }
                } else {
                    val result = mutableListOf<ConversationListContentModel>()

                    val conversations = conversationsPool.filter { conversation ->
                        conversation.name.contains(searchQuery, ignoreCase = true)
                    }.toPersistentList()
                    if (conversations.isNotEmpty()) {
                        result.add(
                            ConversationListContentModel.Header(
                                MR.string.chat_search_result_conversations
                            )
                        )
                        result.addAll(
                            conversations.map { ConversationListContentModel.Conversation(it) })
                    }

                    // Only search for message if filter by unread conversations filter is not
                    // enabled
                    if (!filterByUnread) {
                        val messages = chatMessageStream.searchMessages(searchQuery).records
                        if (messages.isNotEmpty()) {
                            result.add(
                                ConversationListContentModel.Header(
                                    MR.string.chat_search_result_messages
                                )
                            )
                            result.addAll(messages.map { ConversationListContentModel.Message(it) })
                        }
                    }

                    _uiState.update {
                        it.copy(
                            conversationsContent = if (result.isEmpty()) ConversationListContentState.EmptySearch(
                                searchQuery
                            )
                            else ConversationListContentState.Items(
                                result.toPersistentList()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to load conversations: ${e.message}"))
            }
        }
    }

    private fun loadReactionDetails(messageId: Uuid) {
        viewModelScope.launch {
            val messageReactions = chatMessageActionService.getReactions(messageId)
            _messagesUiState.update { it.copy(messageReactions = messageReactions) }
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid, messageIdForScroll: Uuid?) {
        // When loading message for newly selected conversation, cancel any previous job to
        // avoid observing multiple messageStreams
        currentConversationJob?.cancel()
        currentConversationJob = viewModelScope.launch {
            try {
                chatMessageStream.loadConversation(conversationId)
                chatMessageStream.observeMessages(conversationId).collect { messages ->

                    // Group messages within day sections
                    val timezone = TimeZone.currentSystemDefault()
                    val groupedMessages = messages.sortedBy { it.created }.groupBy { message ->
                        val date = message.created.toLocalDateTime(timezone).date
                        date
                    }
                    val messagesModels: List<MessageListContentModel> =
                        groupedMessages.flatMap { (date, messages) ->
                            listOf(MessageListContentModel.Section(date)) + messages.map {
                                MessageListContentModel.Message(
                                    it
                                )
                            }
                        }

                    // Scroll handling, either use new message id, click message id or null
                    val newMessageId = messages.firstOrNull { it.id == pendingMessageId }?.id
                    pendingMessageId = null
                    val indexOfMessageForScroll = if (newMessageId != null) {
                        Logger.i("Resetting scroll position, new message seen")
                        messagesModels.indexOfLast {
                            it is MessageListContentModel.Message && it.message.id == newMessageId
                        } + 1
                    } else {
                        if (messageIdForScroll == null) null
                        else messagesModels.indexOfLast {
                            it is MessageListContentModel.Message && it.message.id == messageIdForScroll
                        } + 1
                    }

                    _uiState.value = _uiState.value.copy(
                        selectedConversationId = conversationId,
                    )

                    _messagesUiState.update {
                        it.copy(
                            messages = messagesModels.toPersistentList(),
                            scrollPosition = if (indexOfMessageForScroll == null) {
                                getScrollPosition(conversationId)
                            } else {
                                ScrollPosition(indexOfMessageForScroll)
                            },
                        )
                    }
                }
            } catch (_: CancellationException) {
                // ignore
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to load messages: ${e.message}"))
            }
        }
    }

    private fun getScrollPosition(conversationId: Uuid): ScrollPosition? {
        val firstVisibleItemIndex =
            userPreferences.getConversationScrollIndex(conversationId.toString())
        val firstVisibleItemScrollOffset =
            userPreferences.getConversationScrollOffset(conversationId.toString())

        if (firstVisibleItemIndex != null && firstVisibleItemScrollOffset != null) {
            return ScrollPosition(
                firstVisibleItemIndex = firstVisibleItemIndex,
                firstVisibleItemScrollOffset = firstVisibleItemScrollOffset
            )
        }
        return null
    }

    private fun sendEvent(event: ConversationListUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

    private fun editMessage(messageId: Uuid, versionTag: Uuid, content: String) {
        viewModelScope.launch {
            try {
                chatMessageSenderService.updateMessage(
                    messageId = messageId,
                    versionTag = versionTag,
                    content = content
                )
                _messagesUiState.update {
                    it.copy(
                        isEditingMessageId = null,
                        isEditingVersionTag = null
                    )
                }
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to edit message: ${e.message}"))
            }
        }
    }

    private fun addMessage(
        conversationId: Uuid,
        content: String,
        linkPreview: LinkPreview? = null
    ) {
        viewModelScope.launch {
            try {
                val payloadBundle = linkPreview?.let {
                    LinkPreviewPayloadBuilder.build(it, fileOperationsProvider)
                }

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle,
                )
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to send message: ${e.message}"))
            }
        }
    }

    private fun replyToMessage(
        conversationId: Uuid,
        replyTo: MessageUiModel,
        content: String,
        linkPreview: LinkPreview? = null
    ) {
        viewModelScope.launch {
            try {
                val payloadBundle = linkPreview?.let {
                    LinkPreviewPayloadBuilder.build(it, fileOperationsProvider)
                }

                val replyPreview = ReplyPreview(
                    replyUniqueId = replyTo.id,
                    authorOdinId = replyTo.originalAuthor?.domainName ?: "null",
                    message = replyTo.content.truncateToCodePoints(80),
                    previewThumbnail = replyTo.previewThumbnail
                )
                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                chatMessageSenderService.replyToMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    replyTo = replyPreview,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle
                )
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to send reply: ${e.message}"))
            }
        }
    }

    private fun addMessageWithFiles(
        conversationId: Uuid,
        content: String,
        files: List<AttachmentPendingFile>
    ) {
        viewModelScope.launch {
            try {
                val attachments = mutableListOf<AttachmentInput>()
                files.forEach { attachment ->
                    when (attachment) {
                        is AttachmentPendingFile.File -> {
                            attachments.add(
                                AttachmentInput(
                                    filePath = attachment.file.toString(),
                                    contentType = detectContentTypeFromExtensionOrHint(
                                        attachment.file.name
                                    ),
                                    displayName = attachment.file.name,
                                )
                            )
                        }

                        is AttachmentPendingFile.FileImage -> {
                            attachments.add(
                                AttachmentInput(
                                    filePath = attachment.file.toString(),
                                    contentType = detectContentTypeFromExtensionOrHint(
                                        attachment.file.name
                                    ),
                                    displayName = attachment.file.name,
                                )
                            )
                        }

                        is AttachmentPendingFile.Gallery -> {
                            attachments.add(
                                AttachmentInput(
                                    filePath = attachment.image.file.toString(),
                                    contentType = detectContentTypeFromExtensionOrHint(
                                        attachment.image.fileName
                                    ),
                                    displayName = attachment.image.fileName,
                                )
                            )
                        }
                    }
                }

                val bundle = MessageAttachmentBuilder.build(
                    attachments = attachments,
                    fileOperationsProvider = fileOperationsProvider,
                    payloadKeyFactory = { index, _ ->
                        "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                    })

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = bundle,
                )
            } catch (e: Exception) {
                Logger.e("Failed to send file(s)", e)
                sendEvent(
                    ShowErrorMessage(
                        "Failed to send file(s): ${e.message}"
                    )
                )
            }
        }
    }
}
