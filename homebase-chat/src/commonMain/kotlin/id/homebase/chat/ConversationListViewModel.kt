package id.homebase.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ContactService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import io.github.vinceglb.filekit.name
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class ConversationListViewModel(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val conversationStream: ConversationStream,
    private val chatMessageStream: ChatMessageStream,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val chatMessageActionService: ChatMessageActionService,
    private val userPreferences: UserPreferences,
    private val fileOperationsProvider: FileOperationsProvider,
    private val conversationWriterService: ConversationService,
    private val connectionRequestService: ConnectionRequestService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    val messageState = RichTextState()

    init {
        viewModelScope.launch {
            contactService.start()
            contactService.contacts.collect { contacts ->
                _uiState.value = _uiState.value.copy(
                    contacts = contacts.toPersistentList()
                )
            }

            connectionRequestService.start()
            connectionRequestService.incomingRequests.collect { requests ->
                _uiState.value = _uiState.value.copy(
                    incomingConnectionRequests = requests.toPersistentList()
                )
            }

//            connectionRequestService.outgoingRequests.collect { requests ->
//                _uiState.value = _uiState.value.copy(
//                    requests = requests.toPersistentList()
//                )
//            }
        }

        viewModelScope.launch {
            val domain = credentialsManager.requireActiveCredentials().domain.domainName
            _uiState.update { it.copy(currentOdinId = domain) }
        }

        viewModelScope.launch {
            conversationStream.start()
            conversationStream.conversations.collect { conversations ->
                val sorted = conversations.sortedByDescending { it.timestamp }
                _uiState.value = _uiState.value.copy(conversations = sorted.toPersistentList())
            }
        }

        viewModelScope.launch {
            // TODO - configure properties for textField here
            //textFieldState.config.linkColor = Color.Blue
            //textFieldState.config.linkTextDecoration = TextDecoration.Underline
            //textFieldState.config.codeSpanColor = Color.Blue
            //textFieldState.config.codeSpanBackgroundColor = Color.Magenta
            //textFieldState.config.codeSpanStrokeColor = Color.Yellow
            messageState.config.listIndent = 0

            // TODO - restore any draft message stored for conversation here
            messageState.setMarkdown("")
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
                loadMessagesForConversation(action.conversationId)
            }

            ConversationListUiAction.BackClicked -> {
                sendEvent(ConversationListUiEvent.NavigateBack)
            }

            ConversationListUiAction.NewChatClicked -> {
                _uiState.value = _uiState.value.copy(showingNewChatPane = true, searchQuery = "")
            }

            ConversationListUiAction.BackToListClicked -> {
                _uiState.value = _uiState.value.copy(showingNewChatPane = false, searchQuery = "")
            }

            is ConversationListUiAction.ContactClicked -> {
                viewModelScope.launch {
                    val conversationId = conversationWriterService.createConversation(
                        recipients = listOf(action.contact.odinId),
                        title = "",
                        payloadBundle = null,
                    )

                    _uiState.value =
                        _uiState.value.copy(showingNewChatPane = false, searchQuery = "")

                    loadMessagesForConversation(conversationId)
                }
            }

            is ConversationListUiAction.SearchQueryChanged -> {
                _uiState.value = _uiState.value.copy(searchQuery = action.query)
            }

            is ConversationListUiAction.SendMessage -> {
                val hasMessage = !messageState.annotatedString.isBlank()
                if (hasMessage) {
                    val content = messageState.toMarkdown()
                    val replyTo = _uiState.value.replyToMessage
                    if (replyTo != null) {
                        replyToMessage(
                            conversationId = action.conversationId,
                            replyTo = replyTo,
                            content = content
                        )
                        _uiState.update { it.copy(replyToMessage = null) }
                    } else {
                        addMessage(conversationId = action.conversationId, content = content)
                    }
                    messageState.clear()
                }
            }

            is ConversationListUiAction.SaveScrollPosition -> {
                _uiState.update {
                    it.copy(
                        conversationScrollPosition = ScrollPosition(
                            action.firstVisibleItemIndex, action.firstVisibleItemScrollOffset
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

            is ConversationListUiAction.DeleteMessage -> {
                val message = _uiState.value.currentConversationMessages.firstOrNull {
                    it.id == action.messageId
                } ?: return
                val isCurrentUserMessage =
                    message.originalAuthor?.domainName == _uiState.value.currentOdinId
                _uiState.update {
                    it.copy(
                        uiDialog = ConversationListUiDialog.DeleteMessage(
                            messageId = action.messageId,
                            allowDeleteForEveryone = isCurrentUserMessage
                        )
                    )
                }
            }

            is ConversationListUiAction.ShareMedia -> {
                sendEvent(ConversationListUiEvent.ShowErrorMessage("Not implemented yet"))
            }

            is ConversationListUiAction.DownloadMedia -> {
                sendEvent(ConversationListUiEvent.ShowErrorMessage("Not implemented yet"))
            }

            is ConversationListUiAction.SaveFile -> {
                sendEvent(ConversationListUiEvent.ShowErrorMessage("Not implemented yet"))
            }

            is ConversationListUiAction.DeleteMessageForEveryone -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.deleteMessage(
                            action.messageId,
                            deleteForEveryone = true
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ConversationListUiEvent.ShowErrorMessage(
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
                            action.messageId,
                            deleteForEveryone = false
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ConversationListUiEvent.ShowErrorMessage(
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
                            ConversationListUiEvent.ShowErrorMessage(
                                "Failed to mark message as read: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.AddReaction -> {
                viewModelScope.launch {
                    try {

                        // TODO - If adding reaction already added to a message from me, then remove it, how to though?
                        //chatMessageActionService.deleteReaction(action.messageId, action.reaction)

                        println(
                            "Adding reaction: ${action.reaction} - Unicode: ${
                                action.reaction.map { it.code }.joinToString(" ") {
                                    "U+${
                                        it.toString(16).uppercase().padStart(4, '0')
                                    }"
                                }
                            }"
                        )

                        chatMessageActionService.addReaction(
                            action.conversationId,
                            action.messageId,
                            action.reaction
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ConversationListUiEvent.ShowErrorMessage(
                                "Failed to add reaction: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.DeleteReaction -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.deleteReaction(
                            action.conversationId,
                            action.messageId,
                            action.reaction
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ConversationListUiEvent.ShowErrorMessage(
                                "Failed to delete reaction: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.SendFile -> {
                viewModelScope.launch {
                    try {
                        _uiState.update {
                            it.copy(
                                loadingNewMessage = true,
                                conversationScrollPosition = null,
                                fullScreenOverlay = null,
                            )
                        }
                        val attachments = mutableListOf<AttachmentInput>()
                        action.attachments.forEach { attachment ->
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

                        val bundle = MessageAttachmentBuilder
                            .build(attachments, fileOperationsProvider)

                        chatMessageSenderService.sendNewMessage(
                            messageUniqueId = Uuid.random(),
                            conversationId = action.conversationId,
                            messageText = action.message,
                            previousMessageUniqueId = null,
                            payloadBundle = bundle,
                        )
                        messageState.clear()
                        _uiState.update { it.copy(loadingNewMessage = false) }
                    } catch (e: Exception) {
                        Logger.e("Failed to send file(s)", e)
                        sendEvent(ConversationListUiEvent.ShowErrorMessage("Failed to send file(s): ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.AttachPlatformFile -> {
                viewModelScope.launch {
                    try {
                        val newFiles =
                            action.files.map { AttachmentPendingFile.File(Uuid.generateV7(), it) }
                        val conversation =
                            _uiState.value.conversations.find { it.id == action.conversationId }
                        if (newFiles.isEmpty() || conversation == null) return@launch

                        val overlay = _uiState.value.fullScreenOverlay
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

                        _uiState.update {
                            it.copy(
                                fullScreenOverlay = newOverlay,
                                loadingNewMessage = false,
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(ConversationListUiEvent.ShowErrorMessage("Failed to attach file(s): ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.AttachGalleryItem -> {
                viewModelScope.launch {
                    try {
                        val newFiles =
                            action.files.map {
                                AttachmentPendingFile.Gallery(
                                    Uuid.generateV7(),
                                    it
                                )
                            }
                        val conversation =
                            _uiState.value.conversations.find { it.id == action.conversationId }
                        if (newFiles.isEmpty() || conversation == null) return@launch

                        val overlay = _uiState.value.fullScreenOverlay
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

                        _uiState.update {
                            it.copy(
                                fullScreenOverlay = newOverlay,
                                loadingNewMessage = false,
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(ConversationListUiEvent.ShowErrorMessage("Failed to attach file(s): ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.UnAttachFile -> {
                viewModelScope.launch {
                    try {
                        val fullScreenOverlay = _uiState.value.fullScreenOverlay
                        if (fullScreenOverlay == null || fullScreenOverlay !is FullScreenOverlay.AttachmentData) return@launch

                        val newFiles =
                            fullScreenOverlay.attachments.filter { it.attachmentId != action.id }
                        _uiState.update {
                            it.copy(
                                fullScreenOverlay = fullScreenOverlay.copy(attachments = newFiles),
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to unattach file", e)
                        sendEvent(ConversationListUiEvent.ShowErrorMessage("Failed to unattach file: ${e.message}"))
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

                                _uiState.update {
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
                            contentType.startsWith("application/") -> {}
                            else -> {}
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to handle media click", e)
                        sendEvent(
                            ConversationListUiEvent.ShowErrorMessage(
                                "Failed to handle media click: ${e.message}"
                            )
                        )
                    }
                }
            }

            ConversationListUiAction.CloseFullScreenOverlay -> {
                _uiState.update { it.copy(fullScreenOverlay = null) }
            }

            //            is ConversationListUiAction.ArchiveConversation -> TODO()
            //            is ConversationListUiAction.ClearConversation -> TODO()
            //            is ConversationListUiAction.DeleteConversation -> TODO()
            //            is ConversationListUiAction.EditMessage -> TODO()
            //            is ConversationListUiAction.ShowConversationInfo -> TODO()
            //            is ConversationListUiAction.ShowMessageInfo -> TODO()
            //            is ConversationListUiAction.StarMessage -> TODO()

            is ConversationListUiAction.ReplyToMessage -> {
                _uiState.update { it.copy(replyToMessage = action.message) }
            }

            is ConversationListUiAction.CancelReplyToMessage -> {
                _uiState.update { it.copy(replyToMessage = null) }
            }

            is ConversationListUiAction.ShowReactionDetails -> {
                loadReactionDetails(action.messageId)
            }

            is ConversationListUiAction.HideReactionDetails -> {
                _uiState.update { it.copy(messageReactions = null) }
            }

            else -> {
                println("Unhandled action: $action")
            }
        }
    }

    private fun loadReactionDetails(messageId: Uuid) {
        viewModelScope.launch {
            val messageReactions = chatMessageActionService.getReactions(messageId)
            _uiState.update { it.copy(messageReactions = messageReactions) }
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid) {
        viewModelScope.launch {
            try {
                chatMessageStream.loadConversation(conversationId)

                chatMessageStream.observeMessages(conversationId).collect { messages ->
                    val sorted = messages.sortedBy { it.created }
                    _uiState.value = _uiState.value.copy(
                        selectedConversationId = conversationId,
                        currentConversationMessages = sorted.toPersistentList(),
                        conversationScrollPosition = getScrollPosition(conversationId),
                    )
                }
            } catch (e: Exception) {
                sendEvent(
                    ConversationListUiEvent.ShowErrorMessage(
                        "Failed to load messages: ${e.message}"
                    )
                )
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

    fun addMessage(conversationId: Uuid, content: String) {
        viewModelScope.launch {
            try {
                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = conversationId,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = null,
                )

                // you can also use chatMessageSenderService.replyToMessage
            } catch (e: Exception) {
                sendEvent(
                    ConversationListUiEvent.ShowErrorMessage(
                        "Failed to send message: ${e.message}"
                    )
                )
            }
        }
    }

    private fun replyToMessage(conversationId: Uuid, replyTo: MessageUiModel, content: String) {
        viewModelScope.launch {
            try {
                val replyPreview = ReplyPreview(
                    replyUniqueId = replyTo.id,
                    authorOdinId = replyTo.originalAuthor?.domainName ?: "null",
                    message = replyTo.content.truncateToCodePoints(80),
                    previewThumbnail = replyTo.previewThumbnail
                )
                chatMessageSenderService.replyToMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = conversationId,
                    replyTo = replyPreview,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = null
                )
            } catch (e: Exception) {
                sendEvent(
                    ConversationListUiEvent.ShowErrorMessage(
                        "Failed to send reply: ${e.message}"
                    )
                )
            }
        }
    }
}
