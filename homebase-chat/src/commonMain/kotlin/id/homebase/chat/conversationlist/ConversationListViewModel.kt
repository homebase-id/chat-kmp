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
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.resources.MR
import id.homebase.resources.chat_search_result_conversations
import id.homebase.resources.chat_search_result_messages
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid

@OptIn(FlowPreview::class)
class ConversationListViewModel(
    savedStateHandle: SavedStateHandle,
    private val credentialsManager: CredentialsManager,
    private val conversationStream: ConversationStream,
    private val chatMessageStream: ChatMessageStream,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val chatMessageActionService: ChatMessageActionService,
    private val userPreferences: UserPreferences,
    private val fileOperationsProvider: FileOperationsProvider,
    private val ownerSessionRepository: OwnerSessionRepository

) : ViewModel() {

    val ownerSession = ownerSessionRepository.user

    val chatListRoute = savedStateHandle.toRoute<Route.ChatList>()

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    val conversationSearchTextState = TextFieldState()
    val messageInputTextState = RichTextState().applyDefaultStyling()
    var currentConversationJob: Job? = null

    init {
        viewModelScope.launch {
            val domain = credentialsManager.requireActiveCredentials().domain.domainName
            _uiState.update { it.copy(currentOdinId = domain) }
        }

        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
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
            chatListRoute.conversationId?.let {
                loadMessagesForConversation(Uuid.parse(it), null)
            }
        }

        // Listen for search query changes
        viewModelScope.launch {
            snapshotFlow { conversationSearchTextState.text.toString() }.debounce(300)
                .collectLatest {
                    updateListContent()
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

            ConversationListUiAction.BackClicked -> {
                sendEvent(ConversationListUiEvent.NavigateBack)
            }

            ConversationListUiAction.SearchClicked -> {
                _uiState.update { it.copy(isSearchActive = true) }
            }

            ConversationListUiAction.SearchBackClicked -> {
                _uiState.update { it.copy(isSearchActive = false) }
            }

            ConversationListUiAction.NewConversationClicked -> {
                _uiState.value =
                    _uiState.value.copy(uiEvent = ConversationListUiEvent.NavigateToNewConversation)
            }

            ConversationListUiAction.ClearSelection -> {
                // Clear the selected conversation when user navigates back to list
                _uiState.update { it.copy(selectedConversationId = null) }
            }

            ConversationListUiAction.FilterByUnreadClicked -> {
                _uiState.update { it.copy(filterByUnread = true) }
                updateListContent()
            }

            ConversationListUiAction.ClearFilterByUnreadClicked -> {
                _uiState.update { it.copy(filterByUnread = false) }
                updateListContent()
            }

            is ConversationListUiAction.SendMessage -> {
                val hasMessage = !messageInputTextState.annotatedString.isBlank()
                if (hasMessage) {
                    val content = messageInputTextState.toMarkdown()
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
                    messageInputTextState.clear()
                }
            }

            is ConversationListUiAction.SaveScrollPosition -> {
                _uiState.update {
                    it.copy(
                        conversationScrollPosition =
                            ScrollPosition(
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
                val hasMessage = !messageInputTextState.annotatedString.isBlank()
                if (hasMessage) {

                    val content = messageInputTextState.toMarkdown()
                    editMessage(
                        messageId = action.messageId,
                        content = content
                    )
                    messageInputTextState.clear()
                }
            }

            is ConversationListUiAction.DeleteMessage -> {
                val messages =
                    uiState.value.currentConversationMessages.mapNotNull { if (it is MessageListContentModel.Message) it.message else null }
                val message = messages.firstOrNull {
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
                        chatMessageActionService.deleteMessageClassic(
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
                        chatMessageActionService.deleteMessageClassic(
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
                        val messageReactions =
                            chatMessageActionService.getReactions(action.messageId)
                        val remove =
                            messageReactions.any { it.emoji == action.reaction && it.odinId.domainName == _uiState.value.currentOdinId }
                        if (remove) {
                            chatMessageActionService.deleteReaction(
                                action.conversationId,
                                action.messageId,
                                action.reaction
                            )
                        } else {
                            chatMessageActionService.addReaction(
                                action.conversationId,
                                action.messageId,
                                action.reaction
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(
                            ConversationListUiEvent.ShowErrorMessage(
                                "Failed to add reaction: ${e.message}"
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

                        val bundle = MessageAttachmentBuilder.build(
                            attachments = attachments,
                            fileOperationsProvider = fileOperationsProvider,
                            payloadKeyFactory = { index, _ ->
                                "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                            }
                        )

                        chatMessageSenderService.sendNewMessage(
                            messageUniqueId = Uuid.random(),
                            conversationId = action.conversationId,
                            messageText = action.message,
                            previousMessageUniqueId = null,
                            payloadBundle = bundle,
                        )
                        messageInputTextState.clear()
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
                            _uiState.value.activeConversations.find { it.id == action.conversationId }
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
                            _uiState.value.activeConversations.find { it.id == action.conversationId }
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

            is ConversationListUiAction.ShowContactInfo -> {
                // ignore if click on own contact
                if (action.odinId == uiState.value.currentOdinId) return
                _uiState.update { it.copy(uiEvent = ConversationListUiEvent.NavigateToContactInfo((action.odinId))) }
            }

            is ConversationListUiAction.ShowConversationSettings -> {
                if (action.conversation.isGroupConversation) {
                    _uiState.update {
                        it.copy(uiEvent = ConversationListUiEvent.NavigateToGroupSettings((action.conversation.id.toString())))
                    }
                } else {
                    _uiState.update {
                        it.copy(uiEvent = ConversationListUiEvent.NavigateToConversationSettings((action.conversation.id.toString())))
                    }
                }
            }

            is ConversationListUiAction.ShowMessageInfo -> {
                _uiState.update { it.copy(uiEvent = ConversationListUiEvent.NavigateToMessageInfo((action.message))) }
            }

            else -> {
                println("Unhandled action: $action")
            }
        }
    }

    private fun updateListContent() {
        viewModelScope.launch {
            try {
                val searchQuery = conversationSearchTextState.text.toString()
                val filterByUnread = uiState.value.filterByUnread
                val conversationsPool =
                    if (filterByUnread) uiState.value.activeConversations.filter { it.unreadCount > 0 || it.id == uiState.value.selectedConversationId } else uiState.value.activeConversations

                if (searchQuery.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            conversationsContent = if (conversationsPool.isEmpty()) ConversationListContentState.Empty else ConversationListContentState.Items(
                                conversationsPool.map { conv ->
                                    ConversationListContentModel.Conversation(conv)
                                }.toPersistentList()
                            )
                        )
                    }
                } else {
                    val result = mutableListOf<ConversationListContentModel>()

                    val conversations = conversationsPool.filter { conversation ->
                        conversation.name.contains(searchQuery, ignoreCase = true)
                    }.toPersistentList()
                    if (conversations.isNotEmpty()) {
                        result.add(ConversationListContentModel.Header(MR.string.chat_search_result_conversations))
                        result.addAll(conversations.map {
                            ConversationListContentModel.Conversation(
                                it
                            )
                        })
                    }

                    // Only search for message if filter by unread conversations filter is not enabled
                    if (!filterByUnread) {
                        val messages = chatMessageStream.searchMessages(searchQuery).records
                        if (messages.isNotEmpty()) {
                            result.add(ConversationListContentModel.Header(MR.string.chat_search_result_messages))
                            result.addAll(messages.map { ConversationListContentModel.Message(it) })
                        }
                    }

                    _uiState.update {
                        it.copy(
                            conversationsContent = if (result.isEmpty())
                                ConversationListContentState.EmptySearch(searchQuery)
                            else
                                ConversationListContentState.Items(result.toPersistentList())
                        )
                    }
                }
            } catch (e: Exception) {
                sendEvent(
                    ConversationListUiEvent.ShowErrorMessage(
                        "Failed to load conversations: ${e.message}"
                    )
                )
            }
        }
    }

    private fun loadReactionDetails(messageId: Uuid) {
        viewModelScope.launch {
            val messageReactions = chatMessageActionService.getReactions(messageId)
            _uiState.update { it.copy(messageReactions = messageReactions) }
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid, messageId: Uuid?) {
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
                    val messages: List<MessageListContentModel> =
                        groupedMessages.flatMap { (date, messages) ->
                            listOf(MessageListContentModel.Section(date)) + messages.map {
                                MessageListContentModel.Message(
                                    it
                                )
                            }
                        }

                    val indexOfMessageForScroll =
                        if (messageId == null) null else messages.indexOfLast { it is MessageListContentModel.Message && it.message.id == messageId } + 1 // +1 for header

                    _uiState.value = _uiState.value.copy(
                        selectedConversationId = conversationId,
                        currentConversationMessages = messages.toPersistentList(),
                        conversationScrollPosition =
                            if (indexOfMessageForScroll == null) {
                                getScrollPosition(conversationId)
                            } else {
                                ScrollPosition(indexOfMessageForScroll, 0)
                            },
                    )
                }
            } catch (_: CancellationException) {
                // ignore
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

    private fun editMessage(
        messageId: Uuid,
        content: String) {
        viewModelScope.launch {
            try {
                chatMessageSenderService.updateMessage(
                    messageId = messageId,
                    content = content
                )
            } catch (e: Exception) {
                sendEvent(
                    ConversationListUiEvent.ShowErrorMessage(
                        "Failed to edit message: ${e.message}"
                    )
                )
            }
        }
    }

    private fun addMessage(conversationId: Uuid, content: String) {
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
