package id.homebase.core.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class HomeViewModel(): ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<HomeUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()


    /** Single entry point for all UI actions. */
    fun onAction(action: HomeUiAction) {
        when (action) {
            is HomeUiAction.ChatListClicked -> {
                sendEvent(HomeUiEvent.NavigateToChatList)
            }
        }
    }

    private fun sendEvent(event: HomeUiEvent) {
        viewModelScope.launch { _uiEvent.send(event) }
    }
}
