package id.homebase.chat.createconversationgroup

import androidx.compose.runtime.Immutable
import id.homebase.chat.data.ContactUiModel
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.uuid.Uuid

@Immutable
data class CreateConversationGroupUiState(
    val uiEvent: CreateConversationGroupUiEvent? = null,
    val uiDialog: CreateConversationGroupUiDialog? = null,
    val contacts: PersistentList<ContactUiModel> = persistentListOf(),
    val isCreatingGroup: Boolean = false,
    val groupImage: PlatformFile? = null,
    val createAllowed: Boolean = false,
)

sealed interface CreateConversationGroupUiEvent {
    data object Back : CreateConversationGroupUiEvent
    data class LoadConversation(val conversationId: Uuid) : CreateConversationGroupUiEvent
    data class ShowErrorMessage(val message: String) : CreateConversationGroupUiEvent
    data object PickGroupImage : CreateConversationGroupUiEvent
}

sealed interface CreateConversationGroupUiDialog {
    data class RemoveMember(val contact: ContactUiModel) : CreateConversationGroupUiDialog
    data object RemoveMemberWarning : CreateConversationGroupUiDialog
    //data object AddImage : CreateConversationGroupUiDialog
}


