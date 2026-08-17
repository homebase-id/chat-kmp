@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.Md5
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ConnectionState
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.auth.toConnectionStatus
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
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

    private val _selectedTab = MutableStateFlow(ContactTab.CONTACTS)
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
                    .sortedWith(compareBy({ it.circle.id.circleSortRank() }, { it.circle.name.lowercase() }))
                _circles.value = circles
                _circlesLoading.value = !circleState.isLoaded

                val open = _circleMembers.value ?: return@collect
                val match = circles.firstOrNull { it.circle.id == open.circleId } ?: return@collect
                val domains = match.members.map { it.domainName }.toSet()
                _circleMembers.update {
                    it?.copy(
                        members = entriesForDomains(domains, entries.value).sortedBy { m -> m.sortKey },
                        drives = resolveCircleDrives(match.circle),
                    )
                }
                // Best-effort: a fresh emission here means someone's real membership actually
                // changed, so re-derive the pending badge too. This does NOT catch a pending-only
                // add — the real member list is unchanged, so StateFlow conflates the assignment
                // and this block never runs for that case. refreshOpenCircle() is the reliable
                // path (#1096); this just keeps things fresher between resumes when it does fire.
                if (open.manageable) checkCirclePending(match)
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
        // Apply user overrides up front so every downstream list (All, Unvetted, Requests,
        // introducer names) shows the user's renamed/edited values, not the synced ones.
        val overriddenContacts = contactsData.contacts
            .map { it.withOverride(contactsData.overrides[it.uniqueId]) }
        val connectedRegs = contactsData.connections.map
            .filterValues { it.status == ConnectionStatus.Connected }
        val connectedDomains = connectedRegs.keys.map { it.domainName.lowercase() }.toSet()

        // Unvetted = connected but not confirmed. Confirmed is the server-computed `vetted` flag
        // (connected AND a member of the Confirmed Connections system circle — see issue #919);
        // it rides with the connection data itself, so this needs no circle load/fallback. This
        // is a full complement over connected identities, not just auto-connected/introduced —
        // a plain direct connection that hasn't been explicitly confirmed is unvetted too.
        val confirmedDomains = connectedRegs.filterValues { it.vetted }
            .keys.map { it.domainName.lowercase() }
            .toSet()
        val unvettedDomains = connectedDomains - confirmedDomains

        // contact-domain (lowercase) → saved contact entry, for resolving requests/introducers.
        val contactsByOdin = overriddenContacts
            .filter { !it.odinId.isNullOrBlank() }
            .associateBy { it.odinId!!.lowercase() }

        // ALL = saved contacts plus every other connection. A connection with no saved contact
        // entry would otherwise fall through both pills. Connections already in the book show via
        // their saved entry; the rest get a synthetic display-only entry, the same projection
        // Unvetted uses.
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

        val unvetted = entriesForDomains(unvettedDomains, overriddenContacts)
            .filter { it.matches(ui.query) }
            .sortedBy { it.sortKey }

        val vetted = entriesForDomains(confirmedDomains, overriddenContacts)
            .filter { it.matches(ui.query) }
            .sortedBy { it.sortKey }

        // Pending connection requests, projected onto contact entries the same way Unvetted is:
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
            unvetted = unvetted,
            vetted = vetted,
            requests = requests,
            incomingRequestCount = incomingRequests.size,
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

            ContactBookUiAction.OnboardingGetStarted ->
                viewModelScope.launch { preferences.setOnboardingComplete(true) }
            ContactBookUiAction.OnboardingSkip -> viewModelScope.launch {
                preferences.setOnboardingComplete(true)
                _events.tryEmit(ContactBookUiEvent.CloseOnboarding)
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

    private var circlePendingJob: Job? = null

    private fun handleCircleClicked(circle: CircleWithMembers) {
        // Members are bundled with the circle list — resolve them to contact entries
        // synchronously, no second network call. The init collector on connectionService.circles
        // keeps this in sync going forward (an add/remove from elsewhere no longer leaves this
        // sheet stale, #1096).
        val domains = circle.members.map { it.domainName }.toSet()
        val members = entriesForDomains(domains, entries.value).sortedBy { it.sortKey }
        // System circles (Confirmed/Auto-connected) are computed by the vetting flow, not
        // manually curated — hide the add/remove affordances for those, editable for the rest.
        val manageable = !isSystemCircleId(circle.circle.id)
        _circleMembers.value = CircleMembersUi(
            circleId = circle.circle.id,
            circleName = circle.circle.name,
            manageable = manageable,
            members = members,
            isLoading = false,
            pendingChecking = manageable,
            drives = resolveCircleDrives(circle.circle),
        )
        if (manageable) checkCirclePending(circle)
    }

    /**
     * Re-derive the open circle sheet's pending badge and real-member list on screen resume
     * (e.g. returning from the add picker). The `init` collector on connectionService.circles
     * re-checks pending automatically whenever that flow actually emits, but a pending-only add
     * doesn't change any circle's real member list — so the resulting CircleMembershipState is
     * `equals()` to the prior one, and MutableStateFlow silently conflates the assignment,
     * never notifying collectors at all (#1096). This resume-triggered call doesn't depend on
     * the flow re-emitting; it always re-checks.
     */
    fun refreshOpenCircle() {
        val open = _circleMembers.value ?: return
        viewModelScope.launch { connectionService.refresh() }
        val match = _circles.value.firstOrNull { it.circle.id == open.circleId } ?: return
        if (open.manageable) checkCirclePending(match)
    }

    /** Live pending-status re-check for whichever circle's sheet is currently open — called on
     *  first open, again whenever connectionService.circles happens to emit a structurally
     *  different value, and explicitly on screen resume via [refreshOpenCircle] (#1096). */
    private fun checkCirclePending(circle: CircleWithMembers) {
        circlePendingJob?.cancel()
        circlePendingJob = viewModelScope.launch {
            val circleId = try {
                Uuid.parseHex(circle.circle.id)
            } catch (e: Exception) {
                Logger.w(e, "ContactBookViewModel") { "bad circle id ${circle.circle.id}" }
                _circleMembers.update { it?.copy(pendingChecking = false) }
                return@launch
            }
            val pending = try {
                connectionService.findPendingMembers(circleId)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, "ContactBookViewModel") { "findPendingMembers failed for ${circle.circle.id}" }
                emptyList()
            }
            val pendingEntries = entriesForDomains(
                pending.map { it.domainName }.toSet(),
                entries.value,
            ).sortedBy { it.sortKey }
            // Only apply if the sheet is still open on the same circle (the user may have
            // dismissed or switched to another circle while this was in flight). Re-excludes
            // against the CURRENT members at update time, not the snapshot this fan-out started
            // from — cancel() on the superseded job is cooperative, so a stale fan-out that's
            // already past its last suspension point can still land its update after a fresher
            // one already promoted someone from pending to real, putting them in both lists at
            // once and crashing CircleMembersSheet's keyed LazyColumn (real crash, not cosmetic:
            // #1096 in the Location dashboard's plain Column was the same race, just invisible).
            _circleMembers.update {
                if (it?.circleId == circle.circle.id) {
                    it.copy(
                        pendingMembers = pendingEntries.filterNot { p -> it.members.any { m -> m.uniqueId == p.uniqueId } },
                        pendingChecking = false,
                    )
                } else it
            }
        }
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

/** Matches ContactDetailViewModel's equivalent check — case-insensitive since nothing
 *  guarantees the server always returns these ids in the same casing. */
private fun isSystemCircleId(id: String): Boolean =
    id.equals(AUTO_CONNECTIONS_CIRCLE_ID, ignoreCase = true) ||
        id.equals(CONFIRMED_CONNECTIONS_CIRCLE_ID, ignoreCase = true)

/**
 * Sort bucket for the Circles tab: the auto-connected ("Unvetted") system circle first, the
 * user's own circles (including Emergency Location Access — a user circle, not a system one)
 * in the middle, and the confirmed-connected system circle last.
 */
private fun String.circleSortRank(): Int = when {
    equals(AUTO_CONNECTIONS_CIRCLE_ID, ignoreCase = true) -> 0
    equals(CONFIRMED_CONNECTIONS_CIRCLE_ID, ignoreCase = true) -> 2
    else -> 1
}
