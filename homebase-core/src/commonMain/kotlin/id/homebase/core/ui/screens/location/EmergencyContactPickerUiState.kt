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

sealed interface EmergencyContactPickerUiEvent {
    data object Back : EmergencyContactPickerUiEvent

    /** [added] landed as real or pending grants; [alreadyMember] were no-ops (already a member
     *  or already pending); [failed] hit an unexpected error (network, forbidden, etc). */
    data class AddCompleted(
        val added: Int,
        val alreadyMember: Int,
        val failed: Int,
    ) : EmergencyContactPickerUiEvent
}
