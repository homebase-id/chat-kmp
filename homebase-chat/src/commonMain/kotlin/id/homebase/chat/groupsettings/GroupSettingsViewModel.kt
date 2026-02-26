package id.homebase.chat.groupsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.chat.services.convo.ContactService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class GroupSettingsViewModel(
    savedStateHandle: SavedStateHandle,
    val conversationService: ConversationService,
    val contactService: ContactService,
    val credentialsManager: CredentialsManager,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.GroupSettings>()
    private val _uiState = MutableStateFlow(GroupSettingsUiState())
    val uiState: StateFlow<GroupSettingsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun onUiAction(action: GroupSettingsUiAction) {
        when (action) {
            is GroupSettingsUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = GroupSettingsUiEvent.Back) }
            }
            is GroupSettingsUiAction.AddMembersClicked -> {
                _uiState.update { it.copy(uiEvent = GroupSettingsUiEvent.ShowAddMembers(route.conversationId)) }
            }
            is GroupSettingsUiAction.EditGroupClicked -> {
                _uiState.update { it.copy(uiEvent = GroupSettingsUiEvent.ShowEditGroup(route.conversationId)) }
            }
            is GroupSettingsUiAction.ShowContactInfo -> _uiState.update {
                it.copy(
                    uiEvent = GroupSettingsUiEvent.ShowContactInfo(
                        action.contact.odinId.toString()
                    )
                )
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val conversation =
                    conversationService.getConversation(Uuid.parse(route.conversationId))
                if (conversation != null) {
                    val contacts = conversation.participants.mapNotNull { odinId ->
                        contactService.resolveByOdinId(odinId)
                    }
                    val domain = credentialsManager.requireActiveCredentials().domain.domainName
                    _uiState.update {
                        it.copy(
                            conversation = conversation,
                            contacts = contacts,
                            currentOdinId = domain,
                            isLoading = false,
                        )
                    }
                } else {
                    Logger.d("Failed to load contacts for group conversation")
                    _uiState.update { it.copy(isLoading = false) }
                }

            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}