package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.photobase.sharing.AlbumAudience
import id.homebase.photobase.sharing.AlbumShareRecipient
import id.homebase.photobase.sharing.PhotoAlbumAccess
import id.homebase.photobase.sharing.PhotoAlbumSharing
import id.homebase.photobase.sharing.PhotoSharingException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumShareUiState(
    val signedIn: Boolean = false,
    /** Null until read. */
    val recipients: List<AlbumShareRecipient>? = null,
    val busy: Boolean = false,
    val identity: String = "",
    val access: PhotoAlbumAccess = PhotoAlbumAccess.View,
    val identityInvalid: Boolean = false,
)

sealed interface AlbumShareEvent {
    data class OpenUrl(val url: String) : AlbumShareEvent
    data object Failed : AlbumShareEvent
    data class Waiting(val who: String) : AlbumShareEvent
    data class NotAdded(val who: String) : AlbumShareEvent
}

sealed interface AlbumShareUiAction {
    data object SignIn : AlbumShareUiAction
    data class Identity(val text: String) : AlbumShareUiAction
    data class Access(val access: PhotoAlbumAccess) : AlbumShareUiAction
    data object Share : AlbumShareUiAction
    data class Withdraw(val recipient: AlbumShareRecipient) : AlbumShareUiAction
}

/** Who an album is shared with, and sharing it with someone else: signing in to Photobase first when this device hasn't. */
class AlbumShareViewModel(
    val albumId: Uuid,
    private val sharing: PhotoAlbumSharing,
    private val signedIn: StateFlow<Boolean>,
    private val restore: suspend () -> Boolean,
    private val signInUrl: suspend (onDone: suspend (Boolean) -> Unit) -> String,
    private val missingPermissionsUrl: suspend () -> String?,
    /** Tells the people a share reaches about it. */
    private val tell: suspend (List<OdinId>) -> Unit = {},
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumShareUiState())
    val uiState: StateFlow<AlbumShareUiState> = _uiState.asStateFlow()

    private val _events = Channel<AlbumShareEvent>(Channel.BUFFERED)
    val events: Flow<AlbumShareEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (guarded { restore() } == true) ready()
        }
    }

    fun onAction(action: AlbumShareUiAction) {
        when (action) {
            AlbumShareUiAction.SignIn -> signIn()
            is AlbumShareUiAction.Identity -> _uiState.update { it.copy(identity = action.text, identityInvalid = false) }
            is AlbumShareUiAction.Access -> _uiState.update { it.copy(access = action.access) }
            AlbumShareUiAction.Share -> share()
            is AlbumShareUiAction.Withdraw -> busy { sharing.withdraw(albumId, action.recipient.audience) }
        }
    }

    /** Back from the browser: the sign-in or the owner's page may have finished. */
    fun resumed() {
        if (signedIn.value && !_uiState.value.signedIn) viewModelScope.launch { finishSigningIn() }
    }

    private fun signIn() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            if (signedIn.value) return@launch finishSigningIn()
            val url = guarded { signInUrl { ok -> if (ok) finishSigningIn() else _events.send(AlbumShareEvent.Failed) } } ?: return@launch
            _events.send(AlbumShareEvent.OpenUrl(url))
        }
    }

    private suspend fun finishSigningIn() {
        val missing = guarded { missingPermissionsUrl() }
        if (missing != null) return _events.send(AlbumShareEvent.OpenUrl(missing))
        ready()
    }

    private suspend fun ready() {
        _uiState.update { it.copy(signedIn = true) }
        load()
    }

    private fun share() {
        val state = _uiState.value
        val person = state.identity.trim().takeIf { it.contains('.') }?.let { runCatching { OdinId(it.lowercase()) }.getOrNull() }
            ?: return _uiState.update { it.copy(identityInvalid = true) }
        busy {
            val shared = sharing.share(albumId, AlbumAudience.Person(person), state.access, expiresAt = null)
            _uiState.update { it.copy(identity = "") }
            tell(listOf(person))
            if (shared.waiting.isNotEmpty()) _events.send(AlbumShareEvent.Waiting(shared.waiting.joinToString { it.domainName }))
        }
    }

    private fun busy(block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoSharingException.RecipientNotAdded) {
                _events.send(AlbumShareEvent.NotAdded(e.recipients.joinToString { it.domainName }))
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "album $albumId: sharing failed" }
                sharing.signOutIfRefused(e)
                _events.send(AlbumShareEvent.Failed)
            } finally {
                _uiState.update { it.copy(busy = false) }
                load()
            }
        }
    }

    private suspend fun load() {
        guarded { sharing.sharedWith(albumId) }?.let { recipients -> _uiState.update { it.copy(recipients = recipients) } }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "album $albumId: sharing not read" }
            if (sharing.signOutIfRefused(e)) _uiState.update { it.copy(signedIn = false) }
            _events.send(AlbumShareEvent.Failed)
            null
        }

    private companion object {
        const val TAG = "AlbumShareViewModel"
    }
}
