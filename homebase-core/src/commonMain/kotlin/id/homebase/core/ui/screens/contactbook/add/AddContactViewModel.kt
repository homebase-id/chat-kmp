@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.connections.RecipientResolution
import id.homebase.core.ui.screens.contactbook.ContactDraft
import id.homebase.core.ui.screens.contactbook.ContactSaveResult
import id.homebase.core.ui.screens.contactbook.saveContactDraft
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi

/**
 * Drives the full-screen Add Contact flow. By default it leads with the Homebase ID and resolves
 * the identity live via [PublicIdentityRepository] (the same debounced lookup the connection-request
 * composer uses), pre-filling the name from the resolved profile. The user can switch to a manual
 * mode to enter a contact by hand without an ID.
 *
 * Persistence reuses [saveContactDraft] so a hand-added contact behaves identically to one created
 * from the edit sheet.
 */
class AddContactViewModel(
    private val repo: ContactRepository,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val connectionService: ConnectionService,
    private val connectionRequestService: ConnectionRequestService,
) : ViewModel() {

    private val _state = MutableStateFlow(AddContactUiState())

    /**
     * Public state, with [AddContactUiState.relation] and [AddContactUiState.alreadySaved] folded
     * in live from the connection map, the pending incoming/outgoing request lists, and the saved
     * contacts. This is what lets the resolved-identity card offer exactly the applicable action
     * (send / accept / reject / cancel / nothing) and avoid offering to save a contact twice.
     */
    val state: StateFlow<AddContactUiState> =
        combine(
            _state,
            connectionService.connections,
            connectionRequestService.incomingRequests,
            connectionRequestService.outgoingRequests,
            repo.contacts,
        ) { s, conn, incoming, outgoing, contacts ->
            val domain = (s.resolution as? RecipientResolution.Resolved)
                ?.identity?.odinId?.domainName?.lowercase()
            val status = domain?.let { d ->
                conn.map.entries
                    .firstOrNull { it.key.domainName.lowercase() == d }
                    ?.value?.status
            }
            val relation = when {
                domain == null -> IdentityRelation.NONE
                status == ConnectionStatus.Connected -> IdentityRelation.CONNECTED
                status == ConnectionStatus.Blocked -> IdentityRelation.BLOCKED
                incoming.any { it.senderOdinId.domainName.lowercase() == domain } ->
                    IdentityRelation.INCOMING_PENDING
                outgoing.any { it.recipientOdinId.domainName.lowercase() == domain } ->
                    IdentityRelation.OUTGOING_PENDING
                else -> IdentityRelation.NONE
            }
            val alreadySaved = domain != null &&
                contacts.any { it.content.odinId?.lowercase() == domain }
            s.copy(relation = relation, alreadySaved = alreadySaved)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddContactUiState())

    private val _events = MutableSharedFlow<AddContactEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AddContactEvent> = _events.asSharedFlow()

    init {
        // All idempotent — already started by app bootstrap; we re-trigger so the connection map,
        // the pending-request lists, and the contact list are hydrated even if Add Contact is the
        // first screen reached, keeping `relation` / `alreadySaved` accurate from the start.
        connectionService.start()
        viewModelScope.launch { connectionRequestService.start() }
        viewModelScope.launch { repo.ensureLoaded() }
    }

    private var resolveJob: Job? = null

    /**
     * The (given, surname) we last auto-filled from a resolved identity. Lets a *re-resolution*
     * (the user corrected the Homebase ID) replace the prefilled name, while a name the user has
     * since typed by hand is left untouched.
     */
    private var autoFilledName: Pair<String, String>? = null

    fun onAction(action: AddContactAction) {
        when (action) {
            is AddContactAction.OdinIdChanged -> {
                _state.update { it.copy(draft = it.draft.copy(odinId = action.value)) }
                startResolution(action.value)
            }
            is AddContactAction.DraftChanged -> _state.update { it.copy(draft = action.draft) }
            is AddContactAction.PhotoPicked -> _state.update { it.copy(photo = action.photo) }
            AddContactAction.SwitchToManual -> {
                resolveJob?.cancel()
                _state.update {
                    it.copy(mode = AddContactMode.MANUAL, resolution = RecipientResolution.Idle)
                }
            }
            AddContactAction.SwitchToByIdentity ->
                _state.update { it.copy(mode = AddContactMode.BY_IDENTITY) }
            AddContactAction.SaveClicked -> save()
            AddContactAction.AcceptRequestClicked -> handleRequestAction(
                AddContactEvent.RequestAccepted,
            ) { connectionRequestService.acceptIncomingRequest(it) }
            AddContactAction.RejectRequestClicked -> handleRequestAction(
                AddContactEvent.RequestRejected,
            ) { connectionRequestService.rejectIncomingRequest(it) }
            AddContactAction.CancelRequestClicked -> handleRequestAction(
                AddContactEvent.RequestCancelled,
            ) { connectionRequestService.cancelOutgoingRequest(it) }
            AddContactAction.BackClicked -> _events.tryEmit(AddContactEvent.Back)
        }
    }

    /**
     * Runs an accept/reject/cancel against the resolved identity. The relation/alreadySaved
     * state updates reactively once the service refreshes its lists, so we only manage the
     * in-flight flag and surface a success/failure event for the snackbar.
     */
    private fun handleRequestAction(
        success: AddContactEvent,
        action: suspend (OdinId) -> Unit,
    ) {
        val odinId = (_state.value.resolution as? RecipientResolution.Resolved)?.identity?.odinId
            ?: return
        if (_state.value.actionInProgress) return
        _state.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            try {
                runCatching { action(odinId) }
                    .onSuccess { _events.tryEmit(success) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Logger.w(it) { "Request action failed for $odinId" }
                        _events.tryEmit(AddContactEvent.RequestActionFailed)
                    }
            } finally {
                _state.update { it.copy(actionInProgress = false) }
            }
        }
    }

    /** Debounced identity lookup, mirroring `ConnectRequestViewModel.startRecipientResolution`. */
    private fun startResolution(rawValue: String) {
        resolveJob?.cancel()
        val trimmed = rawValue.trim()

        if (trimmed.isEmpty()) {
            _state.update { it.copy(resolution = RecipientResolution.Idle) }
            return
        }
        if (!OdinId.isValid(trimmed)) {
            _state.update { it.copy(resolution = RecipientResolution.InvalidFormat) }
            return
        }

        _state.update { it.copy(resolution = RecipientResolution.Resolving) }
        resolveJob = viewModelScope.launch {
            delay(450)
            val odinId = OdinId(trimmed)
            val identity = try {
                publicIdentityRepository.resolve(odinId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to resolve $odinId" }
                null
            }

            // The field changed while we were resolving — drop this stale result.
            if (_state.value.draft.odinId.trim() != trimmed) return@launch

            if (identity == null) {
                _state.update { it.copy(resolution = RecipientResolution.NotFound) }
                return@launch
            }

            val resolvedGiven =
                identity.firstName?.ifBlank { null } ?: identity.displayNameOrDomain()
            val resolvedSurname = identity.surName.orEmpty()
            // Refresh the prefilled name when it's still blank or still holds what we last
            // auto-filled (the user re-typed the ID). Leave a hand-edited name alone.
            val current = _state.value.draft
            val nameUntouched = (current.givenName.isBlank() && current.surname.isBlank()) ||
                (current.givenName to current.surname) == autoFilledName
            if (nameUntouched) autoFilledName = resolvedGiven to resolvedSurname

            _state.update { state ->
                state.copy(
                    resolution = RecipientResolution.Resolved(identity),
                    draft = if (nameUntouched) {
                        state.draft.copy(givenName = resolvedGiven, surname = resolvedSurname)
                    } else {
                        state.draft
                    },
                )
            }
        }
    }

    private fun save() {
        val current = _state.value
        if (!current.draft.isSavable || current.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = saveContactDraft(
                repo = repo,
                draft = current.draft,
                editing = null,
                photo = current.photo,
            )
            _state.update { it.copy(isSaving = false) }
            when (result) {
                is ContactSaveResult.Success -> {
                    if (result.photoFailed) _events.tryEmit(AddContactEvent.PhotoFailed)
                    _events.tryEmit(AddContactEvent.Saved)
                }
                ContactSaveResult.Forbidden -> _events.tryEmit(AddContactEvent.Forbidden)
                ContactSaveResult.Failed -> _events.tryEmit(AddContactEvent.Error)
            }
        }
    }
}
