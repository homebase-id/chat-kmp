@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.ConnectionRequestOrigin
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.Md5
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.contact.CircleMembershipState
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ConnectionState
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.api.client.contacts.ContactEmail
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
import id.homebase.core.config.contactTargetDrive
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.core.contactbook.DeviceContact
import id.homebase.core.contactbook.isDeviceContactsSupported
import id.homebase.core.contactbook.readDeviceContacts
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import io.github.vinceglb.filekit.PlatformFile
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ContactBookViewModel(
    private val stream: ContactBookStream,
    private val service: ContactBookService,
    private val preferences: ContactBookPreferences,
    private val conversationService: ConversationService,
    private val connectionService: ConnectionService,
    private val connectionNetworkProvider: ConnectionNetworkProvider,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(ContactTab.CONTACTS)
    private val _searchQuery = MutableStateFlow("")
    private val _filter = MutableStateFlow(ContactFilter.ALL)
    private val _overlay = MutableStateFlow<ContactBookOverlay?>(null)
    private val _importState = MutableStateFlow<ImportUiState?>(null)
    private val _circles = MutableStateFlow<List<CircleWithMembers>>(emptyList())
    private val _circlesLoading = MutableStateFlow(false)
    private val _circleMembers = MutableStateFlow<CircleMembersUi?>(null)

    private data class ContactsBundle(
        val contacts: List<ContactBookEntry>,
        val loaded: Boolean,
        val connections: ConnectionState,
        val circles: CircleMembershipState,
    )

    private data class UiBits(
        val query: String,
        val filter: ContactFilter,
        val tab: ContactTab,
        val overlay: ContactBookOverlay?,
        val importState: ImportUiState?,
    )

    private data class CirclesBundle(
        val circles: List<CircleWithMembers>,
        val loading: Boolean,
        val members: CircleMembersUi?,
    )

    val uiState: StateFlow<ContactBookUiState> = combine(
        combine(
            stream.contacts,
            stream.isLoaded,
            connectionService.connections,
            connectionService.circles,
        ) { c, l, conn, circ ->
            ContactsBundle(c, l, conn, circ)
        },
        combine(_searchQuery, _filter, _selectedTab, _overlay, _importState) { q, f, tab, o, i ->
            UiBits(q, f, tab, o, i)
        },
        combine(_circles, _circlesLoading, _circleMembers) { c, l, m -> CirclesBundle(c, l, m) },
    ) { contactsData, ui, circlesData ->
        val connectedRegs = contactsData.connections.map
            .filterValues { it.status == ConnectionStatus.Connected }
        val connectedDomains = connectedRegs.keys.map { it.domainName.lowercase() }.toSet()

        // Introduced and Confirmed are orthogonal, so a contact who was introduced and then
        // confirmed shows under both pills:
        //  - Introduced = provenance: the connection originated from an introduction. This is
        //    permanent (it stays true after confirmation), which is what "we connected via an
        //    intro" means, and it rides in the connection data so it needs no circle load.
        //  - Confirmed = membership in the Confirmed Connections system circle (the explicit
        //    confirm action). Until the circle list loads we fall back to "connected and not
        //    introduced" so the pill isn't empty on a cold start.
        val introducedDomains = connectedRegs
            .filterValues { it.connectionRequestOrigin == ConnectionRequestOrigin.Introduction }
            .keys.map { it.domainName.lowercase() }
            .toSet()
        val confirmedDomains = if (contactsData.circles.isLoaded) {
            connectedDomains intersect contactsData.circles.membersOf(CONFIRMED_CONNECTIONS_CIRCLE_ID)
        } else {
            connectedDomains - introducedDomains
        }

        // contact-domain (lowercase) → introducer display name, resolved to a saved
        // contact's name when we have one, else the raw introducer domain.
        val contactsByOdin = contactsData.contacts
            .filter { !it.odinId.isNullOrBlank() }
            .associateBy { it.odinId!!.lowercase() }
        val introducedByDomain = connectedRegs
            .filterValues {
                it.connectionRequestOrigin == ConnectionRequestOrigin.Introduction &&
                    it.introducerOdinId != null
            }
            .entries.associate { (odinId, reg) ->
                val introducer = reg.introducerOdinId!!.domainName
                odinId.domainName.lowercase() to
                    (contactsByOdin[introducer.lowercase()]?.displayName ?: introducer)
            }

        val filtered = contactsData.contacts.filter { it.matches(ui.query) }

        val introduced = entriesForDomains(introducedDomains, contactsData.contacts)
            .filter { it.matches(ui.query) }
            .sortedBy { it.sortKey }
        val confirmed = entriesForDomains(confirmedDomains, contactsData.contacts)
            .filter { it.matches(ui.query) }
            .sortedBy { it.sortKey }

        ContactBookUiState(
            selectedTab = ui.tab,
            contacts = filtered,
            totalCount = contactsData.contacts.size,
            connectedOdinIds = connectedDomains,
            introduced = introduced,
            confirmed = confirmed,
            introducedByDomain = introducedByDomain,
            circles = circlesData.circles.filter { it.matchesQuery(ui.query) },
            circlesLoading = circlesData.loading,
            circleMembers = circlesData.members,
            isLoading = !contactsData.loaded,
            searchQuery = ui.query,
            filter = ui.filter,
            overlay = ui.overlay,
            importState = ui.importState,
            importSupported = isDeviceContactsSupported(),
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
            ContactBookUiAction.AddClicked -> _overlay.value = ContactBookOverlay.Edit(null)
            is ContactBookUiAction.EditClicked -> _overlay.value = ContactBookOverlay.Edit(action.entry)
            is ContactBookUiAction.DeleteClicked -> handleDelete(action.entry)
            is ContactBookUiAction.SaveContact -> handleSave(action.draft, action.editing, action.photo)
            is ContactBookUiAction.MessageClicked -> handleMessage(action.entry)
            is ContactBookUiAction.SyncClicked -> {
                val odinId = action.entry.odinId ?: return
                viewModelScope.launch { service.syncFromIdentity(odinId) }
            }
            ContactBookUiAction.CloseOverlay -> _overlay.value = null

            ContactBookUiAction.ImportClicked -> {
                _importState.value = ImportUiState.RequestingPermission
                _events.tryEmit(ContactBookUiEvent.RequestContactsPermission)
            }
            is ContactBookUiAction.ImportPermissionResult -> handlePermissionResult(action.granted)
            is ContactBookUiAction.ImportToggle -> toggleImport(action.index)
            is ContactBookUiAction.ImportSelectAll -> selectAllImport(action.selected)
            ContactBookUiAction.ImportConfirm -> handleImportConfirm()
            ContactBookUiAction.ImportDismiss -> _importState.value = null

            ContactBookUiAction.OnboardingGetStarted ->
                viewModelScope.launch { preferences.setOnboardingComplete(true) }
            ContactBookUiAction.OnboardingSkip -> viewModelScope.launch {
                preferences.setOnboardingComplete(true)
                _events.tryEmit(ContactBookUiEvent.CloseOnboarding)
            }
        }
    }

    private fun handleSave(draft: ContactDraft, editing: ContactBookEntry?, photo: PlatformFile?) {
        if (!draft.isSavable) return
        _overlay.value = null
        viewModelScope.launch {
            when (val result = saveContactDraft(service, draft, editing, photo, contactTargetDrive.alias)) {
                is ContactSaveResult.Success -> {
                    stream.insertOrUpdateOptimistic(result.entry)
                    if (result.photoFailed) {
                        _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.PhotoFailed))
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

    private fun loadCircles() {
        _circlesLoading.value = true
        viewModelScope.launch {
            val circles = try {
                connectionNetworkProvider.getCirclesWithMembers().filterNot { it.circle.disabled }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, "ContactBookViewModel") { "getCirclesWithMembers failed" }
                emptyList()
            }
            _circles.value = circles.sortedBy { it.circle.name.lowercase() }
            _circlesLoading.value = false
        }
    }

    private fun handleCircleClicked(circle: CircleWithMembers) {
        // Members are bundled with the circle list — resolve them to contact entries
        // synchronously, no second network call.
        val domains = circle.members.map { it.domainName }.toSet()
        val members = entriesForDomains(domains, stream.contacts.value).sortedBy { it.sortKey }
        _circleMembers.value = CircleMembersUi(
            circleName = circle.circle.name,
            members = members,
            isLoading = false,
        )
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
        stream.removeOptimistic(entry.uniqueId)
        viewModelScope.launch {
            if (!service.delete(entry.uniqueId)) {
                _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.DeleteFailed))
                stream.loadAll() // re-sync truth after a failed delete
            }
        }
    }

    // region Import

    private fun handlePermissionResult(granted: Boolean) {
        if (!granted) {
            _importState.value = ImportUiState.Failed(ContactBookError.PermissionDenied)
            return
        }
        _importState.value = ImportUiState.Reading
        viewModelScope.launch {
            val devices = try {
                readDeviceContacts().filter { it.hasContactPoint }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                _importState.value = ImportUiState.Failed(ContactBookError.ImportFailed)
                return@launch
            }
            _importState.value = ImportUiState.Review(
                contacts = devices,
                selected = devices.indices.toSet(),
            )
        }
    }

    private fun toggleImport(index: Int) {
        _importState.update { state ->
            if (state !is ImportUiState.Review) return@update state
            val selected = if (index in state.selected) state.selected - index else state.selected + index
            state.copy(selected = selected)
        }
    }

    private fun selectAllImport(select: Boolean) {
        _importState.update { state ->
            if (state !is ImportUiState.Review) return@update state
            state.copy(selected = if (select) state.contacts.indices.toSet() else emptySet())
        }
    }

    private fun handleImportConfirm() {
        val review = _importState.value as? ImportUiState.Review ?: return
        val chosen = review.selected.sorted().mapNotNull { review.contacts.getOrNull(it) }
        if (chosen.isEmpty()) {
            _importState.value = null
            return
        }
        viewModelScope.launch {
            var imported = 0
            var skipped = 0
            chosen.forEachIndexed { index, device ->
                _importState.value = ImportUiState.Saving(done = index, total = chosen.size)
                val response = service.save(device.toContactContent())
                if (response != null) {
                    imported++
                    stream.insertOrUpdateOptimistic(device.toEntry(response.uniqueId, response.versionTag))
                } else {
                    skipped++
                }
            }
            _importState.value = ImportUiState.Complete(imported = imported, skipped = skipped)
        }
    }

    // endregion
}

/** Name/description match for the search box, which now spans both tabs. */
private fun CircleWithMembers.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return circle.name.lowercase().contains(q) ||
        circle.description?.lowercase()?.contains(q) == true
}

private fun DeviceContact.toContactContent(): ContactContent = ContactContent(
    name = ContactName(
        displayName = displayName.ifBlank { null },
        givenName = givenName?.ifBlank { null },
        surname = surname?.ifBlank { null },
    ),
    source = ContactBookSource.IMPORTED,
    phone = phone?.ifBlank { null }?.let { ContactPhone(it) },
    email = email?.ifBlank { null }?.let { ContactEmail(it) },
)

@OptIn(ExperimentalUuidApi::class)
private fun DeviceContact.toEntry(uniqueId: Uuid, versionTag: Uuid): ContactBookEntry =
    ContactBookEntry(
        uniqueId = uniqueId,
        fileId = uniqueId,
        versionTag = versionTag,
        displayName = displayName.ifBlank { phone ?: email ?: "?" },
        givenName = givenName,
        surname = surname,
        phone = phone,
        email = email,
        source = ContactBookSource.IMPORTED,
    )
