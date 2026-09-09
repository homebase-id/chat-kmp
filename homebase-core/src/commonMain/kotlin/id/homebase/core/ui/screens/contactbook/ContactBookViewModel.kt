@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.Md5
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ConnectionState
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.auth.toConnectionStatus
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.contactTargetDrive
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unlike the optional add-ons (Vault, Moments, Location, Stickers), the Contact Book has
 * **no drive-mount/activation step** — by design, not omission. It operates on the system
 * `contactLabeledDrive`, which is in [id.homebase.core.config.mandatorySyncDrives] and is
 * mounted unconditionally by `AuthConnectionCoordinator.ensureMandatoryMounted()` before
 * bootstrap. So this ViewModel never injects [id.homebase.core.sync.OptionalDriveActivation]
 * and never mounts: `isActivated(contactLabeledDrive)` would be a constant `true`.
 *
 * This is the same end-state the vault login-mount-race fix reached for Vault — no eager
 * re-mount at login, rely on the drive already being mounted by the login pre-mount path —
 * the only difference being mandatory-pre-mount here vs. registry-pre-mount there. The
 * post-login "0 records" race that fix addressed therefore cannot occur for Contacts.
 * See also [id.homebase.core.contactbook.ContactBookPreferences] (no `activated` flag).
 */
