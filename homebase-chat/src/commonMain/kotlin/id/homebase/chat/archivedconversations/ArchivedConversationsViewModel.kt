package id.homebase.chat.archivedconversations

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.ConversationEnricher
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.contact.ContactService
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class ArchivedConversationsViewModel(
    private val conversationStream: ConversationStream,
    private val conversationService: ConversationService,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val contactService: ContactService
) : ViewModel() {
    private val enricher = ConversationEnricher()
    private val _uiState = MutableStateFlow(ArchivedConversationsUiState())
    val uiState: StateFlow<ArchivedConversationsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            contactService.start()
            conversationStream.start()

            combine(
                conversationStream.conversations,
                contactService.contacts,
                ownerSessionRepository.user
            ) { conversationState, contacts, ownerSession ->

                if (ownerSession == null) return@combine Pair(false, emptyList())

                val contactMap = contacts.associateBy { it.odinId }

                Pair(
                    conversationState.dataReady,
                    conversationState.items.filter { it.conversationState == ConversationState.Archived }
                        .map {
                            enricher.enrich(it, contactMap, ownerSession)
                        })
            }.collect { (dataReady: Boolean, enriched: List<EnrichedConversationUiModel>) ->
                if (dataReady) {
                    _uiState.update {
                        it.copy(
                            conversations = enriched
                                .sortedByDescending { conversation -> conversation.conversation.timestamp }
                                .toPersistentList(),
                            isLoading = false,
                        )
                    }
                }
            }
        }
    }

    fun onUiAction(action: ArchivedConversationsUiAction) {
        when (action) {
            is ArchivedConversationsUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = ArchivedConversationsUiEvent.Back) }
            }

            is ArchivedConversationsUiAction.ShowConversation -> {
                _uiState.update {
                    it.copy(uiEvent = ArchivedConversationsUiEvent.NavigateToConversation(action.conversationId.toString()))
                }
            }

            is ArchivedConversationsUiAction.UnarchiveConversation -> {
                viewModelScope.launch {
                    try {
                        conversationService.unarchiveConversation(action.conversationId)
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                uiEvent = ArchivedConversationsUiEvent.Error(
                                    e.message ?: "Failed to unarchive conversation"
                                )
                            )
                        }
                    }
                }
            }
            is ArchivedConversationsUiAction.ShowConversationSettings -> {
                if (action.conversation.isGroupConversation) {
                    _uiState.update {
                        it.copy(
                            uiEvent = ArchivedConversationsUiEvent.NavigateToGroupSettings(
                                (action.conversation.id.toString())
                            )
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            uiEvent = ArchivedConversationsUiEvent.NavigateToConversationSettings(
                                (action.conversation.id.toString())
                            )
                        )
                    }
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}

@Immutable
data class ArchivedConversationsUiState(
    val isLoading: Boolean = true,
    val conversations: ImmutableList<EnrichedConversationUiModel> = persistentListOf(),
    val uiEvent: ArchivedConversationsUiEvent? = null,
)

sealed interface ArchivedConversationsUiEvent {
    data object Back : ArchivedConversationsUiEvent
    data class Error(val errorMessage: String) : ArchivedConversationsUiEvent
    data class NavigateToConversation(val conversationId: String) : ArchivedConversationsUiEvent
    data class NavigateToGroupSettings(val conversationId: String) : ArchivedConversationsUiEvent
    data class NavigateToConversationSettings(val conversationId: String) : ArchivedConversationsUiEvent
}

sealed interface ArchivedConversationsUiAction {
    data object BackClicked : ArchivedConversationsUiAction
    data class ShowConversation(val conversationId: Uuid) : ArchivedConversationsUiAction
    data class UnarchiveConversation(val conversationId: Uuid) : ArchivedConversationsUiAction
    data class ShowConversationSettings(val conversation: ConversationUiModel) :
        ArchivedConversationsUiAction
}
