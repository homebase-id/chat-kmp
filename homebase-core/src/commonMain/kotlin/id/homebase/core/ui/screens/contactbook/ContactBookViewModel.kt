@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.ConversationService
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
import id.homebase.core.util.resolveContentType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
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
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _filter = MutableStateFlow(ContactFilter.ALL)
    private val _overlay = MutableStateFlow<ContactBookOverlay?>(null)
    private val _importState = MutableStateFlow<ImportUiState?>(null)

    val uiState: StateFlow<ContactBookUiState> = combine(
        combine(stream.contacts, stream.isLoaded) { contacts, loaded -> contacts to loaded },
        _searchQuery,
        _filter,
        _overlay,
        _importState,
    ) { (contacts, loaded), query, filter, overlay, importState ->
        val filtered = contacts
            .filter { entry ->
                when (filter) {
                    ContactFilter.ALL -> true
                    ContactFilter.HOMEBASE -> entry.hasOdinId
                    ContactFilter.IMPORTED -> !entry.hasOdinId
                }
            }
            .filter { it.matches(query) }
        ContactBookUiState(
            contacts = filtered,
            totalCount = contacts.size,
            isLoading = !loaded,
            searchQuery = query,
            filter = filter,
            overlay = refreshOverlay(overlay, contacts),
            importState = importState,
            importSupported = isDeviceContactsSupported(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactBookUiState())

    private val _events = MutableSharedFlow<ContactBookUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ContactBookUiEvent> = _events.asSharedFlow()

    // Keep an open Detail overlay in sync with fresh stream data after an edit.
    private fun refreshOverlay(
        overlay: ContactBookOverlay?,
        contacts: List<ContactBookEntry>,
    ): ContactBookOverlay? = when (overlay) {
        is ContactBookOverlay.Detail ->
            contacts.find { it.uniqueId == overlay.entry.uniqueId }
                ?.let { ContactBookOverlay.Detail(it) } ?: overlay
        else -> overlay
    }

    fun onAction(action: ContactBookUiAction) {
        preferences.recordUserAction()
        when (action) {
            is ContactBookUiAction.SearchChanged -> _searchQuery.value = action.query
            is ContactBookUiAction.FilterChanged -> _filter.value = action.filter
            is ContactBookUiAction.ContactClicked ->
                _overlay.value = ContactBookOverlay.Detail(action.entry)
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
        val normalizedPhone = draft.phone.ifBlank { null }
            ?.let { ContactFieldValidation.normalizePhone(it) }
        // An odinId may be set on a new contact, or kept from the edited one.
        val odinId = draft.odinId.trim().ifBlank { null } ?: editing?.odinId?.ifBlank { null }
        val content = ContactContent(
            odinId = odinId,
            name = ContactName(
                displayName = draft.displayName.ifBlank { null },
                givenName = draft.givenName.ifBlank { null },
                surname = draft.surname.ifBlank { null },
            ),
            source = editing?.source ?: ContactBookSource.MANUAL,
            location = null,
            phone = normalizedPhone?.let { ContactPhone(it) },
            email = draft.email.ifBlank { null }?.let { ContactEmail(it) },
        ).withLocationAndBirthday(draft)

        _overlay.value = null
        viewModelScope.launch {
            val response = service.save(
                content = content,
                knownUniqueId = editing?.uniqueId,
                knownVersionTag = editing?.versionTag,
            )
            if (response == null) {
                _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.SaveFailed))
                return@launch
            }

            // Upload the avatar after the contact exists (version-gated endpoint).
            if (photo != null) {
                val ok = uploadPhoto(response.uniqueId, response.versionTag, photo)
                if (!ok) _events.tryEmit(ContactBookUiEvent.Error(ContactBookError.PhotoFailed))
            }

            stream.insertOrUpdateOptimistic(
                (editing ?: ContactBookEntry(
                    uniqueId = response.uniqueId,
                    fileId = response.uniqueId,
                    versionTag = response.versionTag,
                    displayName = draft.displayName.ifBlank { draft.phone.ifBlank { draft.email } },
                )).copy(
                    uniqueId = response.uniqueId,
                    versionTag = response.versionTag,
                    odinId = odinId,
                    displayName = draft.displayName.ifBlank { normalizedPhone ?: draft.email }
                        .ifBlank { "?" },
                    givenName = draft.givenName.ifBlank { null },
                    surname = draft.surname.ifBlank { null },
                    phone = normalizedPhone,
                    email = draft.email.ifBlank { null },
                    city = draft.city.ifBlank { null },
                    country = draft.country.ifBlank { null },
                    birthday = draft.birthday.ifBlank { null },
                    source = content.source,
                ),
            )
        }
    }

    private suspend fun uploadPhoto(uniqueId: Uuid, versionTag: Uuid, photo: PlatformFile): Boolean {
        val bytes = try {
            photo.readBytes()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (bytes.isEmpty()) return false
        val contentType = resolveContentType(photo.name, photo.mimeType()?.toString())
        return service.setPhoto(
            uniqueId = uniqueId,
            contactDriveId = contactTargetDrive.alias,
            bytes = bytes,
            contentType = contentType,
            versionTag = versionTag,
        )
    }

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

private fun ContactContent.withLocationAndBirthday(draft: ContactDraft): ContactContent {
    val hasLocation = draft.city.isNotBlank() || draft.country.isNotBlank()
    return copy(
        location = if (hasLocation) id.homebase.api.client.contacts.ContactLocation(
            city = draft.city.ifBlank { null },
            country = draft.country.ifBlank { null },
        ) else null,
        birthday = draft.birthday.ifBlank { null }
            ?.let { id.homebase.api.client.contacts.ContactBirthday(date = it) },
    )
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
