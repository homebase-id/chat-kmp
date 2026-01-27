package id.homebase.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.data.ChatMessageReaderService
import id.homebase.chat.data.ChatMessageSenderService
import id.homebase.chat.data.Contact
import id.homebase.chat.data.ContactService
import id.homebase.chat.data.ConversationService
import id.homebase.chat.data.Message
import id.homebase.chat.data.MockChatApiProvider
import id.homebase.core.model.ThumbnailDescriptor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ChatListUiEvent {
    data class NavigateToMessages(val conversationId: Uuid) : ChatListUiEvent
    data object NavigateBack : ChatListUiEvent
}

sealed interface ChatListUiAction {
    data class ConversationClicked(val conversationId: Uuid) : ChatListUiAction
    data object BackClicked : ChatListUiAction
    data object NewChatClicked : ChatListUiAction
    data object BackToListClicked : ChatListUiAction
    data class ContactClicked(val contact: Contact) : ChatListUiAction
    data class SearchQueryChanged(val query: String) : ChatListUiAction
    data class SendMessage(val conversationId: Uuid, val content: String) : ChatListUiAction
}

@Immutable
data class Conversation(
    val id: Uuid,
    val name: String,
    val lastMessage: String,
    val timestamp: Instant,
    var unreadCount: Int = 0,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val avatarTiny: ThumbnailDescriptor?,
    val participants: List<String> = listOf()
)

@Immutable
data class ChatListUiState(
    val conversations: ImmutableList<Conversation> = persistentListOf(),
    val showingNewChatPane: Boolean = false,
    val contacts: ImmutableList<Contact> = persistentListOf(),
    val searchQuery: String = "",
    val currentConversationMessages: ImmutableList<Message> = persistentListOf(),
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

    private val _uiEvent = Channel<ChatListUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    fun onAction(action: ChatListUiAction) {
        when (action) {
            is ChatListUiAction.ConversationClicked -> {
                loadMessagesForConversation(action.conversationId)
                sendEvent(ChatListUiEvent.NavigateToMessages(action.conversationId))
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
                sendEvent(ChatListUiEvent.NavigateToMessages(conversation.id))
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
                    currentConversationMessages = sorted.toPersistentList()
                )
            }
        }
    }

    private fun sendEvent(event: ChatListUiEvent) {
        viewModelScope.launch { _uiEvent.send(event) }
    }

    fun createConversation(
        name: String,
        avatarInitials: String,
        isPinned: Boolean = false
    ): Conversation {
        return apiProvider.createConversation(name, avatarInitials, isPinned)
    }

    fun updateConversation(conversation: Conversation) {
        apiProvider.updateConversation(conversation)
    }

    fun deleteConversation(conversationId: Uuid) {
        apiProvider.deleteConversation(conversationId)
    }

    fun getMessagesByConversationId(conversationId: Uuid): List<Message> {
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

    fun updateMessage(message: Message) {
        apiProvider.updateMessage(message)
    }

    fun deleteMessage(messageId: Uuid) {
        apiProvider.deleteMessage(messageId)
    }

    fun getFilteredContacts(): List<Contact> {
        return apiProvider.searchContacts(_uiState.value.searchQuery)
    }
}
