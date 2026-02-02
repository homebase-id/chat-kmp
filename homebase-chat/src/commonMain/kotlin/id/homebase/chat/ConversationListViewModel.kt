package id.homebase.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.writeBytesToTempFile
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageReaderService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ContactService
import id.homebase.chat.services.ConversationService
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.ScrollPosition
import io.github.vinceglb.filekit.FileKit
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.collections.immutable.persistentListOf


class ChatListViewModel(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val conversationService: ConversationService,
    private val chatMessageService: ChatMessageReaderService,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val chatMessageActionService: ChatMessageActionService,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val domain =
                credentialsManager
                    .requireActiveCredentials()
                    .domain
                    .trim()
                    .lowercase()

            _uiState.update {
                it.copy(currentOdinId = domain)
            }
        }

        viewModelScope.launch {
            conversationService.start()
            conversationService.conversations.collect { conversations ->
                val sorted = conversations.sortedByDescending { it.timestamp }
                _uiState.value = _uiState.value.copy(
                    conversations = sorted.toPersistentList()
                )
            }
        }

        contactService.start()
        viewModelScope.launch {
            contactService.contacts.collect { contacts ->
                _uiState.value = _uiState.value.copy(
                    contacts = contacts.toPersistentList()
                )
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
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
                _uiState.value = _uiState.value.copy(
                    showingNewChatPane = true,
                    searchQuery = ""
                )
            }

            ConversationListUiAction.BackToListClicked -> {
                _uiState.value = _uiState.value.copy(
                    showingNewChatPane = false,
                    searchQuery = ""
                )
            }

            is ConversationListUiAction.ContactClicked -> {
//                val conversation = apiProvider.createConversationFromContact(action.contact)
//                _uiState.value = _uiState.value.copy(
//                    showingNewChatPane = false,
//                    searchQuery = ""
//                )
//                loadMessagesForConversation(conversation.id)
            }

            is ConversationListUiAction.SearchQueryChanged -> {
                _uiState.value = _uiState.value.copy(
                    searchQuery = action.query
                )
            }

            is ConversationListUiAction.SendMessage -> {
                if (action.content.isNotBlank()) {
                    addMessage(
                        conversationId = action.conversationId,
                        content = action.content
                    )
                }
            }

            is ConversationListUiAction.SaveScrollPosition -> {
                _uiState.update {
                    it.copy(
                        conversationScrollPosition = ScrollPosition(
                            action.firstVisibleItemIndex,
                            action.firstVisibleItemScrollOffset
                        )
                    )
                }

                // Persist to user settings
                viewModelScope.launch {
                    userPreferences.setConversationScrollIndex(
                        action.conversationId.toString(),
                        action.firstVisibleItemIndex
                    )
                    userPreferences.setConversationScrollOffset(
                        action.conversationId.toString(),
                        action.firstVisibleItemScrollOffset
                    )
                }
            }

            is ConversationListUiAction.DeleteMessageForEveryone -> {

                viewModelScope.launch {
                    chatMessageActionService.deleteMessage(
                        action.messageId,
                        deleteForEveryone = true
                    )
                }
            }

            is ConversationListUiAction.DeleteMessageForMe -> {

                viewModelScope.launch {
                    chatMessageActionService.deleteMessage(
                        action.messageId,
                        deleteForEveryone = false
                    )
                }
            }

            is ConversationListUiAction.MarkAsRead -> {
                viewModelScope.launch {
                    chatMessageActionService.markAsRead(listOf(action.messageId))
                }
            }

            is ConversationListUiAction.AddReaction -> {
                viewModelScope.launch {
                    chatMessageActionService.addReaction(action.messageId, action.reaction)
                }
            }

            is ConversationListUiAction.DeleteReaction -> {
                viewModelScope.launch {
                    chatMessageActionService.deleteReaction(action.messageId, action.reaction)
                }
            }

//            is ConversationListUiAction.ArchiveConversation -> TODO()
//            is ConversationListUiAction.ClearConversation -> TODO()
//            is ConversationListUiAction.DeleteConversation -> TODO()
//            is ConversationListUiAction.EditMessage -> TODO()
//            is ConversationListUiAction.ReplyToMessage -> TODO()
//            is ConversationListUiAction.ShowConversationInfo -> TODO()
//            is ConversationListUiAction.ShowMessageInfo -> TODO()
//            is ConversationListUiAction.StarMessage -> TODO()


            ConversationListUiAction.AttachmentPickCancelled -> TODO()

            is ConversationListUiAction.AttachmentPickFailed -> TODO()
            is ConversationListUiAction.FilePicked -> TODO()
            is ConversationListUiAction.ImagePicked -> TODO()
            is ConversationListUiAction.VideoPicked -> TODO()

            ConversationListUiAction.PickImage -> pickImage()
            ConversationListUiAction.PickVideo -> pickVideo()
            ConversationListUiAction.PickFile -> TODO()

            else -> {
                println("Unhandled action: $action")
            }
        }
    }


    private fun pickImage() {
        pick(FileKitType.Image) { file ->
            viewModelScope.launch {
                val bytes = file.readBytes()
                val tempPath = writeBytesToTempFile(
                    bytes = bytes,
                    prefix = "img_",
                    suffix = "_${file.name}"
                )

                _uiState.update { state ->
                    state.copy(
                        pendingAttachments =
                            state.pendingAttachments + AttachmentInput(
                                filePath = tempPath,
                                contentType = "image/png", // TODO: detect properly
                                displayName = file.name
                            )
                    )
                }
            }

            // Return value ignored; work is done above
            ConversationListUiAction.AttachmentPickCancelled
        }
    }


    private fun pickVideo() {
        pick(FileKitType.ImageAndVideo) { file ->
            viewModelScope.launch {
                val bytes = file.readBytes()
                val tempPath = writeBytesToTempFile(
                    bytes = bytes,
                    prefix = "vid_",
                    suffix = "_${file.name}"
                )

                _uiState.update { state ->
                    state.copy(
                        pendingAttachments =
                            state.pendingAttachments + AttachmentInput(
                                filePath = tempPath,
                                contentType = "video/mp4", // TODO detect
                                displayName = file.name
                            )
                    )
                }
            }

            ConversationListUiAction.AttachmentPickCancelled
        }
    }


    private fun pick(
        type: FileKitType, onPicked: (PlatformFile) -> ConversationListUiAction
    ) = viewModelScope.launch {
        try {
            val file = FileKit.openFilePicker(type)
            if (file != null) {
                onAction(onPicked(file))
            } else {
                onAction(ConversationListUiAction.AttachmentPickCancelled)
            }
        } catch (e: Exception) {
            onAction(
                ConversationListUiAction.AttachmentPickFailed(
                    e.message ?: "Pick failed"
                )
            )
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid) {
        viewModelScope.launch {
            chatMessageService.loadConversation(conversationId)

            chatMessageService
                .observeMessages(conversationId).collect { messages ->
                    val sorted = messages.sortedBy { it.created }
                    _uiState.value = _uiState.value.copy(
                        selectedConversationId = conversationId,
                        currentConversationMessages = sorted.toPersistentList(),
                        conversationScrollPosition = getScrollPosition(conversationId),
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

    fun addMessage(
        conversationId: Uuid,
        content: String
    ) {
        viewModelScope.launch {
            val attachments = _uiState.value.pendingAttachments

            val bundle =
                if (attachments.isNotEmpty()) {
                    MessageAttachmentBuilder.build(attachments)
                } else {
                    null
                }

            chatMessageSenderService.sendNewMessage(
                conversationId = conversationId,
                messageText = content,
                payloadBundle = bundle
            )

            // clear attachments after successful send
            _uiState.update {
                it.copy(pendingAttachments = persistentListOf())
            }
        }
    }
}
