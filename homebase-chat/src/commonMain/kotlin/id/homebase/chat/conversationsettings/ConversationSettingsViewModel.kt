package id.homebase.chat.conversationsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.chat.services.convo.ConversationRepository
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class ConversationSettingsViewModel(
    savedStateHandle: SavedStateHandle,
    val conversationRepository: ConversationRepository,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.ConversationSettings>()
    private val _uiState = MutableStateFlow(ConversationSettingsUiState())
    val uiState: StateFlow<ConversationSettingsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }


    fun onUiAction(action: ConversationSettingsUiAction) {
        when (action) {
            is ConversationSettingsUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = ConversationSettingsUiEvent.Back)}
            is ConversationSettingsUiAction.ShowContactInfo -> _uiState.update { it.copy(uiEvent = ConversationSettingsUiEvent.ShowContactInfo(action.odinId.toString()))}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val conversation = conversationRepository.getConversation(Uuid.parse(route.conversationId))
                if (conversation != null) {
                    _uiState.update { it.copy(conversation = conversation, isLoading = false) }
                } else {
                    Logger.d( "Failed to load contact for conversation")
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                Logger.e( "Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}