package id.homebase.chat.editconversationgroup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class EditConversationGroupViewModel(
    savedStateHandle: SavedStateHandle,
    private val conversationService: ConversationService,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {
    val route = savedStateHandle.toRoute<Route.GroupEdit>()
    private val _uiState = MutableStateFlow(EditConversationGroupUiState())
    val uiState: StateFlow<EditConversationGroupUiState> = _uiState.asStateFlow()
    val groupNameTextState = TextFieldState()

    init {
        loadData()
        viewModelScope.launch {
            snapshotFlow { groupNameTextState.text.toString() }.collectLatest { groupName ->
                _uiState.update { it.copy(saveAllowed = isSaveAllowed(groupName, uiState.value.imageChanged)) }
            }
        }
    }

    fun onUiAction(action: EditConversationGroupUiAction) {
        when (action) {
            is EditConversationGroupUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = EditConversationGroupUiEvent.Back) }
            }

            is EditConversationGroupUiAction.SaveGroup -> {
                viewModelScope.launch {
                    try {
                        val conversation = uiState.value.conversation
                        val name = groupNameTextState.text.toString()
                        if (conversation == null ||name.isBlank()) return@launch
                        _uiState.update { it.copy(isLoading = true) }

                        val groupImage = uiState.value.groupImage

                        val bundle = if (groupImage != null) {
                            MessageAttachmentBuilder.buildSingle(
                                attachment = AttachmentInput(
                                    filePath = groupImage.toString(),
                                    contentType = detectContentTypeFromExtensionOrHint(groupImage.name),
                                    displayName = groupImage.name
                                ),
                                fileOperationsProvider = fileOperationsProvider,
                                payloadKey = ChatProtocol.ConversationImageKey
                            )
                        } else {
                            null
                        }

                        conversationService.updateConversation(
                            conversationId = conversation.id,
                            title = name,
                            payloadBundle = bundle,
                        )
                        sendEvent(EditConversationGroupUiEvent.Back)
                    } catch (e: Exception) {
                        sendEvent(EditConversationGroupUiEvent.Error("Failed to update conversation: ${e.message}"))
                    }
                }
            }

            is EditConversationGroupUiAction.AddGroupImage -> {
                _uiState.update {
                    it.copy(
                        uiEvent = EditConversationGroupUiEvent.PickGroupImage,
                        imageChanged = true,
                        saveAllowed = isSaveAllowed(groupNameTextState.toString(), true),
                    )
                }
            }

            is EditConversationGroupUiAction.RemoveGroupImage -> {
                _uiState.update {
                    it.copy(
                        groupImage = null,
                        imageChanged = true,
                        saveAllowed = isSaveAllowed(groupNameTextState.toString(), true),
                    )
                }
            }

            is EditConversationGroupUiAction.AttachGroupImage -> {
                _uiState.update { it.copy(groupImage = action.file) }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: EditConversationGroupUiEvent) {
        _uiState.update { it.copy(uiEvent = event, isLoading = false) }
    }

    private fun isSaveAllowed(groupName: String, imageChanged: Boolean): Boolean {
        return groupName.isNotBlank()
                && (groupName != uiState.value.originalName || imageChanged)
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val conversation = conversationService.getConversation(Uuid.parse(route.conversationId))
                if (conversation != null) {
                    groupNameTextState.setTextAndPlaceCursorAtEnd(conversation.name)
                    _uiState.update {
                        it.copy(
                            originalName = conversation.name,
                            conversation = conversation,
                            isLoading = false,
                        )
                    }
                } else {
                    Logger.d("Failed to load conversation")
                    _uiState.update { it.copy(isLoading = false) }
                }

            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}

@Immutable
data class EditConversationGroupUiState(
    val conversation: ConversationUiModel? = null,
    val originalName: String = "",
    val imageChanged: Boolean = false,
    val isLoading: Boolean = true,
    val groupImage: PlatformFile? = null,
    val saveAllowed: Boolean = false,
    val uiEvent: EditConversationGroupUiEvent? = null,
)

sealed interface EditConversationGroupUiEvent {
    data object Back : EditConversationGroupUiEvent
    data class Error(val errorMessage: String) : EditConversationGroupUiEvent
    data object PickGroupImage : EditConversationGroupUiEvent
}

sealed interface EditConversationGroupUiAction {
    data object BackClicked : EditConversationGroupUiAction
    data object SaveGroup : EditConversationGroupUiAction
    data object AddGroupImage : EditConversationGroupUiAction
    data object RemoveGroupImage : EditConversationGroupUiAction
    data class AttachGroupImage(val file: PlatformFile) : EditConversationGroupUiAction
}
