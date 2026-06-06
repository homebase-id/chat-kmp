package id.homebase.chat.conversationmedia

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.collectConversationOverview
import id.homebase.chat.services.ChatMessageStream
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

@Immutable
data class ConversationMediaUiState(
    val isLoading: Boolean = true,
    val overview: ConversationOverview? = null,
)

/**
 * Backs the "See all" album — the full bucketed media for one conversation
 * (Media / Files / Audio / Dice rolls / Locations tabs). Reuses the same
 * [collectConversationOverview] the settings overview uses.
 */
class ConversationMediaViewModel(
    savedStateHandle: SavedStateHandle,
    private val chatMessageStream: ChatMessageStream,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.ConversationMedia>()
    private val _uiState = MutableStateFlow(ConversationMediaUiState())
    val uiState: StateFlow<ConversationMediaUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val conversationId = Uuid.parse(route.conversationId)
        viewModelScope.launch {
            try {
                val batch = chatMessageStream.fetchMessages(
                    conversationId = conversationId,
                    limit = ALBUM_MESSAGE_CAP,
                )
                val overview = withContext(Dispatchers.Default) {
                    collectConversationOverview(batch)
                }
                _uiState.update { it.copy(overview = overview, isLoading = false) }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation media album", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    companion object {
        const val ALBUM_MESSAGE_CAP = 1000
    }
}
