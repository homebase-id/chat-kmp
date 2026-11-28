package id.homebase.core.ui.screens.privatedrive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.privatedrive.PhotosPrivateDrive
import id.homebase.core.privatedrive.PrivateDriveOptIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Photos status screen's sign-in again: the callback outlives a recomposition, as Vault's does. */
class PhotosSignInAgainViewModel(private val optIn: PrivateDriveOptIn) : ViewModel() {
    val needsSignIn: StateFlow<Boolean> = optIn.state(PhotosPrivateDrive).map { it.needsSignIn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), optIn.currentState(PhotosPrivateDrive).needsSignIn)

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun signInAgain(open: (String) -> Unit) {
        _failed.value = false
        viewModelScope.launch { optIn.signInAgain(open = { open(it) }, failed = { _failed.value = true }) }
    }

    fun onSignInCallback(url: String) {
        viewModelScope.launch { optIn.handleSignInCallback(url) }
    }
}
