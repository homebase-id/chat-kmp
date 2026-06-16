@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.collectConversationOverview
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.contactTargetDrive
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.screens.contactbook.ContactBookService
import id.homebase.core.ui.screens.contactbook.ContactBookStream
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.ContactSaveResult
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.ui.screens.contactbook.saveContactDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "ContactDetailViewModel"
private const val OVERVIEW_MESSAGE_CAP = 1000

class ContactDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val contactBookStream: ContactBookStream,
    private val contactBookService: ContactBookService,
    private val conversationService: ConversationService,
    private val conversationStream: ConversationStream,
    private val chatMessageStream: ChatMessageStream,
    private val connectionService: ConnectionService,
    private val connectionNetworkProvider: ConnectionNetworkProvider,
    private val ownerSessionRepository: OwnerSessionRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.ContactBookDetail>()

    private val _uiState = MutableStateFlow(ContactDetailUiState())
    val uiState: StateFlow<ContactDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ContactDetailEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ContactDetailEvent> = _events.asSharedFlow()

    /** odinId for this contact (route arg first, else the resolved contact). */
    private val odinId: String?
        get() = route.odinId?.ifBlank { null } ?: _uiState.value.entry?.odinId?.ifBlank { null }

    init {
        // Keep the entry + connection status live (an edit / block reflects immediately).
        viewModelScope.launch {
            combine(
                contactBookStream.contacts,
                connectionService.connections,
                connectionService.circles,
            ) { contacts, conn, circ ->
                Triple(contacts, conn, circ)
            }.collect { (contacts, conn, circ) ->
                val entry = contacts.find { it.uniqueId.toString() == route.uniqueId }
                    ?: syntheticEntry()
                val domain = entry?.odinId
                val status = domain?.let { d ->
                    conn.map.entries.firstOrNull { it.key.domainName.equals(d, ignoreCase = true) }
                        ?.value?.status
                }
                // User-defined circles only — the Confirmed/Auto system circles are surfaced
                // through the connection status, not as chips.
                val circleNames = domain?.let { d ->
                    circ.circlesFor(d)
                        .filterNot { it.disabled }
                        .filterNot {
                            it.id.equals(CONFIRMED_CONNECTIONS_CIRCLE_ID, ignoreCase = true) ||
                                it.id.equals(AUTO_CONNECTIONS_CIRCLE_ID, ignoreCase = true)
                        }
                        .map { it.name }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                }.orEmpty()
                _uiState.update {
                    it.copy(
                        entry = entry,
                        connectionStatus = status,
                        circles = circleNames,
                        isLoading = false,
                    )
                }
            }
        }
        loadConversationOverview()
    }

    private fun syntheticEntry(): ContactBookEntry? {
        val domain = route.odinId?.ifBlank { null } ?: return null
        val uid = runCatching { Uuid.parse(route.uniqueId) }.getOrNull() ?: return null
        return ContactBookEntry(
            uniqueId = uid,
            fileId = uid,
            versionTag = null,
            odinId = domain,
            displayName = domain,
            source = ContactBookSource.CONNECTION,
        )
    }

    /** Loads media + groups-in-common ONLY when a 1:1 conversation already exists. */
    private fun loadConversationOverview() {
        val domain = odinId ?: return
        viewModelScope.launch {
            val items = conversationStream.conversations.value.items
            val convo = items.firstOrNull { c ->
                !c.isGroupConversation && c.participants.any { it.domainName.equals(domain, true) }
            } ?: return@launch

            val groups = items
                .filter { it.isGroupConversation && it.participants.any { p -> p.domainName.equals(domain, true) } }
                .map { GroupInCommonItem(it.id, it.name, it.avatarModel) }

            _uiState.update { it.copy(conversationId = convo.id, groupsInCommon = groups, overviewLoading = true) }

            try {
                val batch = chatMessageStream.fetchMessages(convo.id, limit = OVERVIEW_MESSAGE_CAP)
                val overview = withContext(Dispatchers.Default) { collectConversationOverview(batch) }
                _uiState.update { it.copy(overview = overview, overviewLoading = false) }
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to load contact overview" }
                _uiState.update { it.copy(overviewLoading = false) }
            }
        }
    }

    fun onAction(action: ContactDetailAction) {
        when (action) {
            ContactDetailAction.MessageClicked -> handleMessage()
            ContactDetailAction.EditClicked -> _uiState.update { it.copy(editOpen = true) }
            ContactDetailAction.CloseEdit -> _uiState.update { it.copy(editOpen = false) }
            is ContactDetailAction.SaveContact -> handleSave(action)
            ContactDetailAction.DeleteClicked -> _uiState.update { it.copy(confirm = ContactDetailConfirm.DELETE) }
            ContactDetailAction.BlockClicked -> _uiState.update { it.copy(confirm = ContactDetailConfirm.BLOCK) }
            ContactDetailAction.DisconnectClicked ->
                _uiState.update { it.copy(confirm = ContactDetailConfirm.DISCONNECT) }
            ContactDetailAction.UnblockClicked -> handleUnblock()
            ContactDetailAction.ConfirmYes -> handleConfirm()
            ContactDetailAction.ConfirmDismiss -> _uiState.update { it.copy(confirm = null) }
            is ContactDetailAction.OpenMedia -> _uiState.update { it.copy(fullScreenMedia = action.item) }
            ContactDetailAction.CloseMedia -> _uiState.update { it.copy(fullScreenMedia = null) }
            ContactDetailAction.SeeAllMediaClicked ->
                _uiState.value.conversationId?.let {
                    _events.tryEmit(ContactDetailEvent.SeeAllMedia(it.toString()))
                }
            is ContactDetailAction.OpenGroup ->
                _events.tryEmit(ContactDetailEvent.OpenConversation(action.conversationId))
            ContactDetailAction.BackClicked -> _events.tryEmit(ContactDetailEvent.Back)
        }
    }

    private fun handleMessage() {
        val domain = odinId ?: return
        viewModelScope.launch {
            val id = try {
                conversationService.createConversation(listOf(OdinId(domain)), "", null).conversationId
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ContactDetailEvent.Error)
                return@launch
            }
            _events.tryEmit(ContactDetailEvent.OpenConversation(id))
        }
    }

    private fun handleSave(action: ContactDetailAction.SaveContact) {
        val editing = _uiState.value.entry
        _uiState.update { it.copy(editOpen = false) }
        viewModelScope.launch {
            when (val result = saveContactDraft(
                service = contactBookService,
                draft = action.draft,
                editing = editing,
                photo = action.photo,
                contactDriveId = contactTargetDrive.alias,
            )) {
                is ContactSaveResult.Success -> {
                    contactBookStream.insertOrUpdateOptimistic(result.entry)
                    if (result.photoFailed) _events.tryEmit(ContactDetailEvent.PhotoError)
                }
                ContactSaveResult.Forbidden -> _events.tryEmit(ContactDetailEvent.Forbidden)
                ContactSaveResult.Failed -> _events.tryEmit(ContactDetailEvent.Error)
            }
        }
    }

    private fun handleUnblock() {
        val domain = odinId ?: return
        viewModelScope.launch {
            runCatching { connectionNetworkProvider.unblock(OdinId(domain)) }
                .onSuccess { connectionService.refresh() }
                .onFailure { emitConnectionError(it) }
        }
    }

    /**
     * The server returns 200 for no-ops and never echoes the new state, so we only refresh
     * after a successful call. A 403 means this app wasn't granted manage-connections
     * permission — surface that distinctly from a generic/transient failure.
     */
    private fun emitConnectionError(error: Throwable) {
        _events.tryEmit(
            if (error is ForbiddenException) ContactDetailEvent.ConnectionForbidden
            else ContactDetailEvent.Error
        )
    }

    private fun handleConfirm() {
        val confirm = _uiState.value.confirm ?: return
        val entry = _uiState.value.entry
        val domain = odinId
        _uiState.update { it.copy(confirm = null) }
        viewModelScope.launch {
            when (confirm) {
                ContactDetailConfirm.DELETE -> {
                    if (entry != null) {
                        contactBookStream.removeOptimistic(entry.uniqueId)
                        if (!contactBookService.delete(entry.uniqueId)) {
                            _events.tryEmit(ContactDetailEvent.Error)
                        }
                    }
                    _events.tryEmit(ContactDetailEvent.Back)
                }
                ContactDetailConfirm.BLOCK -> {
                    if (domain != null) {
                        runCatching { connectionNetworkProvider.block(OdinId(domain)) }
                            .onSuccess { connectionService.refresh() }
                            .onFailure { emitConnectionError(it) }
                    }
                }
                ContactDetailConfirm.DISCONNECT -> {
                    if (domain != null) {
                        runCatching { connectionNetworkProvider.disconnect(OdinId(domain)) }
                            .onSuccess { connectionService.refresh() }
                            .onFailure { emitConnectionError(it) }
                    }
                }
            }
        }
    }
}
