package id.homebase.chat.createconversation

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.auth.initials
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.sync.DriveSyncManager
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.convo.matchesSelfQuery
import id.homebase.chat.services.convo.selfDisplayLabel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateConversationViewModel(
    private val contactService: ContactService,
    private val conversationWriterService: ConversationService,
    private val connectionService: ConnectionService,
    private val driveSyncManager: DriveSyncManager,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val conversationStream: ConversationStream,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CreateConversationUiState()
    )
    val uiState: StateFlow<CreateConversationUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    init {
        viewModelScope.launch {
            contactService.start()
            combine(
                contactService.contacts,
                snapshotFlow { searchTextState.text.toString() },
                ownerSessionRepository.user,
                conversationStream.conversations,
            ) { contacts, query, self, conversations ->
                val groups = conversations.items.filter { it.isGroupConversation }
                filterAndGroup(contacts, groups, query, self)
            }
                .catch {
                    sendEvent(
                        CreateConversationUiEvent.ShowErrorMessage(
                            it.message ?: "Unknown error"
                        )
                    )
                }
                .collectLatest { contactGroups ->
                    _uiState.update { it.copy(displayItems = contactGroups.toPersistentList()) }
                }
        }
    }

    fun onUiAction(action: CreateConversationUiAction) {
        when (action) {
            is CreateConversationUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = CreateConversationUiEvent.Back) }
            is CreateConversationUiAction.RefreshClicked -> onRefreshClicked()
            is CreateConversationUiAction.CreateNewGroup -> _uiState.update { it.copy(uiEvent = CreateConversationUiEvent.ShowCreateGroupScreen) }
            is CreateConversationUiAction.CreateSelfConversation -> {
                _uiState.update {
                    it.copy(
                        uiEvent = CreateConversationUiEvent.LoadConversation(
                            ChatProtocol.ConversationWithYourselfId
                        )
                    )
                }
            }
            is CreateConversationUiAction.ExistingConversationClicked -> {
                _uiState.update {
                    it.copy(uiEvent = CreateConversationUiEvent.LoadConversation(action.conversationId))
                }
            }
            is CreateConversationUiAction.ContactClicked -> {
                viewModelScope.launch {
                    try {
                        val conversationId = conversationWriterService.createConversation(
                            recipients = listOf(action.contact.odinId),
                            title = "",
                            payloadBundle = null,
                        ).conversationId
                        _uiState.update {
                            it.copy(
                                uiEvent = CreateConversationUiEvent.LoadConversation(
                                    conversationId
                                )
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(CreateConversationUiEvent.ShowErrorMessage("Failed to create conversation: ${e.message}"))
                    }
                }
            }
        }
    }

    private fun onRefreshClicked() {
        if (uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                // Re-mount the contact drive in case a prior 403 unmounted it this
                // session, then pull it from the server. The contact list updates
                // itself when the sync completes (ContactService observes the drive
                // event). Connections are fetched directly from the server.
                driveSyncManager.ensureMandatoryMounted()
                driveSyncManager.syncDrive(SystemDriveConstants.contactDrive.alias)
                connectionService.refresh()
            } catch (e: Exception) {
                sendEvent(CreateConversationUiEvent.ShowErrorMessage(e.message ?: "Refresh failed"))
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: CreateConversationUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

}

/**
 * Builds the new-conversation list for [query]: the standing actions when idle, or — when the query
 * matches the signed-in user — a self "Name (you)" row backed by [CreateConversationListItem.SelfRef],
 * followed by the matching contacts. Self is excluded from the contact rows (Note to Self is the
 * canonical self path). Pure function of its inputs so the self-search seam is unit-testable.
 */
internal fun filterAndGroup(
    contacts: List<ContactUiModel>,
    groupConversations: List<ConversationUiModel>,
    query: String,
    self: OwnerSession?,
): List<CreateConversationListItem> {
    val result = mutableListOf<CreateConversationListItem>()

    // Only display new group and note to self options if not showing search results
    if (query.isEmpty()) {
        result.add(CreateConversationListItem.NoteToSelf())
        result.add(CreateConversationListItem.NewContact)
        result.add(CreateConversationListItem.NewGroup)
    } else if (self != null && self.matchesQuery(query)) {
        // Searching your own name/handle would otherwise be a dead end (self isn't a
        // contact) — surface Note to Self as a contact row (your avatar + "Name (you)")
        // so the result reads as you, not a generic note.
        result.add(
            CreateConversationListItem.NoteToSelf(
                CreateConversationListItem.SelfRef(
                    odinId = self.odinId,
                    name = self.displayLabel(),
                    initials = self.initials(),
                )
            )
        )
    }

    val filtered = if (query.isEmpty()) {
        contacts
    } else {
        contacts.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.odinId.toString().contains(query, ignoreCase = true)
        }
    }
        .distinctBy { it.odinId }
        // Never list the signed-in user as a normal contact — self routes to Note to Self above.
        .filter { it.odinId != self?.odinId }
    val groups = filtered.groupBy {
        it.name.firstOrNull()?.uppercase() ?: "#"
    }.map { (initial, groupContacts) ->
        ContactGroup(initial = initial, contacts = groupContacts)
    }.sortedBy { it.initial }
    result.add(CreateConversationListItem.Contacts(groups))

    // Existing group conversations, matchable by group name or by a member's
    // contact name (a group's title often doesn't contain any member's name).
    // Listed after contacts so the picker leads with individuals.
    val contactNameByOdinId = contacts.associate { it.odinId to it.name }
    val matchedGroups = if (query.isEmpty()) {
        groupConversations
    } else {
        groupConversations.filter { group ->
            group.getDisplayName().contains(query, ignoreCase = true) ||
                group.participants.any { p ->
                    contactNameByOdinId[p]?.contains(query, ignoreCase = true) == true
                }
        }
    }
    val groupRows = matchedGroups
        .distinctBy { it.id }
        .sortedBy { it.getDisplayName().lowercase() }
        .map {
            CreateConversationListItem.GroupRow(
                id = it.id,
                avatarModel = it.avatarModel,
                name = it.getDisplayName(),
            )
        }
    if (groupRows.isNotEmpty()) {
        result.add(CreateConversationListItem.Groups(groupRows))
    }
    return result
}

/** True when [query] hits the signed-in user's own display name or handle (case-insensitive). */
internal fun OwnerSession?.matchesQuery(query: String): Boolean = matchesSelfQuery(query)

/** Display label for the signed-in user: their name, or their handle when the name isn't loaded. */
internal fun OwnerSession.displayLabel(): String = selfDisplayLabel()
