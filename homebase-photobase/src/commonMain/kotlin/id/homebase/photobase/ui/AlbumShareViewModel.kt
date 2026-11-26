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
    /** What's running, which only its own button shows; null when nothing is. */
    val running: AlbumShareStep? = null,
    val access: PhotoAlbumAccess = PhotoAlbumAccess.View,
    /** The groups made for sharing albums, to share this one with too. */
    val groups: List<AlbumAudience.Group> = emptyList(),
) {
    val busy: Boolean get() = running != null
}

sealed interface AlbumShareEvent {
    data class OpenUrl(val url: String) : AlbumShareEvent
    data class OpenSignIn(val url: String) : AlbumShareEvent
    data class Failed(val step: AlbumShareStep, val reason: ShareFailure) : AlbumShareEvent
    /** The circle a share goes through grants [granted] on the Photos drive, short of [missing]; only the owner console changes it. */
    data class CircleTooNarrow(val circleName: String, val circleId: Uuid, val granted: List<DrivePermission>, val missing: List<DrivePermission>) : AlbumShareEvent
    data class Shared(val who: String) : AlbumShareEvent
    data class GroupSaved(val name: String) : AlbumShareEvent
    /** Added to [group]; their grant lands on its own when their identity next calls this one. */
    data class GroupPending(val who: String, val group: String) : AlbumShareEvent
    data object GroupGone : AlbumShareEvent
    /** A group's share went to all but these, refused by odin-core. */
    data class LeftOut(val who: String) : AlbumShareEvent
    data class Waiting(val who: String) : AlbumShareEvent
    data class NotAdded(val who: String) : AlbumShareEvent
    data class NotConnected(val who: String) : AlbumShareEvent
    data class NotReviewed(val who: String) : AlbumShareEvent
    /** Not queued: they're named and the owner tries again later. */
    data class Pending(val who: String) : AlbumShareEvent
    /** Shared, but the chat message telling them didn't go. */
    data class NotTold(val who: String) : AlbumShareEvent
}

enum class AlbumShareStep { SignIn, Read, Share, Withdraw, EditGroup }

/**
 * Why something failed, as the user is told it: the server's [reason] quoted, with [status], [code] and [correlationId]
 * under Details. An error of this device's own is said as one, its text only under Details ([deviceError]).
 */
data class ShareFailure(
    val reason: String?,
    val unreachable: Boolean = false,
    val status: Int? = null,
    val code: String? = null,
    val correlationId: String? = null,
    val deviceError: String? = null,
) {
    val hasDetails: Boolean get() = status != null || deviceError != null

    companion object {
        fun of(e: Throwable): ShareFailure = when {
            e is OdinApiException -> ShareFailure(
                reason = e.problem?.title ?: e.message,
                status = e.status,
                code = e.problem?.errorCode(),
                correlationId = e.correlationId ?: e.problem?.correlationId(),
            )
            e.isTransientNetworkFailure() -> ShareFailure(reason = null, unreachable = true)
            else -> ShareFailure(reason = null, deviceError = listOfNotNull(e::class.simpleName, e.message).joinToString(": "))
        }
    }
}

