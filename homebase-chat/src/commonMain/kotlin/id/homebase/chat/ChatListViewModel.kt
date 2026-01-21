package id.homebase.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface ChatListUiEvent {
    data class NavigateToMessages(val conversationId: String) : ChatListUiEvent
    data object NavigateBack : ChatListUiEvent
}

sealed interface ChatListUiAction {
    data class ConversationClicked(val conversationId: String) : ChatListUiAction
    data object BackClicked : ChatListUiAction
}

@Immutable
data class Conversation(
    val id: String,
    val name: String,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Int = 0,
    val avatarInitials: String,
    val isPinned: Boolean = false,
)

@Immutable
data class ChatListUiState(
    val conversations: ImmutableList<Conversation> = persistentListOf(),
)

class ChatListViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState(
        conversations = persistentListOf(
            Conversation(
                id = "1",
                name = "Alice Johnson",
                lastMessage = "Hey! Are we still on for dinner tonight?",
                timestamp = "2:45 PM",
                unreadCount = 2,
                avatarInitials = "AJ",
                isPinned = true
            ),
            Conversation(
                id = "2",
                name = "Bob Smith",
                lastMessage = "Thanks for your help earlier! 👍",
                timestamp = "1:30 PM",
                unreadCount = 0,
                avatarInitials = "BS",
            ),
            Conversation(
                id = "3",
                name = "Team Planning",
                lastMessage = "Charlie: The meeting is rescheduled to 3 PM",
                timestamp = "12:15 PM",
                unreadCount = 5,
                avatarInitials = "TP",
                isPinned = true
            ),
            Conversation(
                id = "4",
                name = "Diana Martinez",
                lastMessage = "Can you send me those files?",
                timestamp = "11:20 AM",
                unreadCount = 1,
                avatarInitials = "DM",
            ),
            Conversation(
                id = "5",
                name = "Project Starlight",
                lastMessage = "Emily: I've pushed the latest changes",
                timestamp = "Yesterday",
                unreadCount = 0,
                avatarInitials = "PS",
            ),
            Conversation(
                id = "6",
                name = "Frank Wilson",
                lastMessage = "See you tomorrow!",
                timestamp = "Yesterday",
                unreadCount = 0,
                avatarInitials = "FW",
            ),
            Conversation(
                id = "7",
                name = "Grace Lee",
                lastMessage = "That sounds great! 😊",
                timestamp = "Monday",
                unreadCount = 0,
                avatarInitials = "GL",
            ),
            Conversation(
                id = "8",
                name = "Henry Davis",
                lastMessage = "I'll take a look at it",
                timestamp = "Monday",
                unreadCount = 0,
                avatarInitials = "HD",
            ),
            Conversation(
                id = "9",
                name = "Family",
                lastMessage = "Mom: Don't forget to call grandma",
                timestamp = "Sunday",
                unreadCount = 0,
                avatarInitials = "FM",
            ),
            Conversation(
                id = "10",
                name = "Ivy Chen",
                lastMessage = "Perfect! See you there",
                timestamp = "Saturday",
                unreadCount = 0,
                avatarInitials = "IC",
            ),
        )
    ))
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<ChatListUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    fun onAction(action: ChatListUiAction) {
        when (action) {
            is ChatListUiAction.ConversationClicked -> {
                sendEvent(ChatListUiEvent.NavigateToMessages(action.conversationId))
            }
            ChatListUiAction.BackClicked -> {
                sendEvent(ChatListUiEvent.NavigateBack)
            }
        }
    }

    private fun sendEvent(event: ChatListUiEvent) {
        viewModelScope.launch { _uiEvent.send(event) }
    }
}
