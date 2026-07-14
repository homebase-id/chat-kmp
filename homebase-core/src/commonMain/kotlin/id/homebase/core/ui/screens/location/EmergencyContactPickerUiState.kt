package id.homebase.core.ui.screens.location

import id.homebase.chat.createconversation.ContactGroup
import id.homebase.chat.data.ContactUiModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

data class EmergencyContactPickerUiState(
    val displayItems: PersistentList<ContactGroup> = persistentListOf(),
    val selectedContacts: PersistentList<ContactUiModel> = persistentListOf(),
    val submitting: Boolean = false,
)

sealed interface EmergencyContactPickerUiAction {
    data class ContactClicked(val contact: ContactUiModel) : EmergencyContactPickerUiAction
    data object AddClicked : EmergencyContactPickerUiAction
    data object BackClicked : EmergencyContactPickerUiAction
}

/** One failed add attempt, carrying the actual server/client reason so the user sees why —
 *  not just that something failed. */
data class EmergencyContactAddFailure(val name: String, val reason: String)

sealed interface EmergencyContactPickerUiEvent {
    data object Back : EmergencyContactPickerUiEvent

    /** [added] landed as real or pending grants; [alreadyMember] were no-ops (already a member
     *  or already pending); [failures] hit a real error (network, forbidden, an unrecognized
     *  400, etc) — kept selected on the picker so the user can see who still needs attention. */
    data class AddCompleted(
        val added: Int,
        val alreadyMember: Int,
        val failures: List<EmergencyContactAddFailure>,
    ) : EmergencyContactPickerUiEvent
}
