package id.homebase.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.data.Contact
import id.homebase.chat.data.Message
import id.homebase.chat.data.MockChatApiProvider
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

sealed interface ChatListUiEvent {
    data class NavigateToMessages(val conversationId: String) : ChatListUiEvent
    data object NavigateBack : ChatListUiEvent
}

sealed interface ChatListUiAction {
    data class ConversationClicked(val conversationId: String) : ChatListUiAction
    data object BackClicked : ChatListUiAction
    data object NewChatClicked : ChatListUiAction
    data object BackToListClicked : ChatListUiAction
    data class ContactClicked(val contact: Contact) : ChatListUiAction
    data class SearchQueryChanged(val query: String) : ChatListUiAction
    data class SendMessage(val conversationId: String, val content: String) : ChatListUiAction
}

@Immutable
data class Conversation(
    val id: String,
    val name: String,
    val lastMessage: String,
    val timestamp: Instant,
    val unreadCount: Int = 0,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val isPinned: Boolean = false,
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState())
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            apiProvider.conversations.collect { conversations ->
                val sorted = conversations.sortedByDescending { it.timestamp }
                _uiState.value = _uiState.value.copy(
                    conversations = sorted.toPersistentList()
                )
            }
        }
        viewModelScope.launch {
            apiProvider.contacts.collect { contacts ->
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
                    loadMessagesForConversation(action.conversationId)
                }
            }
        }
    }
    
    private fun loadMessagesForConversation(conversationId: String) {
        apiProvider.markConversationAsRead(conversationId)
        val messages = apiProvider.getMessagesByConversationId(conversationId)
        _uiState.value = _uiState.value.copy(
            currentConversationMessages = messages.toPersistentList()
        )
    }

    private fun sendEvent(event: ChatListUiEvent) {
        viewModelScope.launch { _uiEvent.send(event) }
    }
    
    fun createConversation(name: String, avatarInitials: String, isPinned: Boolean = false): Conversation {
        return apiProvider.createConversation(name, avatarInitials, isPinned)
    }
    
    fun updateConversation(conversation: Conversation) {
        apiProvider.updateConversation(conversation)
    }
    
    fun deleteConversation(conversationId: String) {
        apiProvider.deleteConversation(conversationId)
    }
    
    fun getMessagesByConversationId(conversationId: String): List<Message> {
        return apiProvider.getMessagesByConversationId(conversationId)
    }
    
    fun addMessage(
        conversationId: String,
        content: String,
        senderId: String,
        senderName: String,
        isCurrentUser: Boolean = false
    ): Message {
        return apiProvider.addMessage(conversationId, content, senderId, senderName, isCurrentUser)
    }
    
    fun updateMessage(message: Message) {
        apiProvider.updateMessage(message)
    }
    
    fun deleteMessage(messageId: String) {
        apiProvider.deleteMessage(messageId)
    }
    
    fun getFilteredContacts(): List<Contact> {
        return apiProvider.searchContacts(_uiState.value.searchQuery)
    }
}
