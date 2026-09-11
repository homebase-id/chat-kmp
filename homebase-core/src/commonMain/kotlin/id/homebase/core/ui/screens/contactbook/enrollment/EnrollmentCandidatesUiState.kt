package id.homebase.core.ui.screens.contactbook.enrollment

import androidx.compose.runtime.Immutable
import id.homebase.api.client.connections.CircleGrantOn
import id.homebase.api.client.connections.EnrollmentOutcomeKind

/**
 * One contact that could join a circle, resolved for display.
 *
 * [reviewedAt] is the basis of the offer, not decoration: a list of bare names asks the owner to
 * approve access on trust, where the date lets them notice a review they would not make again.
 */
@Immutable
data class CandidateUi(
    val odinId: String,
    val displayName: String,
    val reviewedAt: Long? = null,
)

/**
 * One circle with a backlog, and what joining it grants.
 *
 * [driveGrants] is stated before the confirm because this grants drive access — for a read circle
 * the drive's storage key is escrowed to each member, and removing them later stops further reads
 * but does not recall anything already read. Closer to disclosure than to a toggle.
 */
@Immutable
data class CandidateCircleUi(
    val circleId: String,
    val circleName: String,
    val grantOn: CircleGrantOn,
    val driveGrants: List<String> = emptyList(),
    /** True when joining escrows a storage key, so enrolling yields deposits rather than members. */
    val grantsRead: Boolean = false,
    val candidates: List<CandidateUi> = emptyList(),
)

/** What happened to one identity, for the per-contact report. */
@Immutable
data class OutcomeUi(
    val displayName: String,
    val kind: EnrollmentOutcomeKind,
)

@Immutable
data class EnrollmentCandidatesUiState(
    val isLoading: Boolean = true,
    val circles: List<CandidateCircleUi> = emptyList(),
    /** Selection is opt-in: nothing is pre-selected. Keyed by circle id. */
    val selected: Map<String, Set<String>> = emptyMap(),
    /** Non-null while a bulk enrol is in flight — these calls walk every connection. */
    val submittingCircleId: String? = null,
    val outcomes: List<OutcomeUi> = emptyList(),
    /** Which circle the open outcomes belong to — a result with no circle names nothing. */
    val outcomesCircleName: String? = null,
    val failed: Boolean = false,
) {
    fun selectedIn(circleId: String): Set<String> = selected[circleId].orEmpty()
}

sealed interface EnrollmentCandidatesUiAction {
    data object BackClicked : EnrollmentCandidatesUiAction
    data class ToggleCandidate(val circleId: String, val odinId: String) : EnrollmentCandidatesUiAction
    data class SelectAll(val circleId: String) : EnrollmentCandidatesUiAction
    data class ClearSelection(val circleId: String) : EnrollmentCandidatesUiAction
    data class Enroll(val circleId: String) : EnrollmentCandidatesUiAction
    data object DismissOutcomes : EnrollmentCandidatesUiAction
}

sealed interface EnrollmentCandidatesUiEvent {
    data object Back : EnrollmentCandidatesUiEvent
}
