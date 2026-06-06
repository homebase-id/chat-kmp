package id.homebase.chat.conversationsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.core.ui.navigation.Route
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

class ConversationSettingsViewModel(
    savedStateHandle: SavedStateHandle,
    val conversationService: ConversationService,
    private val conversationStream: ConversationStream,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val chatMessageStream: ChatMessageStream,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.ConversationSettings>()
    private val _uiState = MutableStateFlow(ConversationSettingsUiState())
    val uiState: StateFlow<ConversationSettingsUiState> = _uiState.asStateFlow()

    init {
        loadData()
        loadOverview()
    }


    fun onUiAction(action: ConversationSettingsUiAction) {
        when (action) {
            is ConversationSettingsUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = ConversationSettingsUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val conversationId = Uuid.parse(route.conversationId)
                if (conversationId == ChatProtocol.ConversationWithYourselfId) {
                    val conversation = conversationStream.getConversationById(conversationId)
                    val owner = ownerSessionRepository.user.value
                    _uiState.update {
                        it.copy(
                            conversation = conversation,
                            ownerSession = owner,
                            isLoading = false,
                        )
                    }
                } else {
                    val conversation = conversationService.getConversation(conversationId)
                    if (conversation != null) {
                        _uiState.update {
                            it.copy(
                                conversation = conversation,
                                groupsInCommon = groupsInCommonWith(conversation),
                                isLoading = false,
                            )
                        }
                    } else {
                        Logger.d("Failed to load contact for conversation")
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Group conversations that both the current identity and this 1:1's contact
     * belong to. The contact is the participant that isn't us; matching excludes
     * this conversation itself (which is 1:1, not a group, so it never matches).
     */
    private fun groupsInCommonWith(conversation: ConversationUiModel) =
        try {
            val self = ownerSessionRepository.user.value?.odinId
            val contact = conversation.participants.firstOrNull { it != self }
            if (contact == null) {
                persistentListOf()
            } else {
                conversationStream.conversations.value.items
                    .filter { it.isGroupConversation && it.participants.any { p -> p == contact } }
                    .map { GroupInCommonItem(it.id, it.name, it.avatarModel) }
                    .toImmutableList()
            }
        } catch (e: Exception) {
            Logger.e("Failed to compute groups in common", e)
            persistentListOf()
        }

    /**
     * Loads the media/files/audio/dice/location overview of this conversation.
     * Runs independently of [loadData] so the header paints before the (heavier)
     * message scan finishes.
     */
    private fun loadOverview() {
        val conversationId = Uuid.parse(route.conversationId)
        viewModelScope.launch {
            try {
                val batch = chatMessageStream.fetchMessages(
                    conversationId = conversationId,
                    limit = SUMMARY_MESSAGE_CAP,
                )

                // When the window is truncated, its oldest row isn't the real
                // first message — fetch that separately (cheap, limit = 1) so
                // "chatting since" stays accurate regardless of the cap.
                val firstMessageDate =
                    if (batch.hasMoreRows) {
                        chatMessageStream.fetchMessages(
                            conversationId = conversationId,
                            limit = 1,
                            sortOrder = QueryBatchSortOrder.OldestFirst,
                        ).records.firstOrNull()?.userDate
                    } else {
                        // newest-first → the last row is the oldest message
                        batch.records.lastOrNull()?.userDate
                    }

                val overview = withContext(Dispatchers.Default) {
                    collectConversationOverview(batch, firstMessageDate)
                }
                _uiState.update { it.copy(overview = overview, isOverviewLoading = false) }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation overview", e)
                _uiState.update { it.copy(isOverviewLoading = false) }
            }
        }
    }

    companion object {
        /**
         * Upper bound on messages scanned for the conversation overview. Every
         * photo/video within these is surfaced in the media strip — the strip is
         * a LazyRow, so it only ever composes/loads the thumbnails on screen.
         */
        const val SUMMARY_MESSAGE_CAP = 1000
    }
}
