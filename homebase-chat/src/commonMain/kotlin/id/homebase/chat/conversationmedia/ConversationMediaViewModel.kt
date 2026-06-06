package id.homebase.chat.conversationmedia

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.chat.conversationsettings.ConversationOverview
import id.homebase.chat.conversationsettings.SharedMediaItem
import id.homebase.chat.conversationsettings.collectConversationOverview
import id.homebase.chat.conversationsettings.collectLocations
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.ui.navigation.Route
import kotlinx.collections.immutable.ImmutableList
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

@Immutable
data class ConversationMediaUiState(
    val isLoading: Boolean = true,
    val overview: ConversationOverview? = null,
    /**
     * Locations are paged independently of [overview]: they're queried straight
     * from the index by [ChatProtocol.ChatLocationMessageDataType] and appended
     * as the user scrolls, so the tab scales to thousands of pins instead of
     * being capped at the last [ALBUM_MESSAGE_CAP] messages.
     */
    val locations: ImmutableList<SharedMediaItem> = persistentListOf(),
    val isLoadingLocations: Boolean = false,
    val hasMoreLocations: Boolean = true,
)

/**
 * Backs the "See all" album — the full bucketed media for one conversation
 * (Media / Files / Audio / Dice rolls / Locations tabs). Media/Files/Audio/Dice
 * reuse the [collectConversationOverview] scan over the most recent
 * [ALBUM_MESSAGE_CAP] messages; Locations are paged separately (see
 * [loadMoreLocations]) so they aren't bounded by that window.
 */
class ConversationMediaViewModel(
    savedStateHandle: SavedStateHandle,
    private val chatMessageStream: ChatMessageStream,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.ConversationMedia>()
    private val conversationId = Uuid.parse(route.conversationId)
    private val _uiState = MutableStateFlow(ConversationMediaUiState())
    val uiState: StateFlow<ConversationMediaUiState> = _uiState.asStateFlow()

    private var locationsCursor: QueryBatchCursor? = null

    init {
        load()
        loadMoreLocations()
    }

    private fun load() {
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

    /**
     * Loads the next page of location pins, appending to [ConversationMediaUiState.locations].
     * Guarded so concurrent scroll triggers don't double-fetch; stops when the
     * index reports no more rows. Queries only messages stamped
     * [ChatProtocol.ChatLocationMessageDataType] (legacy pre-211 pins with
     * dataType=0 are not surfaced — that needs a separate backfill).
     */
    fun loadMoreLocations() {
        if (_uiState.value.isLoadingLocations || !_uiState.value.hasMoreLocations) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLocations = true) }
            try {
                val batch = chatMessageStream.fetchMessages(
                    conversationId = conversationId,
                    limit = LOCATIONS_PAGE_SIZE,
                    cursor = locationsCursor,
                    datatypesAnyOf = listOf(ChatProtocol.ChatLocationMessageDataType),
                )
                val page = withContext(Dispatchers.Default) { collectLocations(batch) }
                locationsCursor = batch.cursor
                _uiState.update {
                    it.copy(
                        locations = (it.locations + page).toImmutableList(),
                        hasMoreLocations = batch.hasMoreRows,
                        isLoadingLocations = false,
                    )
                }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation locations", e)
                _uiState.update { it.copy(isLoadingLocations = false) }
            }
        }
    }

    companion object {
        const val ALBUM_MESSAGE_CAP = 1000
        const val LOCATIONS_PAGE_SIZE = 50
    }
}
