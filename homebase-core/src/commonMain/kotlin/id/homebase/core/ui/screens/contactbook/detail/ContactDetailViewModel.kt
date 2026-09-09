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
import id.homebase.api.client.connections.ConnectionRequestOrigin
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.Md5
import id.homebase.chat.conversationsettings.GroupInCommonItem
import id.homebase.chat.conversationsettings.collectConversationOverview
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.UnreviewBlockedException
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.contactbook.EmergencyContactService
import id.homebase.core.contactbook.LocateVerifyStatus
import id.homebase.core.contactbook.ReconcileAction
import id.homebase.core.contactbook.reconcileAction
import id.homebase.core.contactbook.setICanLocate
import id.homebase.core.ui.navigation.Route
import id.homebase.core.ui.screens.contactbook.CircleMemberStatus
import id.homebase.core.ui.screens.contactbook.assignableCircles
import id.homebase.core.ui.screens.contactbook.CircleAccessState
import id.homebase.core.ui.screens.contactbook.ContactState
import id.homebase.core.ui.screens.contactbook.circleAccessState
import id.homebase.core.ui.screens.contactbook.contactStateOf
import id.homebase.core.ui.screens.contactbook.personalCirclesFor
import id.homebase.core.ui.screens.contactbook.reviewCircleGroups
import id.homebase.core.ui.screens.contactbook.isAccessRevoked
import id.homebase.core.ui.screens.contactbook.isPersonalCircle
import id.homebase.core.ui.screens.contactbook.CircleMembersUi
import id.homebase.core.ui.screens.contactbook.RequestDirection
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.ContactSaveResult
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import id.homebase.core.ui.screens.contactbook.resolveCircleDrives
import id.homebase.core.ui.screens.contactbook.saveContactDraft
import id.homebase.core.ui.screens.contactbook.saveContactEdit
import id.homebase.core.ui.screens.contactbook.withOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val emergencyContacts: EmergencyContactService,
    private val overrideStore: ContactOverrideStore,
    private val publicIdentityRepository: PublicIdentityRepository,
) : ViewModel() {

    /**
     * Public profile (name/status) for a not-yet-connected identity, fetched from their
     * `sitedata.json` — the only profile data readable before connecting. Overlaid onto the
     * synthetic entry so a pending incoming request shows a real name instead of the bare
     * domain (#921). Null until resolved, or when the host is unreachable (best-effort — never
     * blocks Accept/Reject).
     */
    private val _publicIdentity = MutableStateFlow<id.homebase.api.client.identity.PublicIdentity?>(null)

    /** domain we last fetched the public profile for, so the collector only fires one lookup per
     *  contact rather than on every combine emission. */
    private var publicIdentityLoadedFor: String? = null

    /** The synced (pre-override) entry for the contact in view — the baseline for save diffs. */
    private var syncedEntry: ContactBookEntry? = null

    /** (uniqueId, versionTag) we last fetched ext_data for, to avoid re-fetching unchanged. */
    private var extLoadedFor: Pair<Uuid, Uuid?>? = null


    /** Latest circle-membership snapshot + contact list, cached from the init collector so
     *  [onCircleClicked] can build the circle-detail dialog without
     *  re-subscribing to the flows themselves. */
    private var latestCirc: id.homebase.chat.services.convo.contact.CircleMembershipState? = null
    private var latestContacts: List<ContactBookEntry> = emptyList()

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

    /** The three independently-changing inputs the state collector folds into [ContactDetailUiState]. */
    private data class CollectorInputs(
        val bundle: DetailBundle,
        val overrides: Map<Uuid, id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay>,
        val publicIdentity: id.homebase.api.client.identity.PublicIdentity?,
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
                _publicIdentity,
            ) { bundle, overrides, publicIdentity ->
                CollectorInputs(bundle, overrides, publicIdentity)
            }
                .collect { (bundle, overrides, publicIdentity) ->
                val contacts = bundle.contacts
                val conn = bundle.conn
                val circ = bundle.circ
                latestCirc = circ
                latestContacts = contacts
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
                val baseEntry = synced?.withOverride(overrides[synced.uniqueId])
                // Before connecting there's no synced contact record, so the entry is synthetic
                // (name = bare domain, no status/bio). Overlay the public profile so a pending
                // requester shows a real name/status/bio (#921). Only fill blanks — never clobber
                // a saved contact's own edited fields.
                val entry = if (baseEntry != null && publicIdentity != null &&
                    baseEntry.odinId?.equals(publicIdentity.odinId.domainName, ignoreCase = true) == true
                ) {
                    baseEntry.copy(
                        displayName = baseEntry.displayName
                            .takeIf { it.isNotBlank() && !it.equals(baseEntry.odinId, ignoreCase = true) }
                            ?: publicIdentity.displayNameOrDomain(),
                        status = baseEntry.status?.takeIf { it.isNotBlank() } ?: publicIdentity.status,
                        shortBio = baseEntry.shortBio?.takeIf { it.isNotBlank() }
                            ?: publicIdentity.shortBioSummary,
                    )
                } else {
                    baseEntry
                }
                val domain = entry?.odinId
                val isSelf = selfDomain != null && domain?.equals(selfDomain, ignoreCase = true) == true
                val registration = domain?.let { d ->
                    conn.map.entries.firstOrNull { it.key.domainName.equals(d, ignoreCase = true) }?.value
                }
                val status = registration?.status
                // Resolved to a saved contact's name when we have one, else the raw introducer domain.
                val introducedByName = registration
                    ?.takeIf {
                        it.connectionRequestOrigin == ConnectionRequestOrigin.Introduction &&
                            it.introducerOdinId != null
                    }
                    ?.introducerOdinId?.domainName?.let { introducer ->
                        contacts.firstOrNull { it.odinId.equals(introducer, ignoreCase = true) }
                            ?.displayName ?: introducer
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
                // User circles only — app default circles are surfaced through the connection
                // status, not as chips. Both halves are now reactive: real membership from
                // circ.circlesFor, pending deposits from the registration the refresh already
                // updated. A circle that goes pending mid-review therefore appears at once,
                // where the old once-per-contact live read left it invisible until the screen
                // was resumed.
                val pendingCircleIds = registration?.accessGrant?.pendingCircleIds
                    .orEmpty()
                    .map { it.toHexString() }
                    .toSet()
                val realCircles = domain?.let { d -> circ.circlesFor(d) }.orEmpty()
                    .filter { it.isPersonalCircle() }
                val realIds = realCircles.map { it.id.lowercase() }.toSet()
                val pendingCircles = pendingCircleIds
                    .mapNotNull { pid -> circ.circles.map { it.circle }.firstOrNull { it.id.equals(pid, ignoreCase = true) } }
                    .filter { it.isPersonalCircle() && it.id.lowercase() !in realIds }
                val circleItems = (
                    realCircles.map {
                        ContactCircleUi(
                            it.id,
                            it.name,
                            pending = false,
                            emoji = it.emoji,
                            // Membership alone overstates it: a read grant without its storage
                            // key reads as access the contact cannot actually exercise.
                            accessState = registration?.circleAccessState(it.id),
                        )
                    } +
                        pendingCircles.map {
                            ContactCircleUi(
                                it.id,
                                it.name,
                                pending = true,
                                emoji = it.emoji,
                                accessState = CircleAccessState.Pending,
                            )
                        }
                    )
                    .filter { it.name.isNotBlank() }
                    .distinctBy { it.id.lowercase() }
                    .sortedBy { it.name.lowercase() }
                // Every user-defined circle the user could add a contact to — independent of this
                // contact's membership. Same system-circle exclusion as the chips above; feeds the
                // pending-request circle picker (#921 Part B).
                val assignableCircles = circ.assignableCircles()
                _uiState.update {
                    it.copy(
                        entry = entry,
                        connectionStatus = status,
                        isAccessRevoked = registration?.isAccessRevoked() == true,
                        needsReview = registration != null &&
                            contactStateOf(
                                registration,
                                domain?.let { d -> circ.personalCirclesFor(d) }.orEmpty(),
                            ) == ContactState.New,
                        reviewCircleGroups = circ.reviewCircleGroups(),
                        circles = circleItems,
                        assignableCircles = assignableCircles,
                        isLoading = false,
                        isSelf = isSelf,
                        requestDirection = requestDirection,
                        introducedByName = introducedByName,
                    )
                }
                // Fetch the public profile once for a not-yet-connected identity so the pending
                // request card can show name/status. Best-effort — failure just leaves the domain.
                if (domain != null && status != ConnectionStatus.Connected &&
                    publicIdentityLoadedFor != domain
                ) {
                    publicIdentityLoadedFor = domain
                    loadPublicProfile(domain)
                }
            }
        }
        loadConversationOverview()
    }

    /**
     * Resolve a not-yet-connected identity's public profile (best-effort) and publish it to
     * [_publicIdentity], which the state collector overlays onto the entry. A failure (host
     * unreachable / no sitedata) leaves [_publicIdentity] null and the UI falls back to the
     * bare domain — Accept/Reject is never gated on this. See #921.
     */
    private fun loadPublicProfile(domain: String) {
        val odin = runCatching { OdinId(domain) }.getOrNull() ?: return
        viewModelScope.launch {
            val identity = runCatching { publicIdentityRepository.resolve(odin) }.getOrNull()
            if (identity != null) _publicIdentity.value = identity
        }
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

    // region Circles

    /** Opens the circle-detail dialog for [circleId] — real members synchronously (already
     *  bundled with the loaded circle list), then an async pending-roster fan-out. Always
     *  view-only from this screen (manageable = false): circle membership is managed from the
     *  Circles tab, not from a contact's page. */
    fun onCircleClicked(circleId: String) {
        val circ = latestCirc ?: return
        val match = circ.circles.firstOrNull { it.circle.id.equals(circleId, ignoreCase = true) } ?: return
        val domain = odinId
        val memberDomains = match.members.map { it.domainName }.toSet()
        val members = resolveCircleContactEntries(memberDomains, latestContacts).sortedBy { it.sortKey }
        // Members and pending deposits come out of the one circle snapshot, so the roster is
        // complete the moment the dialog opens — no second read, and no window where the two
        // lists disagree about who has converted.
        val pendingDomains = match.pendingMembers
            .map { it.odinId.domainName }
            .filterNot { d -> memberDomains.any { it.equals(d, ignoreCase = true) } }
            .toSet()
        val pending = resolveCircleContactEntries(pendingDomains, latestContacts)
            .sortedBy { it.sortKey }
        val isRealMember = domain != null && memberDomains.any { it.equals(domain, ignoreCase = true) }
        val isPendingMember = domain != null && pendingDomains.any { it.equals(domain, ignoreCase = true) }
        val viewerEntry = domain?.let { d -> latestContacts.firstOrNull { it.odinId.equals(d, ignoreCase = true) } }
            ?: syncedEntry
        _uiState.update {
            it.copy(
                circleDetail = CircleMembersUi(
                    circleId = match.circle.id,
                    circleName = match.circle.name,
                    circleEmoji = match.circle.emoji,
                    manageable = false,
                    members = members,
                    pendingMembers = pending,
                    isLoading = false,
                    drives = resolveCircleDrives(match.circle),
                    viewerStatus = when {
                        isRealMember -> CircleMemberStatus.Member
                        isPendingMember -> CircleMemberStatus.Pending
                        else -> null
                    },
                    viewerContactId = viewerEntry?.uniqueId,
                ),
            )
        }
    }

    fun onCircleDetailDismiss() {
        _uiState.update { it.copy(circleDetail = null) }
    }

    private fun resolveCircleContactEntries(
        domains: Set<String>,
        contacts: List<ContactBookEntry>,
    ): List<ContactBookEntry> {
        val byOdin = contacts.filter { !it.odinId.isNullOrBlank() }.associateBy { it.odinId!!.lowercase() }
        return domains.map { domain -> byOdin[domain.lowercase()] ?: syntheticCircleContactEntry(domain) }
    }

    private fun syntheticCircleContactEntry(domain: String): ContactBookEntry {
        val uid = Md5.toGuidId(domain.lowercase())
        return ContactBookEntry(
            uniqueId = uid,
            fileId = uid,
            versionTag = null,
            odinId = domain,
            displayName = domain,
            source = ContactBookSource.CONNECTION,
        )
    }

    // endregion

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
            is ContactDetailAction.AcceptRequestClicked -> {
                // Circle ids arrive as 32-char N-format strings; the accept API takes Uuids. Drop
                // any that fail to parse rather than aborting the accept.
                val circleUuids = action.circleIds.mapNotNull {
                    runCatching { Uuid.parseHex(it) }.getOrNull()
                }
                handleRequestAction(event = ContactDetailEvent.RequestAccepted) {
                    connectionRequestService.acceptIncomingRequest(it, circleUuids)
                }
            }
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

            ContactDetailAction.ReviewClicked -> openReview()
            ContactDetailAction.ReviewDismissed ->
                _uiState.update { it.copy(review = null) }
            is ContactDetailAction.ReviewSubmitted -> submitReview(action.circleIds)

            ContactDetailAction.UnreviewClicked ->
                _uiState.update { it.copy(unreview = UnreviewState()) }
            ContactDetailAction.UnreviewDismissed ->
                _uiState.update { it.copy(unreview = null) }
            ContactDetailAction.UnreviewConfirmed -> submitUnreview()
            is ContactDetailAction.CircleClicked -> onCircleClicked(action.circleId)
            ContactDetailAction.CircleDetailDismiss -> onCircleDetailDismiss()
            is ContactDetailAction.CircleMemberClicked -> _events.tryEmit(
                ContactDetailEvent.OpenOtherContact(action.entry.uniqueId.toString(), action.entry.odinId)
            )
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
     * Preflight (via [EmergencyContactService]) whether we currently hold temporal read access to
     * [peer]'s location drive and reconcile the cached `iCanLocate` flag against it: access → set
     * the flag and surface the newest-data timestamp; a definitive negative → drop the timestamp
     * (never the flag, #961); inconclusive → leave both untouched.
     */
    private suspend fun verifyLocateAccess(peer: OdinId) {
        when (val status = emergencyContacts.refresh(peer)) {
            is LocateVerifyStatus.Active -> {
                _uiState.update {
                    it.copy(locateNewestDataAt = status.newestModifiedMs?.let(::UnixTimeUtc))
                }
                val entry = _uiState.value.entry
                val versionTag = entry?.versionTag
                if (entry != null && versionTag != null &&
                    reconcileAction(hasAccess = true, entry.iCanLocate) == ReconcileAction.Set
                ) {
                    runCatching { contactRepository.setICanLocate(entry.uniqueId, versionTag) }
                        .onFailure { Logger.w(it, TAG) { "setICanLocate failed for ${peer.domainName}" } }
                }
            }

            is LocateVerifyStatus.Broken -> _uiState.update { it.copy(locateNewestDataAt = null) }

            LocateVerifyStatus.Loading, is LocateVerifyStatus.Unreachable -> Unit
        }
    }

    private fun openReview() {
        val domain = _uiState.value.entry?.odinId?.lowercase() ?: return
        val registration = connectionService.connections.value.map.entries
            .firstOrNull { it.key.domainName.equals(domain, ignoreCase = true) }?.value
        _uiState.update {
            it.copy(
                review = ReviewSheetState(
                    introducedBy = registration?.introducerOdinId?.domainName,
                    connectedAtMs = registration?.created,
                    alreadyHeldCircleIds = connectionService.circles.value
                        .circlesFor(domain)
                        .map { c -> c.id }
                        .toSet(),
                ),
            )
        }
    }

    /**
     * One call stamps the review and enrols the picked circles. Failure keeps the sheet open with
     * the error rather than dropping the selection — the call is idempotent, so retrying the
     * whole thing is safe.
     */
    private fun submitReview(circleIds: Set<String>) {
        val odinId = _uiState.value.entry?.odinId ?: return
        val open = _uiState.value.review ?: return
        _uiState.update { it.copy(review = open.copy(isSubmitting = true, failed = false)) }
        viewModelScope.launch {
            try {
                connectionService.reviewConnection(
                    OdinId(odinId),
                    circleIds.map { Uuid.parseHex(it) },
                )
                _uiState.update { it.copy(review = null) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Review of $odinId failed" }
                _uiState.update {
                    it.copy(review = open.copy(isSubmitting = false, failed = true))
                }
            }
        }
    }

    /**
     * Clear the review stamp. Revokes nothing — the contact keeps every circle and every grant,
     * and only the owner's record of having vouched for them goes away.
     *
     * The server refuses while they still hold a review-granted personal circle, and names all of
     * them, so the dialog can list the set instead of the user discovering it one refusal at a
     * time.
     */
    private fun submitUnreview() {
        val odinId = _uiState.value.entry?.odinId ?: return
        _uiState.update { it.copy(unreview = UnreviewState(isSubmitting = true)) }
        viewModelScope.launch {
            try {
                connectionService.clearConnectionReview(OdinId(odinId))
                _uiState.update { it.copy(unreview = null) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: UnreviewBlockedException) {
                _uiState.update {
                    it.copy(
                        unreview = UnreviewState(
                            blockingCircles = e.circles.mapNotNull { c -> c.name },
                        ),
                    )
                }
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Un-review of $odinId failed" }
                _uiState.update { it.copy(unreview = UnreviewState(failed = true)) }
            }
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
                    if (result.additionsFailed) {
                        _events.tryEmit(ContactDetailEvent.AdditionsFailed)
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
                            if (contactRepository.delete(entry.uniqueId)) ContactDetailEvent.DeletedAndBack
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
