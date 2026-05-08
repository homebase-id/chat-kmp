package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.moments.services.MomentsFeedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Thin wrapper over [MomentsFeedService] for the feed screen. The service is a
 * singleton started by `AppModule.onPostAuthenticated`, so the VM only
 * subscribes — no start() call here.
 */
class MomentsFeedViewModel(
    feedService: MomentsFeedService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentsFeedUiState())
    val uiState: StateFlow<MomentsFeedUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            feedService.feed.collect { list ->
                _uiState.update { it.copy(moments = list) }
            }
        }
    }
}
