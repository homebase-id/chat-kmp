package id.homebase.core.ui.screens.contactbook

import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

data class CircleMemberPickerUiState(
    val circleName: String = "",
    val candidates: PersistentList<CircleMemberCandidate> = persistentListOf(),
    val selected: PersistentList<ContactBookEntry> = persistentListOf(),
    val submitting: Boolean = false,
)

/** A connected identity shown in the add-to-circle picker. [eligible] is false for a connected
 *  but unvetted (unconfirmed) identity — the server rejects circles/add for those with
 *  CannotGrantAutoConnectedMoreCircles, so the row is shown (not hidden) but disabled, with a
 *  reason, rather than silently vanishing from the list. */
data class CircleMemberCandidate(
    val entry: ContactBookEntry,
    val eligible: Boolean,
)

sealed interface CircleMemberPickerUiAction {
    data class ContactClicked(val entry: ContactBookEntry) : CircleMemberPickerUiAction
    data object AddClicked : CircleMemberPickerUiAction
    data object BackClicked : CircleMemberPickerUiAction
}

/** One failed add attempt, carrying the actual server/client reason so the user sees why —
 *  not just that something failed. */
data class CircleMemberAddFailure(val name: String, val reason: CircleAddFailureReason)

sealed interface CircleMemberPickerUiEvent {
    data object Back : CircleMemberPickerUiEvent

    /** [added] landed as real or pending grants; [alreadyMember] were no-ops (already a member
     *  or already pending); [failures] hit a real error (network, forbidden, an unrecognized
     *  400, etc) — kept selected on the picker so the user can see who still needs attention. */
    data class AddCompleted(
        val added: Int,
        val alreadyMember: Int,
        val failures: List<CircleMemberAddFailure>,
    ) : CircleMemberPickerUiEvent
}
