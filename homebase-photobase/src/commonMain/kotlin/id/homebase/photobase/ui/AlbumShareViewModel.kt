package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.OdinApiException
import id.homebase.api.client.correlationId
import id.homebase.api.client.errorCode
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.DrivePermission
import id.homebase.photobase.sharing.AlbumAudience
import id.homebase.photobase.sharing.AlbumShareRecipient
import id.homebase.photobase.sharing.AlbumShared
import id.homebase.photobase.sharing.PhotoAlbumAccess
import id.homebase.photobase.sharing.PhotoAlbumSharing
import id.homebase.photobase.sharing.PhotoSharingException
import id.homebase.photobase.sharing.label
import id.homebase.chat.widget.AudienceChoice
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
    val access: PhotoAlbumAccess = PhotoAlbumAccess.View,
    /** The groups made for sharing albums, to share this one with too. */
    val groups: List<AlbumAudience.Group> = emptyList(),
)

sealed interface AlbumShareEvent {
    data class OpenUrl(val url: String) : AlbumShareEvent
    data class OpenSignIn(val url: String) : AlbumShareEvent
    /** [step] failed for [reason]: the server's, or none when Homebase wasn't reached ([unreachable]). */
    data class Failed(val step: AlbumShareStep, val reason: ShareFailure) : AlbumShareEvent
    /** The circle a share goes through grants [granted] on the Photos drive, short of [missing]; only the owner console changes it. */
    data class CircleTooNarrow(val circleName: String, val circleId: Uuid, val granted: List<DrivePermission>, val missing: List<DrivePermission>) : AlbumShareEvent
    data class Shared(val who: String) : AlbumShareEvent
    data class Waiting(val who: String) : AlbumShareEvent
    data class NotAdded(val who: String) : AlbumShareEvent
    data class NotConnected(val who: String) : AlbumShareEvent
    data class NotReviewed(val who: String) : AlbumShareEvent
    /** Not queued: they're named and the owner tries again later. */
    data class Pending(val who: String) : AlbumShareEvent
    /** Shared, but the chat message telling them didn't go. */
    data class NotTold(val who: String) : AlbumShareEvent
}

enum class AlbumShareStep { SignIn, Read, Share, Withdraw }

/** Why something failed, as the user is told it: [reason] quoted, [status], [code] and [correlationId] under Details. */
data class ShareFailure(
    val reason: String?,
    val unreachable: Boolean = false,
    val status: Int? = null,
    val code: String? = null,
    val correlationId: String? = null,
) {
    companion object {
        fun of(e: Throwable): ShareFailure = when {
            e is OdinApiException -> ShareFailure(
                reason = e.problem?.title ?: e.message,
                status = e.status,
                code = e.problem?.errorCode(),
                correlationId = e.correlationId ?: e.problem?.correlationId(),
            )
            e.isTransientNetworkFailure() -> ShareFailure(reason = null, unreachable = true)
            else -> ShareFailure(reason = e.message ?: e::class.simpleName)
        }
    }
}

sealed interface AlbumShareUiAction {
    data object SignIn : AlbumShareUiAction
    data class Access(val access: PhotoAlbumAccess) : AlbumShareUiAction
    data class Share(val choice: AudienceChoice) : AlbumShareUiAction
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
    /** Tells the people a share reaches about it; returns the ones it couldn't. */
    private val tell: suspend (List<OdinId>, AlbumShared, PhotoAlbumAccess) -> List<OdinId> = { _, _, _ -> emptyList() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumShareUiState())
    val uiState: StateFlow<AlbumShareUiState> = _uiState.asStateFlow()

