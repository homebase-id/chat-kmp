package id.homebase.chat.createconversationgroup

import id.homebase.chat.data.ContactUiModel
import io.github.vinceglb.filekit.PlatformFile

sealed interface CreateConversationGroupUiAction {
    data object BackClicked : CreateConversationGroupUiAction
    data object CreateGroup : CreateConversationGroupUiAction
    data object AddGroupImage : CreateConversationGroupUiAction
    data object RemoveGroupImage : CreateConversationGroupUiAction
    data class AttachGroupImage(val file: PlatformFile) : CreateConversationGroupUiAction
    data class RemoveClicked(val contact: ContactUiModel) : CreateConversationGroupUiAction
    data class RemoveMember(val contact: ContactUiModel) : CreateConversationGroupUiAction
}
