package id.homebase.core.ui.screens.location

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.selectmembers.filterAndGroup
import id.homebase.core.ui.screens.contactbook.toCircleAddFailureReason
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.EMERGENCY_LOCATION_CIRCLE_ID
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "EmergencyContactPickerViewModel"

/**
 * Grant-eligibility candidate list for the emergency-location-access circle: connected AND
 * vetted contacts (auto-connected/unconfirmed identities 400 on add — CannotGrantAutoConnected
 * MoreCircles — so they're filtered out here rather than offered and rejected), excluding
 * anyone already a real member. Real members come from the already-loaded bulk circle-members
 * read ([ConnectionService.circles]) — cheap and authoritative; a duplicate add attempt on a
 * still-pending contact simply hits the server's real IdentityAlreadyMemberOfCircle response
 * (handled in [addSelected]) rather than being pre-filtered from a guess.
 */
class EmergencyContactPickerViewModel(
    private val contactService: ContactService,
    private val connectionService: ConnectionService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmergencyContactPickerUiState())
    val uiState: StateFlow<EmergencyContactPickerUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    private val _events = MutableSharedFlow<EmergencyContactPickerUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EmergencyContactPickerUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            contactService.start()
            combine(
                contactService.contacts,
                connectionService.circles,
                snapshotFlow { searchTextState.text.toString() },
            ) { contacts, circleState, query ->
                val members = circleState.membersOf(EMERGENCY_LOCATION_CIRCLE_ID)
                contacts
                    .filter { it.connection?.status == ConnectionStatus.Connected && it.connection?.vetted == true }
                    .filterNot { members.contains(it.odinId.domainName.lowercase()) }
                    .filterAndGroup(query)
            }.collect { groups ->
                _uiState.update { it.copy(displayItems = groups.toPersistentList()) }
            }
        }
    }

    fun onUiAction(action: EmergencyContactPickerUiAction) {
        when (action) {
            is EmergencyContactPickerUiAction.ContactClicked -> {
                val selected = uiState.value.selectedContacts.toMutableList()
                if (!selected.remove(action.contact)) selected.add(action.contact)
                _uiState.update { it.copy(selectedContacts = selected.toPersistentList()) }
            }

            EmergencyContactPickerUiAction.AddClicked -> addSelected()
            EmergencyContactPickerUiAction.BackClicked -> _events.tryEmit(EmergencyContactPickerUiEvent.Back)
        }
    }

    private fun addSelected() {
        if (uiState.value.submitting) return
        val targets = uiState.value.selectedContacts
        if (targets.isEmpty()) return
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val circleId = Uuid.parseHex(EMERGENCY_LOCATION_CIRCLE_ID)
            var added = 0
            var alreadyMember = 0
            val failures = mutableListOf<EmergencyContactAddFailure>()
            val stillFailed = mutableListOf<ContactUiModel>()
            try {
                for (contact in targets) {
                    try {
                        connectionService.addToCircle(circleId, contact.odinId)
                        added++
                    } catch (e: ClientException) {
                        if (e.errorCode == OdinClientErrorCode.IdentityAlreadyMemberOfCircle) {
                            alreadyMember++
                        } else {
                            Logger.w(e, TAG) { "addToCircle failed for ${contact.odinId}: ${e.errorCode}" }
                            failures += EmergencyContactAddFailure(contact.name, e.toCircleAddFailureReason())
                            stillFailed += contact
                        }
                    } catch (e: Exception) {
                        Logger.w(e, TAG) { "addToCircle failed for ${contact.odinId}" }
                        failures += EmergencyContactAddFailure(contact.name, e.toCircleAddFailureReason())
                        stillFailed += contact
                    }
                }
            } finally {
                // Only drop entries that actually resolved (succeeded or already-member) — a
                // failed one stays selected and visible so the user can see who still needs
                // attention, instead of the selection silently vanishing on failure (#1096).
                _uiState.update { it.copy(submitting = false, selectedContacts = stillFailed.toPersistentList()) }
                _events.tryEmit(EmergencyContactPickerUiEvent.AddCompleted(added, alreadyMember, failures))
                if (failures.isEmpty()) _events.tryEmit(EmergencyContactPickerUiEvent.Back)
            }
        }
    }
}