    private val _events = Channel<AlbumShareEvent>(Channel.BUFFERED)
    val events: Flow<AlbumShareEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            // A stored sign-in may still lack what sharing needs: checked as after signing in.
            if (guarded(AlbumShareStep.SignIn) { restore() } == true) finishSigningIn(openOwnerPage = false)
        }
    }

    fun onAction(action: AlbumShareUiAction) {
        when (action) {
            AlbumShareUiAction.SignIn -> signIn()
            is AlbumShareUiAction.Access -> _uiState.update { it.copy(access = action.access) }
            is AlbumShareUiAction.Share -> share(action.choice)
            is AlbumShareUiAction.Withdraw -> busy(AlbumShareStep.Withdraw) { sharing.withdraw(albumId, action.recipient.audience) }
        }
    }

    /** Back from the browser: the sign-in or the owner's page may have finished. Only Sign in opens the browser again. */
    fun resumed() {
        if (signedIn.value && !_uiState.value.signedIn) viewModelScope.launch { finishSigningIn(openOwnerPage = false) }
    }

    private fun signIn() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            if (signedIn.value) return@launch finishSigningIn(openOwnerPage = true)
            val url = guarded(AlbumShareStep.SignIn) {
                signInUrl { ok ->
                    if (ok) finishSigningIn(openOwnerPage = true)
                    else _events.send(AlbumShareEvent.Failed(AlbumShareStep.SignIn, ShareFailure(reason = null)))
                }
            } ?: return@launch
            _events.send(AlbumShareEvent.OpenSignIn(url))
        }
    }

    private suspend fun finishSigningIn(openOwnerPage: Boolean) {
        // Not known is not granted: a check that failed stops here, not at the first share.
        val missing = try {
            missingPermissionsUrl()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "album $albumId: Photos' permissions not read" }
            return _events.send(AlbumShareEvent.Failed(AlbumShareStep.SignIn, ShareFailure.of(e)))
        }
        if (missing != null) {
            if (openOwnerPage) _events.send(AlbumShareEvent.OpenUrl(missing))
            return
        }
        ready()
    }

    private suspend fun ready() {
        _uiState.update { it.copy(signedIn = true) }
        load()
    }

    private fun share(choice: AudienceChoice) {
        val state = _uiState.value
        busy(AlbumShareStep.Share) {
            val audience = when (choice) {
                is AudienceChoice.Group -> state.groups.firstOrNull { it.circleId.toString() == choice.id } ?: return@busy
                is AudienceChoice.Person -> AlbumAudience.Person(choice.odinId)
                is AudienceChoice.NewGroup -> sharing.createGroup(choice.name, choice.members)
            }
            val shared = sharing.share(albumId, audience, state.access, expiresAt = null)
            _events.send(AlbumShareEvent.Shared(audience.label))
            if (shared.pending.isNotEmpty()) _events.send(AlbumShareEvent.Pending(shared.pending.joinToString { it.domainName }))
            val untold = tell(audience.members - shared.pending.toSet(), shared, state.access)
            if (untold.isNotEmpty()) _events.send(AlbumShareEvent.NotTold(untold.joinToString { it.domainName }))
            if (shared.waiting.isNotEmpty()) _events.send(AlbumShareEvent.Waiting(shared.waiting.joinToString { it.domainName }))
        }
    }

    private fun busy(step: AlbumShareStep, block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoSharingException.RecipientNotAdded) {
                _events.send(AlbumShareEvent.NotAdded(e.recipients.joinToString { it.domainName }))
            } catch (e: PhotoSharingException.NotConnected) {
                _events.send(AlbumShareEvent.NotConnected(e.recipients.joinToString { it.domainName }))
            } catch (e: PhotoSharingException.NotReviewed) {
                _events.send(AlbumShareEvent.NotReviewed(e.recipients.joinToString { it.domainName }))
            } catch (e: PhotoSharingException.Pending) {
                _events.send(AlbumShareEvent.Pending(e.recipients.joinToString { it.domainName }))
            } catch (e: PhotoSharingException.CircleTooNarrow) {
                Logger.w(tag = TAG) { "album $albumId: ${e.message}" }
                _events.send(AlbumShareEvent.CircleTooNarrow(e.circleName, e.circleId, e.granted, e.missing))
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "album $albumId: $step failed" }
                sharing.signOutIfRefused(e)
                _events.send(AlbumShareEvent.Failed(step, ShareFailure.of(e)))
            } finally {
                _uiState.update { it.copy(busy = false) }
                load()
            }
        }
    }

    private suspend fun load() {
        guarded(AlbumShareStep.Read) { sharing.sharedWith(albumId) to sharing.audiences().filterIsInstance<AlbumAudience.Group>() }?.let { (recipients, groups) ->
            _uiState.update { it.copy(recipients = recipients, groups = groups) }
        }
    }

    private suspend fun <T> guarded(step: AlbumShareStep, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "album $albumId: sharing not read" }
            if (sharing.signOutIfRefused(e)) _uiState.update { it.copy(signedIn = false) }
            _events.send(AlbumShareEvent.Failed(step, ShareFailure.of(e)))
            null
        }

    private companion object {
        const val TAG = "AlbumShareViewModel"
    }
}
