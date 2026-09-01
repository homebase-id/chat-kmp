package id.homebase.chat.archivedconversations

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.convo.ConversationEnricher
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.resources.MR
import id.homebase.resources.action_undo
import id.homebase.resources.chat_conversation_restored_confirmation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

private data class ArchivedConnectionContext(
    val connectionMap: Map<id.homebase.api.common.OdinId, id.homebase.api.client.connections.RedactedIdentityConnectionRegistration>,
    val incomingSenders: Set<id.homebase.api.common.OdinId>,
    val outgoingRecipients: Set<id.homebase.api.common.OdinId>,
    val statusKnown: Boolean,
)

class ArchivedConversationsViewModel(
    private val conversationStream: ConversationStream,
    private val conversationService: ConversationService,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val contactService: ContactService,
    private val connectionService: ConnectionService,
    private val connectionRequestService: ConnectionRequestService,
) : ViewModel() {
    private val enricher = ConversationEnricher()
    private val _uiState = MutableStateFlow(ArchivedConversationsUiState())
    val uiState: StateFlow<ArchivedConversationsUiState> = _uiState.asStateFlow()

    private val events = Channel<ArchivedConversationsUiEvent>(capacity = Channel.BUFFERED)
    val uiEvents: Flow<ArchivedConversationsUiEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            contactService.start()
            conversationStream.start()
            connectionService.start()
            connectionRequestService.start()

            val connectionStatusFlow = combine(
                connectionService.connections,
                connectionRequestService.incomingRequests,
                connectionRequestService.outgoingRequests,
                connectionRequestService.isLoaded,
            ) { connections, incoming, outgoing, requestsLoaded ->
                ArchivedConnectionContext(
                    connectionMap = connections.map,
                    incomingSenders = incoming.map { it.senderOdinId }.toSet(),
                    outgoingRecipients = outgoing.map { it.recipientOdinId }.toSet(),
                    statusKnown = connections.isLoaded && requestsLoaded,
                )
            }

            combine(
                conversationStream.conversations,
                contactService.contacts,
                ownerSessionRepository.user,
                connectionStatusFlow,
            ) { conversationState, contacts, ownerSession, connectionCtx ->

                if (ownerSession == null) return@combine Pair(false, emptyList())

                val contactMap = contacts.associateBy { it.odinId }

                Pair(
                    conversationState.dataReady,
                    conversationState.items.filter { it.conversationState == ConversationState.Archived }
                        .map {
                            enricher.enrich(
                                convo = it,
                                contactMap = contactMap,
                                ownerSession = ownerSession,
                                connectionMap = connectionCtx.connectionMap,
                                incomingRequestSenders = connectionCtx.incomingSenders,
                                outgoingRequestRecipients = connectionCtx.outgoingRecipients,
                                connectionStatusKnown = connectionCtx.statusKnown,
                            )
                        })
            }.collect { (dataReady: Boolean, enriched: List<EnrichedConversationUiModel>) ->
                if (dataReady) {
                    _uiState.update {
                        it.copy(
                            conversations = enriched
                                .sortedByDescending { conversation -> conversation.conversation.latestMessageTimestamp }
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
                sendEvent(ArchivedConversationsUiEvent.Back)
            }

            is ArchivedConversationsUiAction.ShowConversation -> {
                sendEvent(ArchivedConversationsUiEvent.NavigateToConversation(action.conversationId))
            }

            is ArchivedConversationsUiAction.UnarchiveConversation -> {
                viewModelScope.launch {
                    try {
                        conversationService.unarchiveConversation(action.conversationId)
                        sendEvent(
                            ArchivedConversationsUiEvent.ShowInfoMessage(
                                res = MR.string.chat_conversation_restored_confirmation,
                                actionLabel = MR.string.action_undo,
                                action = ArchivedConversationsUiAction.ArchiveConversation(
                                    action.conversationId
                                ),
                            )
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ArchivedConversationsUiEvent.Error(
                                e.message ?: "Failed to unarchive conversation"
                            )
                        )
                    }
                }
            }

            is ArchivedConversationsUiAction.ArchiveConversation -> {
                viewModelScope.launch {
                    try {
                        conversationService.archiveConversation(action.conversationId)
                    } catch (e: Exception) {
                        sendEvent(
                            ArchivedConversationsUiEvent.Error(
                                e.message ?: "Failed to archive conversation"
                            )
                        )
                    }
                }
            }

            is ArchivedConversationsUiAction.ShowConversationSettings -> {
                if (action.conversation.isGroupConversation) {
                    sendEvent(
                        ArchivedConversationsUiEvent.NavigateToGroupSettings(
                            action.conversation.id.toString()
                        )
                    )
                } else {
                    sendEvent(
                        ArchivedConversationsUiEvent.NavigateToConversationSettings(
                            action.conversation.id.toString()
                        )
                    )
                }
            }
        }
    }

    private fun sendEvent(event: ArchivedConversationsUiEvent) {
        events.trySend(event).onFailure {
            Logger.w(throwable = it, tag = "ArchivedConversationsViewModel") {
                "dropped ${event::class.simpleName}"
            }
        }
    }
}

@Immutable
data class ArchivedConversationsUiState(
    val isLoading: Boolean = true,
    val conversations: ImmutableList<EnrichedConversationUiModel> = persistentListOf(),
)

sealed interface ArchivedConversationsUiEvent {
    data object Back : ArchivedConversationsUiEvent
    data class Error(val errorMessage: String) : ArchivedConversationsUiEvent
    data class ShowInfoMessage(
        val res: StringResource,
        val actionLabel: StringResource? = null,
        val action: ArchivedConversationsUiAction? = null,
    ) : ArchivedConversationsUiEvent
    data class NavigateToConversation(val conversationId: Uuid) : ArchivedConversationsUiEvent
    data class NavigateToGroupSettings(val conversationId: String) : ArchivedConversationsUiEvent
    data class NavigateToConversationSettings(val conversationId: String) : ArchivedConversationsUiEvent
}

sealed interface ArchivedConversationsUiAction {
    data object BackClicked : ArchivedConversationsUiAction
    data class ShowConversation(val conversationId: Uuid) : ArchivedConversationsUiAction
    data class UnarchiveConversation(val conversationId: Uuid) : ArchivedConversationsUiAction
    data class ArchiveConversation(val conversationId: Uuid) : ArchivedConversationsUiAction
    data class ShowConversationSettings(val conversation: ConversationUiModel) :
        ArchivedConversationsUiAction
}
