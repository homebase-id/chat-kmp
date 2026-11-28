package id.homebase.core.ui.screens.privatedrive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.privatedrive.PhotosPrivateDrive
import id.homebase.core.privatedrive.PrivateDriveOptIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotosSignInAgainUiState(val needsSignIn: Boolean = false, val failed: Boolean = false)

/** The Photos status screen's sign-in again: the callback outlives a recomposition, as Vault's does. */
class PhotosSignInAgainViewModel(private val optIn: PrivateDriveOptIn) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotosSignInAgainUiState(needsSignIn = optIn.currentState(PhotosPrivateDrive).needsSignIn))
    val uiState: StateFlow<PhotosSignInAgainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { optIn.state(PhotosPrivateDrive).collect { state -> _uiState.update { it.copy(needsSignIn = state.needsSignIn) } } }
    }

    fun signInAgain(open: (String) -> Unit) {
        _uiState.update { it.copy(failed = false) }
        viewModelScope.launch { optIn.signInAgain(open = { open(it) }, failed = { _uiState.update { it.copy(failed = true) } }) }
    }

    fun onSignInCallback(url: String) {
        viewModelScope.launch { optIn.handleSignInCallback(url) }
    }
}
