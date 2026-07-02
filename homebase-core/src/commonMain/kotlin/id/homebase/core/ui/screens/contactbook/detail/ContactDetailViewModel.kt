@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.peer.temporal.TemporalDriveReadProvider
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.collectConversationOverview
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.contactbook.ReconcileAction
import id.homebase.core.contactbook.reconcileAction
import id.homebase.core.contactbook.setICanLocate
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.screens.contactbook.RequestDirection
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.ContactSaveResult
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import id.homebase.core.ui.screens.contactbook.saveContactDraft
import id.homebase.core.ui.screens.contactbook.saveContactEdit
import id.homebase.core.ui.screens.contactbook.withOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
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
    private val contactRepository: ContactRepository,
    private val conversationService: ConversationService,
    private val conversationStream: ConversationStream,
    private val chatMessageStream: ChatMessageStream,
    private val connectionService: ConnectionService,
    private val connectionRequestService: ConnectionRequestService,
    private val connectionNetworkProvider: ConnectionNetworkProvider,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val credentialsManager: CredentialsManager,
    private val temporalDriveReadProvider: TemporalDriveReadProvider,
    private val overrideStore: ContactOverrideStore,
) : ViewModel() {

    /** The synced (pre-override) entry for the contact in view — the baseline for save diffs. */
    private var syncedEntry: ContactBookEntry? = null

    /** (uniqueId, versionTag) we last fetched ext_data for, to avoid re-fetching unchanged. */
    private var extLoadedFor: Pair<Uuid, Uuid?>? = null

    /**
     * Fetches the on-demand `ext_data` payload (Experience / Bio rich-text) once per contact version
     * and folds the Experience attribute into state. [ContactRepository.loadExtData] is a cheap no-op
     * (returns null) when the contact has no such payload.
     */
    private fun loadExtData(contact: Contact) {
        val key = contact.uniqueId to contact.versionTag
        if (extLoadedFor == key) return
        extLoadedFor = key
        viewModelScope.launch {
            val experience = contactRepository.loadExtData(contact)?.experience
            _uiState.update { it.copy(experience = experience, experienceImage = null) }
            // The Experience attribute references its image by payload key; fetch the bytes too.
            val imageKey = experience?.imageKey?.takeIf { it.isNotBlank() }
            if (imageKey != null) {
                val bytes = contactRepository.loadPayloadBytes(contact, imageKey)
                if (bytes != null && _uiState.value.experience === experience) {
                    _uiState.update { it.copy(experienceImage = bytes) }
                }
            }
        }
    }

    private val route = savedStateHandle.toRoute<Route.ContactBookDetail>()

    /** The drive whose temporal grant gates "can I locate this contact in an emergency?". */
    private val locationDrive = locationLabeledDrive.drive.alias

    private val _uiState = MutableStateFlow(ContactDetailUiState())
    val uiState: StateFlow<ContactDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ContactDetailEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ContactDetailEvent> = _events.asSharedFlow()

    /** odinId for this contact (route arg first, else the resolved contact). */
    private val odinId: String?
        get() = route.odinId?.ifBlank { null } ?: _uiState.value.entry?.odinId?.ifBlank { null }

    private data class DetailBundle(
        val contacts: List<ContactBookEntry>,
        val conn: id.homebase.chat.services.convo.contact.ConnectionState,
        val circ: id.homebase.chat.services.convo.contact.CircleMembershipState,
        val incoming: List<IncomingConnectionRequestUiModel>,
        val outgoing: List<OutgoingConnectionRequestUiModel>,
    )

    init {
        // Self-sufficient on deep-link: ensure the repo has loaded even if the list wasn't opened.
        viewModelScope.launch { contactRepository.ensureLoaded() }
        // Idempotent — makes pending-request state available even on a deep-link into detail.
        viewModelScope.launch { connectionRequestService.start() }
        // Keep the entry + connection status live (an edit / block reflects immediately).
        viewModelScope.launch {
            val selfDomain = runCatching { credentialsManager.getActiveDomain()?.domainName }.getOrNull()
            combine(
                combine(
                    contactRepository.contacts.map { list -> list.mapNotNull { it.toContactBookEntry() } },
                    connectionService.connections,
                    connectionService.circles,
                    connectionRequestService.incomingRequests,
                    connectionRequestService.outgoingRequests,
                ) { contacts, conn, circ, incoming, outgoing ->
                    DetailBundle(contacts, conn, circ, incoming, outgoing)
                },
                overrideStore.overrides,
            ) { bundle, overrides -> bundle to overrides }.collect { (bundle, overrides) ->
                val contacts = bundle.contacts
                val conn = bundle.conn
                val circ = bundle.circ
                val synced = contacts.find { it.uniqueId.toString() == route.uniqueId }
                    ?: syntheticEntry()
                syncedEntry = synced
                // Load this contact's override (bulk app-data tier) and its on-demand ext_data
                // (Experience/Bio) on first view; cheap no-ops after.
                contactRepository.contacts.value
                    .firstOrNull { it.uniqueId.toString() == route.uniqueId }
                    ?.let { rawContact ->
                        overrideStore.hydrate(rawContact)
                        loadExtData(rawContact)
                    }
                val entry = synced?.withOverride(overrides[synced.uniqueId])
                val domain = entry?.odinId
                val isSelf = selfDomain != null && domain?.equals(selfDomain, ignoreCase = true) == true
                val status = domain?.let { d ->
                    conn.map.entries.firstOrNull { it.key.domainName.equals(d, ignoreCase = true) }
                        ?.value?.status
                }
                val requestDirection = domain?.let { d ->
                    when {
                        bundle.incoming.any { it.senderOdinId.domainName.equals(d, ignoreCase = true) } ->
                            RequestDirection.INCOMING
                        bundle.outgoing.any { it.recipientOdinId.domainName.equals(d, ignoreCase = true) } ->
                            RequestDirection.OUTGOING
                        else -> null
                    }
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
                        isSelf = isSelf,
                        requestDirection = requestDirection,
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
            ContactDetailAction.SyncClicked -> handleSync()
            ContactDetailAction.EditClicked -> _uiState.update { it.copy(editOpen = true) }
            ContactDetailAction.CloseEdit -> _uiState.update { it.copy(editOpen = false) }
            is ContactDetailAction.SaveContact -> handleSave(action)
            ContactDetailAction.DeleteClicked -> _uiState.update { it.copy(confirm = ContactDetailConfirm.DELETE) }
            ContactDetailAction.BlockClicked -> _uiState.update { it.copy(confirm = ContactDetailConfirm.BLOCK) }
            ContactDetailAction.DisconnectClicked ->
                _uiState.update { it.copy(confirm = ContactDetailConfirm.DISCONNECT) }
            ContactDetailAction.AcceptRequestClicked -> handleRequestAction(
                event = ContactDetailEvent.RequestAccepted,
            ) { connectionRequestService.acceptIncomingRequest(it) }
            ContactDetailAction.RejectRequestClicked -> handleRequestAction(
                event = ContactDetailEvent.RequestRejected,
            ) { connectionRequestService.rejectIncomingRequest(it) }
            ContactDetailAction.CancelRequestClicked -> handleRequestAction(
                event = ContactDetailEvent.RequestCancelled,
            ) { connectionRequestService.cancelOutgoingRequest(it) }
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

    /**
     * Best-effort server-side enrichment from the identity's public profile. The endpoint is
     * fire-and-forget (202 Accepted) and the enriched contact lands later via drive sync, so we
     * just acknowledge the request — there's no success/failure to report back synchronously.
     */
    private fun handleSync() {
        val domain = odinId ?: return
        val peer = OdinId(domain)
        _events.tryEmit(ContactDetailEvent.SyncStarted)
        viewModelScope.launch {
            contactRepository.sync(peer)
            verifyLocateAccess(peer)
        }
    }

    /**
     * Preflight whether we currently hold temporal read access to [peer]'s location drive (reads no
     * data, fires no notification on the peer) and reconcile the cached `iCanLocate` "emergency
     * contact" flag against that authoritative answer:
     * - access → set the flag (add them to the emergency list) and surface the newest-data timestamp.
     * - definitively no access → clear a stale flag.
     * - network/parse failure → inconclusive; leave the flag and the timestamp untouched.
     *
     * The flag write needs the contact's [ContactBookEntry.versionTag]; a synthetic entry (deep-link
     * with no stored contact) has none, so we can still show freshness but can't persist the flag.
     */
    private suspend fun verifyLocateAccess(peer: OdinId) {
        val status = runCatching { temporalDriveReadProvider.verifyTemporalAccess(peer, locationDrive) }
            .onFailure { Logger.w(it, TAG) { "verifyTemporalAccess failed for ${peer.domainName}" } }
            .getOrNull() ?: return

        val entry = _uiState.value.entry
        val versionTag = entry?.versionTag

        // "Emergency contact" means the peer currently grants us temporal read access to their
        // location drive. windowSeconds is NOT a reliable ACL-type discriminator — a real
        // emergency-circle grant has been observed reporting windowSeconds=null in practice, same
        // as a plain full read (see issue #875) — so gate on hasAccess alone.
        if (status.hasAccess) {
            // newestFileModified is only a real timestamp when we have access; 0 (ZeroTime) means the
            // drive has no files yet — render "no data", not the epoch.
            val newest = status.newestFileModified.takeIf { it.milliseconds > 0 }
            _uiState.update { it.copy(locateNewestDataAt = newest) }
            if (entry != null && versionTag != null &&
                reconcileAction(status.hasAccess, entry.iCanLocate) == ReconcileAction.Set
            ) {
                runCatching { contactRepository.setICanLocate(entry.uniqueId, versionTag) }
                    .onFailure { Logger.w(it, TAG) { "setICanLocate failed for ${peer.domainName}" } }
            }
        } else {
            // Never clear the flag here (issue #961): hasAccess=false is not a trustworthy
            // revocation — only the peer's explicit revocation message clears iCanLocate
            // (EmergencyContactReceiveService.onRevoked). See reconcileAction.
            _uiState.update { it.copy(locateNewestDataAt = null) }
        }
    }

    private fun handleSave(action: ContactDetailAction.SaveContact) {
        val editing = _uiState.value.entry
        val synced = syncedEntry
        _uiState.update { it.copy(editOpen = false) }
        viewModelScope.launch {
            // Any *identity* contact (has an odinId) is enriched on sync — from the peer's
            // ProfileDrive when connected, else from their public profile card — and the merge
            // overwrites content per-leaf, so its edits must go to the enrichment-proof override
            // (not just connected ones; connection status can also flip later). Only a pure manual
            // contact (no odinId) is never synced, so its primaries write to content as normal.
            val result = if (editing != null && synced != null) {
                saveContactEdit(
                    store = overrideStore,
                    repo = contactRepository,
                    useOverride = !synced.odinId.isNullOrBlank() && synced.versionTag != null,
                    editing = editing,
                    synced = synced,
                    draft = action.draft,
                    additionalPhones = action.additionalPhones,
                    additionalEmails = action.additionalEmails,
                    photo = action.photo,
                )
            } else {
                saveContactDraft(
                    repo = contactRepository,
                    draft = action.draft,
                    editing = editing,
                    photo = action.photo,
                )
            }
            when (result) {
                is ContactSaveResult.Success -> {
                    // repo.save already applied the optimistic update.
                    if (result.photoFailed) _events.tryEmit(ContactDetailEvent.PhotoError)
                    if (result.clearedFieldsIgnored) {
                        _events.tryEmit(ContactDetailEvent.ClearUnsupported)
                    }
                }
                ContactSaveResult.Forbidden -> _events.tryEmit(ContactDetailEvent.Forbidden)
                ContactSaveResult.Failed -> _events.tryEmit(ContactDetailEvent.Error)
            }
        }
    }

    /**
     * Runs a connection-request mutation (accept / reject / cancel) for this contact's odinId,
     * emitting [event] on success. The services apply the optimistic list update and refresh, so
     * the detail screen's request/connection status reconciles on the next combine emission.
     */
    private fun handleRequestAction(
        event: ContactDetailEvent,
        action: suspend (OdinId) -> Unit,
    ) {
        val domain = odinId ?: return
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            try {
                runCatching { action(OdinId(domain)) }
                    .onSuccess { _events.tryEmit(event) }
                    .onFailure { emitConnectionError(it) }
            } finally {
                _uiState.update { it.copy(actionInProgress = false) }
            }
        }
    }

    private fun handleUnblock() {
        val domain = odinId ?: return
        _uiState.update { it.copy(actionInProgress = true) }
        viewModelScope.launch {
            try {
                runCatching { connectionNetworkProvider.unblock(OdinId(domain)) }
                    .onSuccess {
                        connectionService.refresh()
                        _events.tryEmit(ContactDetailEvent.Unblocked)
                    }
                    .onFailure { emitConnectionError(it) }
            } finally {
                _uiState.update { it.copy(actionInProgress = false) }
            }
        }
    }

    /**
     * The server returns 200 for no-ops and never echoes the new state, so we only refresh
     * after a successful call. A 403 means this app wasn't granted manage-connections
     * permission — surface that distinctly from a generic/transient failure. A withdrawn
     * request (accept raced a since-completed cancel-outgoing on the sender's side) is also
     * distinguished — [ConnectionRequestService.acceptIncomingRequest] already dropped the
     * stale local copy before rethrowing, so we just need the specific message here.
     */
    private fun emitConnectionError(error: Throwable) {
        _events.tryEmit(
            when {
                error is ForbiddenException -> ContactDetailEvent.ConnectionForbidden
                error is ClientException &&
                    error.errorCode == OdinClientErrorCode.IncomingRequestNotFound ->
                    ContactDetailEvent.RequestWithdrawn
                else -> ContactDetailEvent.Error
            }
        )
    }

    private fun handleConfirm() {
        val confirm = _uiState.value.confirm ?: return
        val entry = _uiState.value.entry
        val domain = odinId
        val wasConnected = _uiState.value.isConnected
        _uiState.update { it.copy(confirm = null, actionInProgress = true) }
        viewModelScope.launch {
            try {
                when (confirm) {
                    ContactDetailConfirm.DELETE -> {
                        if (entry == null) {
                            _events.tryEmit(ContactDetailEvent.Back)
                            return@launch
                        }
                        // A connected contact must be disconnected before its record is removed —
                        // otherwise deleting the address-book entry leaves the connection (and the
                        // access it granted) live. Tear that down first; abort the delete if it
                        // fails so we don't silently drop the contact while the connection lingers.
                        if (wasConnected && domain != null) {
                            val disconnected = runCatching {
                                connectionNetworkProvider.disconnect(OdinId(domain))
                            }
                                .onSuccess { connectionService.refresh() }
                                .onFailure { emitConnectionError(it) }
                                .isSuccess
                            if (!disconnected) return@launch
                        }
                        // repo.delete does the optimistic remove and restores on failure.
                        val event = try {
                            if (contactRepository.delete(entry.uniqueId)) ContactDetailEvent.Back
                            else ContactDetailEvent.DeleteError
                        } catch (e: ForbiddenException) {
                            ContactDetailEvent.DeleteForbidden
                        }
                        _events.tryEmit(event)
                    }
                    ContactDetailConfirm.BLOCK -> {
                        if (domain != null) {
                            runCatching { connectionNetworkProvider.block(OdinId(domain)) }
                                .onSuccess {
                                    connectionService.refresh()
                                    _events.tryEmit(ContactDetailEvent.Blocked)
                                }
                                .onFailure { emitConnectionError(it) }
                        }
                    }
                    ContactDetailConfirm.DISCONNECT -> {
                        if (domain != null) {
                            runCatching { connectionNetworkProvider.disconnect(OdinId(domain)) }
                                .onSuccess {
                                    connectionService.refresh()
                                    _events.tryEmit(ContactDetailEvent.Disconnected)
                                }
                                .onFailure { emitConnectionError(it) }
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(actionInProgress = false) }
            }
        }
    }
}