class ContactBookViewModel(
    private val repo: ContactRepository,
    private val preferences: ContactBookPreferences,
    private val conversationService: ConversationService,
    private val connectionService: ConnectionService,
    private val connectionRequestService: ConnectionRequestService,
    private val overrideStore: ContactOverrideStore,
    ownerSessionRepository: OwnerSessionRepository,
    authConnectionCoordinator: AuthConnectionCoordinator,
    eventBus: EventBus,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(ContactTab.KNOWN)
    private val _searchQuery = MutableStateFlow("")
    private val _filter = MutableStateFlow(ContactFilter.ALL)
    private val _overlay = MutableStateFlow<ContactBookOverlay?>(null)
    private val _circles = MutableStateFlow<List<CircleWithMembers>>(emptyList())
    private val _circlesLoading = MutableStateFlow(false)
    private val _circleMembers = MutableStateFlow<CircleMembersUi?>(null)

    /** Owner avatar + connection/sync status for the header (mirrors the Moments header). */
    private data class HeaderBundle(
        val ownerSession: OwnerSession? = null,
        val connectionStatus: AppConnectionStatus = AppConnectionStatus.Disconnected,
        val driveIsSyncing: Boolean = false,
        val hasDriveError: Boolean = false,
    )

    private val _header = MutableStateFlow(HeaderBundle())

    init {
        // Make the screen self-sufficient: load the contact list on entry rather than
        // relying on the post-auth bootstrap (onPostAuthenticated -> start()), which can be
        // skipped by the headless/foreground promotion race and leave the list spinning.
        // Idempotent once loaded.
        viewModelScope.launch { repo.ensureLoaded() }
        // Idempotent — already started by the conversation list / app bootstrap; calling it here
        // makes the Requests pill self-sufficient if the Contact Book is the first screen shown.
        viewModelScope.launch { connectionRequestService.start() }
        // Load any user overrides (bulk app-data tier) for contacts that advertise the payload, so
        // the list reflects a renamed connected contact. Cheap no-op for the rest.
        viewModelScope.launch {
            repo.contacts.collect { list -> list.forEach { overrideStore.hydrate(it) } }
        }
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _header.update { it.copy(ownerSession = session) }
            }
        }
        viewModelScope.launch {
            authConnectionCoordinator.connectionState.collectLatest { state ->
                _header.update { it.copy(connectionStatus = state.toConnectionStatus()) }
            }
        }
        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.SyncAllStarted || it is BackendEvent.SyncAllStopped }
                .collectLatest { event ->
                    when (event) {
                        is BackendEvent.SyncAllStarted -> _header.update {
                            it.copy(driveIsSyncing = true, hasDriveError = false)
                        }
                        is BackendEvent.SyncAllStopped -> _header.update {
                            it.copy(
                                driveIsSyncing = false,
                                hasDriveError = event.result is BackendEvent.SyncAllResult.Failure,
                            )
                        }
                        else -> Unit
                    }
                }
        }
        // Circles tab + any open CircleMembersSheet now derive from ConnectionService.circles
        // directly instead of a one-shot fetch taken at the moment the circle was tapped —
        // previously an add/remove from the picker (a different ViewModel instance) updated
        // ConnectionService.refresh()'s data but never reached this screen's own snapshot,
        // so the sheet just sat there stale until closed and reopened (#1096).
        viewModelScope.launch {
            connectionService.circles.collect { circleState ->
                val circles = circleState.circles
                    .filterNot { it.circle.disabled }
                    .sortedWith(compareBy({ it.circle.circleSortRank() }, { it.circle.name.lowercase() }))
                _circles.value = circles
                _circlesLoading.value = !circleState.isLoaded

                val open = _circleMembers.value ?: return@collect
                val match = circles.firstOrNull { it.circle.id == open.circleId } ?: return@collect
                val domains = match.members.map { it.domainName }.toSet()
                val pendingDomains = match.pendingMembers
                    .map { p -> p.odinId.domainName.lowercase() }
                    .filterNot { d -> domains.any { it.equals(d, ignoreCase = true) } }
                    .toSet()
                // Pending is part of the circle value now, so a pending-only change alters this
                // flow and lands here — the case #1096 said StateFlow would conflate, because
                // pending used to live outside the value entirely.
                _circleMembers.update {
                    it?.copy(
                        members = entriesForDomains(domains, entries.value).sortedBy { m -> m.sortKey },
                        pendingMembers = entriesForDomains(pendingDomains, entries.value)
                            .sortedBy { m -> m.sortKey },
                        drives = resolveCircleDrives(match.circle),
                    )
                }
            }
        }
    }

    private data class ContactsBundle(
        val contacts: List<ContactBookEntry>,
        val loaded: Boolean,
        val connections: ConnectionState,
        val overrides: Map<Uuid, ContactFieldOverlay>,
    )

    private data class UiBits(
        val query: String,
        val filter: ContactFilter,
        val tab: ContactTab,
        val overlay: ContactBookOverlay?,
    )

    private data class CirclesBundle(
        val circles: List<CircleWithMembers>,
        val loading: Boolean,
        val members: CircleMembersUi?,
    )

    private data class RequestsBundle(
        val incoming: List<IncomingConnectionRequestUiModel>,
        val outgoing: List<OutgoingConnectionRequestUiModel>,
    )

    /** Server-shaped repository contacts projected into the flat UI model. */
    private val entries: StateFlow<List<ContactBookEntry>> = repo.contacts
        .map { list -> list.mapNotNull { it.toContactBookEntry() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<ContactBookUiState> = combine(
        combine(
            entries,
            repo.isLoaded,
            connectionService.connections,
            overrideStore.overrides,
        ) { c, l, conn, overrides ->
            ContactsBundle(c, l, conn, overrides)
        },
        combine(_searchQuery, _filter, _selectedTab, _overlay) { q, f, tab, o ->
            UiBits(q, f, tab, o)
        },
        combine(_circles, _circlesLoading, _circleMembers) { c, l, m -> CirclesBundle(c, l, m) },
        _header,
        combine(
            connectionRequestService.incomingRequests,
            connectionRequestService.outgoingRequests,
        ) { incoming, outgoing -> RequestsBundle(incoming, outgoing) },
    ) { contactsData, ui, circlesData, header, requestsData ->
        // Apply user overrides up front so every downstream list (Known, New, Requests,
        // introducer names) shows the user's renamed/edited values, not the synced ones.
        val overriddenContacts = contactsData.contacts
            .map { it.withOverride(contactsData.overrides[it.uniqueId]) }
        val connectedRegs = contactsData.connections.map
            .filterValues { it.status == ConnectionStatus.Connected }
        val connectedDomains = connectedRegs.keys.map { it.domainName.lowercase() }.toSet()

        // The three states need both halves — the review stamp AND personal-circle membership —
        // so nothing is classified until the circles have loaded. Guessing from the stamp alone
        // would show every circle member as Chat for a moment and then flip them, which reads as
        // the app changing its mind about who the user trusts.
        val personalCirclesByDomain = buildMap<String, MutableList<RedactedCircleDefinition>> {
            circlesData.circles
                .filter { it.circle.isPersonalCircle() }
                .forEach { cwm ->
                    cwm.members.forEach { member ->
                        getOrPut(member.domainName.lowercase()) { mutableListOf() }.add(cwm.circle)
                    }
                }
        }
        val contactStates = if (circlesData.loading) {
            emptyMap()
        } else {
            connectedRegs.entries.mapNotNull { (odinId, reg) ->
                val domain = odinId.domainName.lowercase()
                contactStateOf(reg, personalCirclesByDomain[domain].orEmpty())?.let { domain to it }
            }.toMap()
        }
        fun domainsInState(state: ContactState) =
            contactStates.filterValues { it == state }.keys

        // contact-domain (lowercase) → saved contact entry, for resolving requests/introducers.
        val contactsByOdin = overriddenContacts
            .filter { !it.odinId.isNullOrBlank() }
            .associateBy { it.odinId!!.lowercase() }

        // ALL = saved contacts plus every other connection. A connection with no saved contact
        // entry would otherwise fall through both pills. Connections already in the book show via
        // their saved entry; the rest get a synthetic display-only entry, the same projection
        // the New tab uses.
        val unsavedConnectionDomains = connectedDomains - contactsByOdin.keys
        val selfEntry = header.ownerSession?.let { selfContact(it) }
        val all = buildList {
            addAll(overriddenContacts)
            addAll(unsavedConnectionDomains.map { syntheticContact(it) })
            // The contact store never holds the signed-in user, so a self-search finds nothing.
            // Surface "Name (you)" when the user searches for their own name/handle — only on an
            // active query, and only if self isn't already a saved contact (no duplicate).
            if (selfEntry != null && ui.query.isNotBlank() &&
                none { it.odinId?.lowercase() == selfEntry.odinId?.lowercase() }
            ) add(selfEntry)
        }
            .filter { it.matches(ui.query) }
            .sortedBy { it.sortKey }

        fun entriesInState(state: ContactState) =
            entriesForDomains(domainsInState(state), overriddenContacts)
                .filter { it.matches(ui.query) }
                .sortedBy { it.sortKey }

        val newContacts = entriesInState(ContactState.New)
        val circleContacts = entriesInState(ContactState.Circle)
        // Known = everyone reviewed, Chat and Circle alike. The two were separate pills while
        // New was one too; with New promoted to a tab, splitting the reviewed set again would
        // ask the user to care about a distinction the tab already made for them.
        val knownContacts = (entriesInState(ContactState.Chat) + circleContacts)
            .sortedBy { it.sortKey }

        // Pending connection requests, projected onto contact entries the same way New is:
        // reuse the saved contact when we have one, else a synthetic display-only entry for the
        // identity. The service's UI-model names are placeholders ("TODO …"), so we deliberately
        // resolve names through the contact book / domain, not those fields.
        fun pendingEntry(domain: String) = contactsByOdin[domain.lowercase()] ?: syntheticContact(domain)
        val incomingRequests = requestsData.incoming.map { req ->
            PendingRequestEntry(
                entry = pendingEntry(req.senderOdinId.domainName),
                direction = RequestDirection.INCOMING,
                receivedAtMs = req.receivedTimestampMilliseconds.milliseconds,
            )
        }
        val outgoingRequests = requestsData.outgoing.map { req ->
            PendingRequestEntry(
                entry = pendingEntry(req.recipientOdinId.domainName),
                direction = RequestDirection.OUTGOING,
                receivedAtMs = req.receivedTimestampMilliseconds.milliseconds,
            )
        }
        val requests = (incomingRequests + outgoingRequests)
            .filter { it.entry.matches(ui.query) }
            .sortedByDescending { it.receivedAtMs }

        ContactBookUiState(
            selectedTab = ui.tab,
            contacts = all,
            totalCount = all.size,
            connectedOdinIds = connectedDomains,
            newContacts = newContacts,
            knownContacts = knownContacts,
            circleContacts = circleContacts,
            contactStates = contactStates,
            statesLoading = circlesData.loading,
            requests = requests,
            incomingRequestCount = incomingRequests.size,
            reviewCircleGroups = CircleMembershipState(isLoaded = true, circles = circlesData.circles)
                .reviewCircleGroups(),
            circles = circlesData.circles.filter { it.matchesQuery(ui.query) },
            circlesLoading = circlesData.loading,
            circleMembers = circlesData.members,
            isLoading = !contactsData.loaded,
            searchQuery = ui.query,
            filter = ui.filter,
            overlay = ui.overlay,
            ownerSession = header.ownerSession,
            connectionStatus = header.connectionStatus,
            driveIsSyncing = header.driveIsSyncing,
            hasDriveError = header.hasDriveError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactBookUiState())

    /** Resolves a set of identity domains to entries, reusing the saved contact when one exists. */
    private fun entriesForDomains(
        domains: Set<String>,
        contacts: List<ContactBookEntry>,
    ): List<ContactBookEntry> {
        val byOdin = contacts.filter { !it.odinId.isNullOrBlank() }
            .associateBy { it.odinId!!.lowercase() }
        return domains.map { domain -> byOdin[domain] ?: syntheticContact(domain) }
    }

    /** A display-only "(you)" entry for the signed-in user, matched by their own name/handle. */
    private fun selfContact(session: OwnerSession): ContactBookEntry {
        val domain = session.odinId.domainName
        val uid = Md5.toGuidId(domain.lowercase())
        return ContactBookEntry(
            uniqueId = uid,
            fileId = uid,
            versionTag = null,
            odinId = domain,
            displayName = session.displayName?.ifBlank { null } ?: domain,
            source = ContactBookSource.CONNECTION,
            isSelf = true,
        )
    }

    /** A display-only entry for a connection/member that isn't in the contact book. */
    private fun syntheticContact(domain: String): ContactBookEntry {
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

    private val _events = MutableSharedFlow<ContactBookUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ContactBookUiEvent> = _events.asSharedFlow()

    fun onAction(action: ContactBookUiAction) {
        when (action) {
            is ContactBookUiAction.TabSelected -> {
                _selectedTab.value = action.tab
                if (action.tab == ContactTab.CIRCLES &&
                    _circles.value.isEmpty() && !_circlesLoading.value
                ) loadCircles()
            }
            is ContactBookUiAction.CircleClicked -> handleCircleClicked(action.circle)
            ContactBookUiAction.CircleMembersDismiss -> _circleMembers.value = null
            is ContactBookUiAction.CircleAddMemberClicked -> _events.tryEmit(
                ContactBookUiEvent.OpenCircleMemberAdd(action.circleId, action.circleName)
            )
            is ContactBookUiAction.CircleRemoveMemberClicked ->
                handleCircleRemoveMember(action.circleId, action.member)
            is ContactBookUiAction.SearchChanged -> _searchQuery.value = action.query
            is ContactBookUiAction.FilterChanged -> _filter.value = action.filter
            is ContactBookUiAction.ContactClicked -> {
                _circleMembers.value = null // close the circle sheet if a member was tapped
                _events.tryEmit(
                    ContactBookUiEvent.OpenDetail(
                        uniqueId = action.entry.uniqueId.toString(),
                        odinId = action.entry.odinId,
                    )
                )
            }
            // Add now leads with the Homebase ID in a full-screen flow; editing an existing
            // contact still uses the in-place sheet (see EditClicked).
            ContactBookUiAction.AddClicked -> _events.tryEmit(ContactBookUiEvent.OpenAddContact)
            is ContactBookUiAction.EditClicked -> _overlay.value = ContactBookOverlay.Edit(action.entry)
            is ContactBookUiAction.DeleteClicked -> handleDelete(action.entry)
            is ContactBookUiAction.SaveContact -> handleSave(
                action.draft, action.editing, action.additionalPhones, action.additionalEmails, action.photo,
            )
            is ContactBookUiAction.MessageClicked -> handleMessage(action.entry)
            is ContactBookUiAction.SyncClicked -> {
                val odinId = action.entry.odinId ?: return
                viewModelScope.launch { repo.sync(OdinId(odinId)) }
            }
            ContactBookUiAction.CloseOverlay -> _overlay.value = null
            is ContactBookUiAction.ReviewClicked -> openReview(action.entry)
            is ContactBookUiAction.ReviewSubmitted -> handleReview(action.entry, action.circleIds)

            ContactBookUiAction.OnboardingGetStarted ->
                viewModelScope.launch { preferences.setOnboardingComplete(true) }
            ContactBookUiAction.OnboardingSkip -> viewModelScope.launch {
                preferences.setOnboardingComplete(true)
                _events.tryEmit(ContactBookUiEvent.CloseOnboarding)
            }
        }
    }

    private fun openReview(entry: ContactBookEntry) {
        val domain = entry.odinId?.lowercase() ?: return
        val registration = connectionService.connections.value.map
            .entries.firstOrNull { it.key.domainName.lowercase() == domain }?.value
        _overlay.value = ContactBookOverlay.Review(
            entry = entry,
            introducedBy = registration?.introducerOdinId?.domainName,
            connectedAtMs = registration?.created,
            alreadyHeldCircleIds = _circles.value
                .filter { cwm -> cwm.members.any { it.domainName.lowercase() == domain } }
                .map { it.circle.id }
                .toSet(),
        )
    }

    /**
     * One call stamps the review and enrols the picked circles. Failure keeps the sheet open with
     * the error rather than dropping the user's selection — the call is idempotent, so retrying
     * the whole thing is safe.
     */
    private fun handleReview(entry: ContactBookEntry, circleIds: Set<String>) {
        val odinId = entry.odinId ?: return
        val current = _overlay.value as? ContactBookOverlay.Review ?: return
        _overlay.value = current.copy(isSubmitting = true, failed = false)
        viewModelScope.launch {
            try {
                connectionService.reviewConnection(
                    OdinId(odinId),
                    circleIds.map { Uuid.parseHex(it) },
                )
                _overlay.value = null
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Review of $odinId failed" }
                _overlay.value = current.copy(isSubmitting = false, failed = true)
            }
        }
    }

    private fun handleSave(
        draft: ContactDraft,
        editing: ContactBookEntry?,
        additionalPhones: List<String>,
        additionalEmails: List<String>,
        photo: PlatformFile?,
    ) {
        if (!draft.isSavable) return
        _overlay.value = null
        viewModelScope.launch {
            val result = if (editing != null) {
                saveContactEdit(
                    store = overrideStore,
                    repo = repo,
                    // Any identity contact (has odinId) is enriched on sync and would be overwritten;
                    // only a pure manual contact writes primaries to content.
                    useOverride = !editing.odinId.isNullOrBlank() && editing.versionTag != null,
                    editing = editing,
                    synced = editing,
                    draft = draft,
                    additionalPhones = additionalPhones,
                    additionalEmails = additionalEmails,
                    photo = photo,
                )
            } else {
                // Not saveContactDraft: the organization and the extra phone/email rows this sheet
                // collects live only in the override blob, and the contact alone drops them.
                saveNewContact(overrideStore, repo, draft, additionalPhones, additionalEmails, photo)
            }
            when (result) {
                is ContactSaveResult.Success -> {
                    // repo.save already applied the optimistic update.
                    if (result.photoFailed) {
                        _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.PhotoFailed))
                    }
                    if (result.clearedFieldsIgnored) {
                        _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.ClearUnsupported))
                    }
                    // The contact is written but its extras are not; silence reads as a full save.
                    if (result.additionsFailed) {
                        _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.AdditionsFailed))
                    }
                }
                ContactSaveResult.Forbidden ->
                    _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.SaveForbidden))
                ContactSaveResult.Failed ->
                    _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.SaveFailed))
            }
        }
    }

    // region Circles

    /**
     * Nudges a fresh network pull when the user looks at the Circles tab. [circles]/
     * [circleMembers] are otherwise kept live by the collector in [init], not by this call —
     * but [_circlesLoading] is reset here directly (not solely from that collector) because
     * ConnectionService.refresh() swallows its own failures and leaves circles unchanged: a
     * failed FIRST load would otherwise never produce a new emission, permanently stranding
     * _circlesLoading at true and disabling this exact retry gesture.
     */
    private fun loadCircles() {
        _circlesLoading.value = true
        viewModelScope.launch {
            try {
                connectionService.refresh()
            } finally {
                _circlesLoading.value = false
            }
        }
    }

    private fun handleCircleClicked(circle: CircleWithMembers) {
        // Members are bundled with the circle list — resolve them to contact entries
        // synchronously, no second network call. The init collector on connectionService.circles
        // keeps this in sync going forward (an add/remove from elsewhere no longer leaves this
        // sheet stale, #1096).
        val domains = circle.members.map { it.domainName }.toSet()
        val members = entriesForDomains(domains, entries.value).sortedBy { it.sortKey }
        // Pending deposits ride the same bundle as the members, so the sheet is complete on open.
        val pendingDomains = circle.pendingMembers
            .map { it.odinId.domainName.lowercase() }
            .filterNot { it in domains.map { d -> d.lowercase() } }
            .toSet()
        val pending = entriesForDomains(pendingDomains, entries.value).sortedBy { it.sortKey }
        // Ambient circles are enrolled with no owner present, so hand-managing a member means
        // nothing — the app re-enrols them. A review circle is the owner's own choice and stays
        // editable.
        val manageable = !circle.circle.isAmbientCircle()
        _circleMembers.value = CircleMembersUi(
            circleId = circle.circle.id,
            circleName = circle.circle.name,
            circleEmoji = circle.circle.emoji,
            manageable = manageable,
            members = members,
            pendingMembers = pending,
            isLoading = false,
            drives = resolveCircleDrives(circle.circle),
        )
    }

    /**
     * Pull fresh circle data on screen resume, e.g. returning from the add picker.
     *
     * Still worth doing: pending membership now rides the circle bundle, so the collector picks
     * up any change on its own — but only once something asks the server. This is that ask.
     */
    fun refreshOpenCircle() {
        _circleMembers.value ?: return
        viewModelScope.launch { connectionService.refresh() }
    }

    private fun handleCircleRemoveMember(circleIdRaw: String, member: ContactBookEntry) {
        val odinId = member.odinId?.let(::OdinId) ?: return
        if (member.uniqueId in (_circleMembers.value?.removingMemberIds ?: emptySet())) return
        _circleMembers.update {
            it?.copy(removingMemberIds = it.removingMemberIds + member.uniqueId)
        }
        viewModelScope.launch {
            try {
                connectionService.removeFromCircle(Uuid.parseHex(circleIdRaw), odinId)
                _circleMembers.update {
                    if (it?.circleId != circleIdRaw) it
                    else it.copy(
                        members = it.members.filterNot { m -> m.uniqueId == member.uniqueId },
                        pendingMembers = it.pendingMembers.filterNot { m -> m.uniqueId == member.uniqueId },
                        removingMemberIds = it.removingMemberIds - member.uniqueId,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, "ContactBookViewModel") { "removeFromCircle failed for $odinId" }
                _circleMembers.update {
                    if (it?.circleId != circleIdRaw) it
                    else it.copy(removingMemberIds = it.removingMemberIds - member.uniqueId)
                }
                _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.CircleActionFailed))
            }
        }
    }

    // endregion

    /**
     * Opens (creating if needed) the 1:1 conversation with this contact, then
     * emits the conversationId so the host can land the chat list on it — the
     * same path the "New conversation" contact picker uses. Closes the detail
     * overlay first so the chat is what the user sees.
     */
    private fun handleMessage(entry: ContactBookEntry) {
        val odinId = entry.odinId?.trim()?.ifBlank { null } ?: return
        _overlay.value = null
        viewModelScope.launch {
            val conversationId = try {
                conversationService.createConversation(
                    recipients = listOf(OdinId(odinId)),
                    title = "",
                    payloadBundle = null,
                ).conversationId
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.MessageFailed))
                return@launch
            }
            _events.tryEmit(ContactBookUiEvent.OpenConversation(conversationId))
        }
    }

    private fun handleDelete(entry: ContactBookEntry) {
        _overlay.value = null
        viewModelScope.launch {
            // repo.delete does the optimistic remove and restores on a generic failure.
            if (!repo.delete(entry.uniqueId)) {
                _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.DeleteFailed))
            }
        }
    }

}

/** Name/description match for the search box, which now spans both tabs. */
private fun CircleWithMembers.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return circle.name.lowercase().contains(q) ||
        circle.description?.lowercase()?.contains(q) == true
}

/**
 * Sort bucket for the Circles tab: the auto-connected ("New") circle first, the user's own
 * circles (including Emergency Location Access — a user circle, not an app default) in the
 * middle, and every other app default circle last.
 */
private fun RedactedCircleDefinition.circleSortRank(): Int = when {
    id.equals(AUTO_CONNECTIONS_CIRCLE_ID, ignoreCase = true) -> 0
    isAppDefaultCircle() -> 2
    else -> 1
}
