package id.homebase.chat.createconversationgroup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ContactService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import io.github.vinceglb.filekit.name
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateConversationGroupViewModel(
    savedStateHandle: SavedStateHandle,
    private val contactService: ContactService,
    private val conversationWriterService: ConversationService,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.CreateConversationGroup>()
    private val contactIds = route.contactIds
    private var isInitialLoad = true

    private val _uiState = MutableStateFlow(CreateConversationGroupUiState())
    val uiState: StateFlow<CreateConversationGroupUiState> = _uiState.asStateFlow()
    val groupNameTextState = TextFieldState()

    init {
        viewModelScope.launch {
            contactService.start()
            contactService.contacts.collect { allContacts ->
                if (isInitialLoad) {
                    val filteredContacts = allContacts
                        .filter { contact ->
                            contactIds.contains(contact.id.toString())
                        }
                        .sortedBy { it.name.uppercase() }
                    _uiState.update { it.copy(contacts = filteredContacts.toPersistentList()) }
                    isInitialLoad = false
                }
            }
        }
        viewModelScope.launch {
            snapshotFlow { groupNameTextState.text.toString() }.collectLatest { groupName ->
                _uiState.update { it.copy(createAllowed = groupName.isNotBlank()) }
            }
        }
    }

    fun onUiAction(action: CreateConversationGroupUiAction) {
        when (action) {
            is CreateConversationGroupUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = CreateConversationGroupUiEvent.Back) }
            is CreateConversationGroupUiAction.CreateGroup -> {
                viewModelScope.launch {
                    try {
                        val name = groupNameTextState.text.toString()
                        val memberOdinIds = uiState.value.contacts.map { it.odinId }
                        if (name.isBlank() || memberOdinIds.size < 2) return@launch

                        _uiState.update { it.copy(isCreatingGroup = true) }

                        val groupImage = uiState.value.groupImage
                        val bundle = if (groupImage != null) {
                            MessageAttachmentBuilder
                                .build(
                                    attachments = listOf(
                                        AttachmentInput(
                                            filePath = groupImage.toString(),
                                            contentType = detectContentTypeFromExtensionOrHint(
                                                groupImage.name
                                            ),
                                            displayName = groupImage.name,
                                        )
                                    ),
                                    fileOperationsProvider = fileOperationsProvider,
                                )
                        } else {
                            null
                        }

                        val conversationId = conversationWriterService.createConversation(
                            recipients = memberOdinIds,
                            title = name,
                            payloadBundle = bundle,
                        )
                        sendEvent(CreateConversationGroupUiEvent.LoadConversation(conversationId))
                    } catch (e: Exception) {
                        sendEvent(CreateConversationGroupUiEvent.ShowErrorMessage("Failed to create conversation: ${e.message}"))
                    }
                }
            }

            is CreateConversationGroupUiAction.RemoveClicked -> {
                if (uiState.value.contacts.size <= 2) {
                    _uiState.update { it.copy(uiDialog = CreateConversationGroupUiDialog.RemoveMemberWarning) }
                    return
                }
                _uiState.update {
                    it.copy(uiDialog = CreateConversationGroupUiDialog.RemoveMember(action.contact))
                }
            }

            is CreateConversationGroupUiAction.RemoveMember -> {
                _uiState.update {
                    it.copy(
                        contacts = it.contacts
                            .filterNot { contact -> contact.odinId == action.contact.odinId }
                            .toPersistentList()
                    )
                }
            }

            is CreateConversationGroupUiAction.AddGroupImage -> {
                sendEvent(CreateConversationGroupUiEvent.PickGroupImage)
            }

            is CreateConversationGroupUiAction.RemoveGroupImage -> {
                _uiState.update { it.copy(groupImage = null) }
            }

            is CreateConversationGroupUiAction.AttachGroupImage -> {
                _uiState.update { it.copy(groupImage = action.file) }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null, uiDialog = null) }
    }

    fun dialogClosed() {
        _uiState.update { it.copy(uiDialog = null) }
    }

    private fun sendEvent(event: CreateConversationGroupUiEvent) {
        _uiState.update { it.copy(uiEvent = event, isCreatingGroup = false) }
    }
}