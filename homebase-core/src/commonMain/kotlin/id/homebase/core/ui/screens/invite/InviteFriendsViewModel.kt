package id.homebase.core.ui.screens.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.invitations.InvitationCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InviteFriendsViewModel(
    private val loadCodes: suspend () -> List<InvitationCode>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteFriendsUiState())
    val uiState: StateFlow<InviteFriendsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun onAction(action: InviteFriendsUiAction) {
        when (action) {
            InviteFriendsUiAction.Retry -> load()
        }
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val codes = loadCodes()
                _uiState.update { it.copy(isLoading = false, codes = codes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Loading invitation codes failed" }
                _uiState.update { it.copy(isLoading = false, loadFailed = true, codes = emptyList()) }
            }
        }
    }

    private companion object {
        const val TAG = "InviteFriendsViewModel"
    }
}