sealed interface AlbumShareUiAction {
    data object SignIn : AlbumShareUiAction
    data class Access(val access: PhotoAlbumAccess) : AlbumShareUiAction
    data class Share(val choice: AudienceChoice) : AlbumShareUiAction
    data class Withdraw(val recipient: AlbumShareRecipient) : AlbumShareUiAction
    /** Everything already shared with [group] reaches whoever is added, and stops reaching whoever is removed. */
    data class EditGroup(val group: AlbumAudience.Group, val members: List<OdinId>) : AlbumShareUiAction
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
            is AlbumShareUiAction.EditGroup -> busy(AlbumShareStep.EditGroup) {
                val edit = sharing.setMembers(action.group, action.members)
                if (edit.changed) _events.send(AlbumShareEvent.GroupSaved(action.group.name))
                if (edit.refused.isNotEmpty()) _events.send(AlbumShareEvent.NotAdded(edit.refused.joinToString { it.domainName }))
                if (edit.pending.isNotEmpty()) _events.send(AlbumShareEvent.GroupPending(edit.pending.joinToString { it.domainName }, action.group.name))
                if (edit.joined.isEmpty()) return@busy
                // The edit is saved: a failure telling them is theirs not being told, not the edit failing.
                val untold = try {
                    sharing.albumsSharedWith(action.group).flatMap { (shared, access) -> tell(edit.joined, shared, access) }.distinct()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(tag = TAG, throwable = e) { "group ${action.group.circleId}: new members not told" }
                    edit.joined
                }
                if (untold.isNotEmpty()) _events.send(AlbumShareEvent.NotTold(untold.joinToString { it.domainName }))
            }
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
        _uiState.update { it.copy(signedIn = true) }
        load()
    }

    private fun share(choice: AudienceChoice) {
        val state = _uiState.value
        busy(AlbumShareStep.Share) {
            val (audience, shared) = when (choice) {
                is AudienceChoice.Group -> {
                    val group = state.groups.firstOrNull { it.circleId.toString() == choice.id } ?: return@busy _events.send(AlbumShareEvent.GroupGone)
                    group to sharing.share(albumId, group, state.access, expiresAt = null)
                }
                is AudienceChoice.Person -> AlbumAudience.Person(choice.odinId).let { it to sharing.share(albumId, it, state.access, expiresAt = null) }
                is AudienceChoice.NewGroup -> sharing.shareWithNewGroup(albumId, choice.name, choice.members, state.access, expiresAt = null)
            }
            _events.send(AlbumShareEvent.Shared(audience.label))
            // Only a new group has pending members here: they join on their own, nothing to try again.
            if (shared.pending.isNotEmpty()) _events.send(AlbumShareEvent.GroupPending(shared.pending.joinToString { it.domainName }, audience.label))
            if (shared.refused.isNotEmpty()) _events.send(AlbumShareEvent.LeftOut(shared.refused.joinToString { it.domainName }))
            val untold = tell(audience.members - shared.pending.toSet() - shared.refused.toSet(), shared, state.access)
            if (untold.isNotEmpty()) _events.send(AlbumShareEvent.NotTold(untold.joinToString { it.domainName }))
            if (shared.waiting.isNotEmpty()) _events.send(AlbumShareEvent.Waiting(shared.waiting.joinToString { it.domainName }))
        }
    }

    private fun busy(step: AlbumShareStep, block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(running = step) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoSharingException.ForRecipients) {
                val who = e.recipients.joinToString { it.domainName }
                _events.send(
                    when (e) {
                        is PhotoSharingException.RecipientNotAdded -> AlbumShareEvent.NotAdded(who)
                        is PhotoSharingException.NotConnected -> AlbumShareEvent.NotConnected(who)
                        is PhotoSharingException.NotReviewed -> AlbumShareEvent.NotReviewed(who)
                        is PhotoSharingException.Pending -> AlbumShareEvent.Pending(who)
                    }
                )
            } catch (e: PhotoSharingException.CircleTooNarrow) {
                Logger.w(tag = TAG) { "album $albumId: ${e.message}" }
                _events.send(AlbumShareEvent.CircleTooNarrow(e.circleName, e.circleId, e.granted, e.missing))
            } catch (e: PhotoSharingException.NotSignedIn) {
                _uiState.update { it.copy(signedIn = false) }
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "album $albumId: $step failed" }
                if (sharing.signOutIfRefused(e)) _uiState.update { it.copy(signedIn = false) }
                _events.send(AlbumShareEvent.Failed(step, ShareFailure.of(e)))
            } finally {
                _uiState.update { it.copy(running = null) }
                load()
            }
        }
    }

    private suspend fun load() {
        guarded(AlbumShareStep.Read) { sharing.sharedWithAndGroups(albumId) }?.let { (recipients, groups) ->
            _uiState.update { it.copy(recipients = recipients, groups = groups) }
        }
    }

    private suspend fun <T> guarded(step: AlbumShareStep, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoSharingException.NotSignedIn) {
            _uiState.update { it.copy(signedIn = false) }
            null
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
