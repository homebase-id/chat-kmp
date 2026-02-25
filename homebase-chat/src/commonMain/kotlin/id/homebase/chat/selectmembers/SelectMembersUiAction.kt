package id.homebase.chat.selectmembers

import id.homebase.chat.data.ContactUiModel

sealed interface SelectMembersUiAction {
    data object BackClicked : SelectMembersUiAction
    data object NextClicked : SelectMembersUiAction
    data class ContactClicked(val contact: ContactUiModel): SelectMembersUiAction
}