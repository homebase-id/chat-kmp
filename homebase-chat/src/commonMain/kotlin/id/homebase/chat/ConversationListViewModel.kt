package id.homebase.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageReaderService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ContactService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStreamService
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import io.github.vinceglb.filekit.name
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatListViewModel(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val conversationService: ConversationStreamService,
    private val chatMessageService: ChatMessageReaderService,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val chatMessageActionService: ChatMessageActionService,
    private val userPreferences: UserPreferences,
    private val fileOperationsProvider: FileOperationsProvider,
    private val conversationWriterService: ConversationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val domain = credentialsManager.requireActiveCredentials().domain.trim().lowercase()

            _uiState.update { it.copy(currentOdinId = domain) }
        }

        viewModelScope.launch {
            conversationService.start()
            conversationService.conversations.collect { conversations ->
                val sorted = conversations.sortedByDescending { it.timestamp }
                _uiState.value = _uiState.value.copy(conversations = sorted.toPersistentList())
            }
        }

        contactService.start()
        viewModelScope.launch {
            contactService.contacts.collect { contacts ->
                _uiState.value = _uiState.value.copy(contacts = contacts.toPersistentList())
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
                if (action.content.isNotBlank()) {
                    val replyTo = _uiState.value.replyToMessage
                    if (replyTo != null) {
                        replyToMessage(
                            conversationId = action.conversationId,
                            replyTo = replyTo,
                            content = action.content
                        )
                        _uiState.update { it.copy(replyToMessage = null) }
                    } else {
                        addMessage(conversationId = action.conversationId, content = action.content)
                    }
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
                val isCurrentUserMessage = message.senderId == _uiState.value.currentOdinId
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

            is ConversationListUiAction.DeleteMessageForEveryone -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.deleteMessage(
                            action.messageId, deleteForEveryone = true
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
                            action.messageId, deleteForEveryone = false
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
                        chatMessageActionService.addReaction(action.messageId, action.reaction)
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
                        chatMessageActionService.deleteReaction(action.messageId, action.reaction)
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
                        val attachments = mutableListOf<AttachmentInput>()
                        action.files.forEach { file ->
                            val filePath = file.toString()
                            attachments.add(
                                AttachmentInput(
                                    filePath = filePath,
                                    contentType = detectContentTypeFromExtensionOrHint(filePath),
                                    displayName = file.name,
                                )
                            )
                        }

                        val bundle =
                            MessageAttachmentBuilder.build(attachments, fileOperationsProvider)

                        chatMessageSenderService.sendNewMessage(
                            conversationId = action.conversationId,
                            messageText = action.message,
                            payloadBundle = bundle
                        )
                    } catch (e: Exception) {
                        Logger.e("Failed to send file(s)", e)
                        sendEvent(
                            ConversationListUiEvent.ShowErrorMessage(
                                "Failed to send file(s): ${e.message}"
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

                                _uiState.update {
                                    it.copy(
                                        fullScreenMedia = FullScreenMessageData(
                                            messageId = action.message.id,
                                            title = action.message.senderId,
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

            ConversationListUiAction.CloseFullScreenMedia -> {
                _uiState.update { it.copy(fullScreenMedia = null) }
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

            ConversationListUiAction.CancelReplyToMessage -> {
                _uiState.update { it.copy(replyToMessage = null) }
            }

            else -> {
                println("Unhandled action: $action")
            }
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid) {
        viewModelScope.launch {
            try {
                chatMessageService.loadConversation(conversationId)

                chatMessageService.observeMessages(conversationId).collect { messages ->
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
                    conversationId = conversationId,
                    messageText = content,
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
                    authorOdinId = replyTo.senderOdinId,
                    message = replyTo.content.take(80),
                    previewThumbnail = replyTo.previewThumbnail
                )
                chatMessageSenderService.replyToMessage(
                    conversationId = conversationId,
                    replyTo = replyPreview,
                    messageText = content,
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
