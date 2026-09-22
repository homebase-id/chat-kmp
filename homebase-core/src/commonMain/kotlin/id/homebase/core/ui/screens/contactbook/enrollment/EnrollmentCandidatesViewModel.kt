package id.homebase.core.ui.screens.contactbook.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.EnrollmentOutcomeKind
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import id.homebase.core.ui.screens.contactbook.resolveCircleDrives
import id.homebase.api.youauth.DrivePermission
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "EnrollmentCandidates"

/**
 * The contacts who qualify for one of this app's circles but were never offered it.
 *
 * The backlog is real and invisible: assigning a circle to an app does not reach back over
 * contacts the owner already reviewed, because a review is a moment rather than a standing rule.
 */
class EnrollmentCandidatesViewModel(
    private val connectionService: ConnectionService,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EnrollmentCandidatesUiState())
    val uiState: StateFlow<EnrollmentCandidatesUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EnrollmentCandidatesUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<EnrollmentCandidatesUiEvent> = _events.asSharedFlow()

    init {
        load()
    }

    fun onUiAction(action: EnrollmentCandidatesUiAction) {
        when (action) {
            EnrollmentCandidatesUiAction.BackClicked ->
                _events.tryEmit(EnrollmentCandidatesUiEvent.Back)

            is EnrollmentCandidatesUiAction.ToggleCandidate -> _uiState.update { state ->
                val current = state.selectedIn(action.circleId)
                val next = if (action.odinId in current) current - action.odinId
                else current + action.odinId
                state.copy(selected = state.selected + (action.circleId to next))
            }

            is EnrollmentCandidatesUiAction.SelectAll -> _uiState.update { state ->
                val all = state.circles.firstOrNull { it.circleId == action.circleId }
                    ?.candidates?.map { it.odinId }?.toSet().orEmpty()
                state.copy(selected = state.selected + (action.circleId to all))
            }

            is EnrollmentCandidatesUiAction.ClearSelection -> _uiState.update { state ->
                state.copy(selected = state.selected + (action.circleId to emptySet()))
            }

            is EnrollmentCandidatesUiAction.Enroll -> enroll(action.circleId)

            EnrollmentCandidatesUiAction.DismissOutcomes ->
                _uiState.update {
                    it.copy(outcomes = emptyList(), outcomesCircleName = null, failed = false)
                }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, failed = false) }
            val candidates = try {
                connectionService.getEnrollmentCandidates(ChatProtocol.ChatAppId.toString())
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "getEnrollmentCandidates failed" }
                _uiState.update { it.copy(isLoading = false, failed = true) }
                return@launch
            }

            // Names come from the contact book where we have one; a candidate with no saved
            // contact still has to be listed, so the domain stands in rather than dropping them.
            val byDomain = contactRepository.contacts.value
                .mapNotNull { it.toContactBookEntry() }
                .filter { !it.odinId.isNullOrBlank() }
                .associateBy { it.odinId!!.lowercase() }

            val circleDefs = connectionService.circles.value.circles.associateBy {
                it.circle.id.lowercase()
            }

            val circles = candidates.map { entry ->
                val definition = circleDefs[entry.circleId.replace("-", "").lowercase()]?.circle
                val drives = definition?.let { resolveCircleDrives(it) }.orEmpty()
                CandidateCircleUi(
                    circleId = entry.circleId,
                    circleName = entry.circleName,
                    grantOn = entry.grantOn,
                    driveGrants = drives.mapNotNull { drive ->
                        drive.label?.let { "$it — ${drive.permission}" }
                    },
                    // Read is what escrows a storage key, and so what turns an enrolment into a
                    // deposit. Write and react need no key.
                    grantsRead = definition?.driveGrants.orEmpty().any { grant ->
                        grant.permissionedDrive?.permission
                            ?.contains(DrivePermission.Read.name, ignoreCase = true) == true
                    },
                    candidates = entry.candidates.map { candidate ->
                        val domain = candidate.odinId.domainName
                        CandidateUi(
                            odinId = domain,
                            displayName = byDomain[domain.lowercase()]?.displayName ?: domain,
                            reviewedAt = candidate.reviewedAt,
                        )
                    }.sortedBy { it.displayName.lowercase() },
                )
            }.filter { it.candidates.isNotEmpty() }

            _uiState.update { it.copy(isLoading = false, circles = circles) }
        }
    }

    /**
     * Enrol the selected identities, then reload.
     *
     * Reloading rather than removing them locally: an outcome of Skipped means the identity is
     * still a candidate, and guessing which stayed would put the list out of step with the server.
     */
    private fun enroll(circleId: String) {
        val selected = _uiState.value.selectedIn(circleId).toList()
        if (selected.isEmpty()) return
        val circle = _uiState.value.circles.firstOrNull { it.circleId == circleId }
        val names = circle?.candidates.orEmpty()
            .associate { it.odinId.lowercase() to it.displayName }

        _uiState.update { it.copy(submittingCircleId = circleId, failed = false) }
        viewModelScope.launch {
            try {
                val result = connectionService.addManyToCircle(circleId, selected)
                _uiState.update { state ->
                    state.copy(
                        submittingCircleId = null,
                        selected = state.selected + (circleId to emptySet()),
                        outcomes = result.outcomes.map { outcome ->
                            val domain = outcome.odinId.domainName
                            OutcomeUi(names[domain.lowercase()] ?: domain, outcome.kind)
                        },
                        outcomesCircleName = circle?.circleName,
                    )
                }
                load()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "addManyToCircle failed for $circleId" }
                _uiState.update { it.copy(submittingCircleId = null, failed = true) }
            }
        }
    }
}

/** True when the result should be reported as pending rather than done. */
fun EnrollmentOutcomeKind.isPending(): Boolean = this == EnrollmentOutcomeKind.Deposited
