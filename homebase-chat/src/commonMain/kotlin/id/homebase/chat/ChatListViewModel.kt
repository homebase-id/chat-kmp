package id.homebase.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.chat.data.ChatMessageReaderService
import id.homebase.chat.data.ChatMessageSenderService
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ContactService
import id.homebase.chat.data.ConversationService
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.data.MockChatApiProvider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ChatListUiEvent {
    data object NavigateBack : ChatListUiEvent
}

sealed interface ChatListUiAction {
    data class ConversationClicked(val conversationId: Uuid) : ChatListUiAction
    data object BackClicked : ChatListUiAction
    data object NewChatClicked : ChatListUiAction
    data object BackToListClicked : ChatListUiAction
    data class ContactClicked(val contact: ContactUiModel) : ChatListUiAction
    data class SearchQueryChanged(val query: String) : ChatListUiAction
    data class SendMessage(val conversationId: Uuid, val content: String) : ChatListUiAction
}

@Immutable
data class ConversationUiModel(  // TODO: Move the data objects / classes into Conversation.kt ?
    val id: Uuid,
    val name: String,
    var lastMessage: String,
    var timestamp: Instant, // Timestamp of the last message in this convo
    var unreadCount: Int = 0,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val avatarTiny: ThumbnailDescriptor?,
    val participants: List<String> = listOf(),
    val isPinned: Boolean = false,
    val lastRead: Instant
) {
    public fun updateWithLatestMessage(msg : MessageUiModel)
    {
        // TODO: Should we also increase unread count here if it's a new message?
        if (msg.timestamp >= timestamp)
        {
            lastMessage = msg.messageAppData.message.truncateToCodePoints(40)
            timestamp = msg.timestamp
        }
    }
}

@Immutable
data class ChatListUiState(
    val conversations: ImmutableList<ConversationUiModel> = persistentListOf(),
    val selectedConversationId: String? = null,
    val showingNewChatPane: Boolean = false,
    val contacts: ImmutableList<ContactUiModel> = persistentListOf(),
    val searchQuery: String = "",
    val currentConversationMessages: ImmutableList<MessageUiModel> = persistentListOf(),
    val uiEvent: ChatListUiEvent? = null,
)

class ChatListViewModel(
    private val apiProvider: MockChatApiProvider,
    private val contactService: ContactService,
    private val conversationService: ConversationService,
    private val chatMessageService: ChatMessageReaderService,
    private val chatMessageSenderService: ChatMessageSenderService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            conversationService.start()
            conversationService.conversations.collect { conversations ->
                val sorted = conversations.sortedByDescending { it.timestamp }
                _uiState.value = _uiState.value.copy(
                    conversationViewModels = sorted.toPersistentList()
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

    fun onAction(action: ChatListUiAction) {
        when (action) {
            is ChatListUiAction.ConversationClicked -> {
                loadMessagesForConversation(action.conversationId)
            }

            ChatListUiAction.BackClicked -> {
                sendEvent(ChatListUiEvent.NavigateBack)
            }

            ChatListUiAction.NewChatClicked -> {
                _uiState.value = _uiState.value.copy(
                    showingNewChatPane = true,
                    searchQuery = ""
                )
            }

            ChatListUiAction.BackToListClicked -> {
                _uiState.value = _uiState.value.copy(
                    showingNewChatPane = false,
                    searchQuery = ""
                )
            }

            is ChatListUiAction.ContactClicked -> {
                val conversation = apiProvider.createConversationFromContact(action.contact)
                _uiState.value = _uiState.value.copy(
                    showingNewChatPane = false,
                    searchQuery = ""
                )
                loadMessagesForConversation(conversation.id)
            }

            is ChatListUiAction.SearchQueryChanged -> {
                _uiState.value = _uiState.value.copy(
                    searchQuery = action.query
                )
            }

            is ChatListUiAction.SendMessage -> {
                if (action.content.isNotBlank()) {
                    addMessage(
                        conversationId = action.conversationId,
                        content = action.content,
                        senderId = "me",
                        senderName = "Me",
                        isCurrentUser = true
                    )
                }
            }
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid) {
//        apiProvider.markConversationAsRead(conversationId)

        viewModelScope.launch {
            chatMessageService.start(conversationId)
            chatMessageService.messages.collect { messages ->
                val sorted = messages.sortedBy { it.timestamp }
                _uiState.value = _uiState.value.copy(
                    selectedConversationId = conversationId,
                    currentConversationMessageViewModels = sorted.toPersistentList()
                )
            }
        }
    }

    private fun sendEvent(event: ChatListUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

    fun createConversation(
        name: String,
        avatarInitials: String,
        isPinned: Boolean = false
    ): ConversationUiModel {
        return apiProvider.createConversation(name, avatarInitials, isPinned)
    }

    fun updateConversation(conversationViewModel: ConversationUiModel) {
        apiProvider.updateConversation(conversationViewModel)
    }

    fun deleteConversation(conversationId: Uuid) {
        apiProvider.deleteConversation(conversationId)
    }

    fun getMessagesByConversationId(conversationId: Uuid): List<MessageUiModel> {
        return apiProvider.getMessagesByConversationId(conversationId)
    }

    fun addMessage(
        conversationId: Uuid,
        content: String,
        senderId: String,
        senderName: String,
        isCurrentUser: Boolean = false
    ) {
        viewModelScope.launch {
            chatMessageSenderService.sendNewMessage(
                conversationId,
                content
            )
        }
    }

    fun updateMessage(messageViewModel: MessageUiModel) {
        apiProvider.updateMessage(messageViewModel)
    }

    fun deleteMessage(messageId: Uuid) {
        apiProvider.deleteMessage(messageId)
    }

    fun getFilteredContacts(): List<ContactUiModel> {
        return apiProvider.searchContacts(_uiState.value.searchQuery)
    }
}
