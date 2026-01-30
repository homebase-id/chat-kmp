package id.homebase.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.services.ChatMessageReaderService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ContactService
import id.homebase.chat.services.ConversationService
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.ScrollPosition
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class ChatListViewModel(
    private val contactService: ContactService,
    private val conversationService: ConversationService,
    private val chatMessageService: ChatMessageReaderService,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

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
                        content = action.content,
                        senderId = "me",
                        senderName = "Me",
                        isCurrentUser = true
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
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid) {
        viewModelScope.launch {
            chatMessageService.loadConversation(conversationId)

            chatMessageService
                .observeMessages(conversationId).collect { messages ->
                val sorted = messages.sortedBy { it.timestamp }
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
}
