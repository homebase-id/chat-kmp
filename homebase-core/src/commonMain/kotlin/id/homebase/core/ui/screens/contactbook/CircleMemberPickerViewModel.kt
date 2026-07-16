package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.crypto.Md5
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
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

private const val TAG = "CircleMemberPickerViewModel"

/**
 * Generic "add contact to circle" picker — works for any circle by [circleId], not just
 * emergency-location access. Candidate list: every connected identity, excluding anyone already
 * a real member of [circleId] (cheap, from the already-loaded bulk circle-members read). A
 * connected-but-unvetted (unconfirmed) identity is still shown — hiding it entirely made it look
 * like the contact didn't exist, which was confusing — but marked ineligible ([CircleMemberCandidate.eligible]
 * = false) and can't be selected, since the server 400s circles/add for those identities
 * (CannotGrantAutoConnectedMoreCircles). A duplicate add on a still-pending contact simply hits
 * the server's real IdentityAlreadyMemberOfCircle response (handled in [addSelected]) rather than
 * being pre-filtered from a guess.
 */
class CircleMemberPickerViewModel(
    private val circleId: Uuid,
    circleName: String,
    private val repo: ContactRepository,
    private val connectionService: ConnectionService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CircleMemberPickerUiState(circleName = circleName))
    val uiState: StateFlow<CircleMemberPickerUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    private val _events = MutableSharedFlow<CircleMemberPickerUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CircleMemberPickerUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                repo.contacts,
                connectionService.connections,
                connectionService.circles,
                snapshotFlow { searchTextState.text.toString() },
            ) { contacts, connectionState, circleState, query ->
                val members = circleState.membersOf(circleId.toHexString())
                val bookEntries = contacts.mapNotNull { it.toContactBookEntry() }
                val byOdin = bookEntries
                    .filter { !it.odinId.isNullOrBlank() }
                    .associateBy { it.odinId!!.lowercase() }

                val connectedRegistrations = connectionState.map.values
                    .filter { it.status == ConnectionStatus.Connected }
                    .filterNot { members.contains(it.odinId.domainName.lowercase()) }

                val q = query.trim().lowercase()
                connectedRegistrations
                    .map { reg ->
                        val domain = reg.odinId.domainName.lowercase()
                        CircleMemberCandidate(entry = byOdin[domain] ?: syntheticEntry(domain), eligible = reg.vetted)
                    }
                    .filter { q.isEmpty() || it.entry.displayName.lowercase().contains(q) || it.entry.odinId?.lowercase()?.contains(q) == true }
                    .sortedBy { it.entry.displayName.lowercase() }
            }.collect { candidates ->
                _uiState.update { it.copy(candidates = candidates.toPersistentList()) }
            }
        }
    }

    private fun syntheticEntry(domain: String): ContactBookEntry {
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

    fun onUiAction(action: CircleMemberPickerUiAction) {
        when (action) {
            is CircleMemberPickerUiAction.ContactClicked -> {
                val eligible = uiState.value.candidates
                    .firstOrNull { it.entry.uniqueId == action.entry.uniqueId }?.eligible == true
                if (eligible) {
                    val selected = uiState.value.selected.toMutableList()
                    if (!selected.remove(action.entry)) selected.add(action.entry)
                    _uiState.update { it.copy(selected = selected.toPersistentList()) }
                }
            }

            CircleMemberPickerUiAction.AddClicked -> addSelected()
            CircleMemberPickerUiAction.BackClicked -> _events.tryEmit(CircleMemberPickerUiEvent.Back)
        }
    }

    private fun addSelected() {
        if (uiState.value.submitting) return
        val targets = uiState.value.selected
        if (targets.isEmpty()) return
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            var added = 0
            var alreadyMember = 0
            val failures = mutableListOf<CircleMemberAddFailure>()
            val stillFailed = mutableListOf<ContactBookEntry>()
            try {
                for (entry in targets) {
                    val odinId = entry.odinId?.let(::OdinId)
                    if (odinId == null) {
                        failures += CircleMemberAddFailure(
                            entry.displayName,
                            CircleAddFailureReason.Raw("No Homebase ID on file"),
                        )
                        stillFailed += entry
                        continue
                    }
                    try {
                        connectionService.addToCircle(circleId, odinId)
                        added++
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: ClientException) {
                        if (e.errorCode == OdinClientErrorCode.IdentityAlreadyMemberOfCircle) {
                            alreadyMember++
                        } else {
                            Logger.w(e, TAG) { "addToCircle failed for $odinId: ${e.errorCode}" }
                            failures += CircleMemberAddFailure(entry.displayName, e.toCircleAddFailureReason())
                            stillFailed += entry
                        }
                    } catch (e: Exception) {
                        Logger.w(e, TAG) { "addToCircle failed for $odinId" }
                        failures += CircleMemberAddFailure(entry.displayName, e.toCircleAddFailureReason())
                        stillFailed += entry
                    }
                }
            } finally {
                // Only drop entries that actually resolved (succeeded or already-member) from
                // the selection — a failed one stays selected and visible so the user can see
                // who still needs attention and retry, instead of the selection silently
                // vanishing and the failure reading as "nothing happened".
                _uiState.update { it.copy(submitting = false, selected = stillFailed.toPersistentList()) }
                _events.tryEmit(CircleMemberPickerUiEvent.AddCompleted(added, alreadyMember, failures))
                if (failures.isEmpty()) _events.tryEmit(CircleMemberPickerUiEvent.Back)
            }
        }
    }
}
