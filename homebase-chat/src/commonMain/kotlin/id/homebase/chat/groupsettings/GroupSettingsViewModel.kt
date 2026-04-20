package id.homebase.chat.groupsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.Back
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.Error
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.ShowAddMembers
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.ShowContactInfo
import id.homebase.chat.groupsettings.GroupSettingsUiEvent.ShowEditGroup
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.buildConnectToIdentityUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class GroupSettingsViewModel(
    savedStateHandle: SavedStateHandle,
    private val conversationStream: ConversationStream,
    private val conversationService: ConversationService,
    private val contactService: ContactService,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.GroupSettings>()
    private val _uiState = MutableStateFlow(GroupSettingsUiState())
    val uiState: StateFlow<GroupSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            conversationStream.start()
            conversationStream.conversations
                .filter { conversations ->
                    conversations.items.any { it.id.toString() == route.conversationId }
                }
                .collect { conversations ->
                    val conversation =
                        conversations.items.find { it.id.toString() == route.conversationId }
                    conversation?.let {
                        loadData(conversation)
                    }
                }
        }
    }

    fun onUiAction(action: GroupSettingsUiAction) {
        when (action) {
            is GroupSettingsUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = Back) }
            }

            is GroupSettingsUiAction.AddMembersClicked -> {
                _uiState.update { it.copy(uiEvent = ShowAddMembers(route.conversationId)) }
            }

            is GroupSettingsUiAction.EditGroupClicked -> {
                _uiState.update { it.copy(uiEvent = ShowEditGroup(route.conversationId)) }
            }

            is GroupSettingsUiAction.ShowContactInfo -> {
                _uiState.update {
                    it.copy(uiEvent = ShowContactInfo(action.contact.odinId.toString()))
                }
            }

            is GroupSettingsUiAction.ShowMemberSheet -> {
                _uiState.update {
                    it.copy(uiSheet = GroupSettingsUiSheet.Member(action.contact.id))
                }
            }

            is GroupSettingsUiAction.LeaveGroupConfirm -> {
                viewModelScope.launch {
                    try {
                        uiState.value.conversation?.let { conversation ->
                            uiState.value.currentOdinId?.let { currentUser ->
                                val isSoleAdmin = conversation.isCurrentUserAdmin(currentUser)
                                        && conversation.admins.size == 1
                                val forceLocalOnly =
                                    isSoleAdmin && !hasReachableNonAdmin(conversation)
                                conversationService.leaveGroup(
                                    conversationId = conversation.id,
                                    forceLocalOnly = forceLocalOnly
                                )
                                conversationStream.onConversationLeft(conversation.id)
                                _uiState.update { it.copy(uiEvent = Back) }
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to leave group", e)
                        _uiState.update { it.copy(uiEvent = Error("Failed to leave group")) }
                    }
                }
            }

            is GroupSettingsUiAction.LeaveGroupClicked -> {
                uiState.value.conversation?.let { conversation ->
                    uiState.value.currentOdinId?.let { currentUser ->
                        val isSoleAdmin = conversation.isCurrentUserAdmin(currentUser)
                                && conversation.admins.size == 1
                                && conversation.isGroupConversation
                        // Only force the "choose new admin" dialog when there IS someone
                        // reachable to promote. If the caller has no connected non-admin,
                        // fall through to ConfirmLeave — leaveGroup will mark locally only.
                        if (isSoleAdmin && hasReachableNonAdmin(conversation)) {
                            _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.LeaveChooseAdmin) }
                        } else {
                            _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.ConfirmLeave) }
                        }
                    }
                }
            }

            is GroupSettingsUiAction.MakeAdmin -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        uiState.value.conversation?.let { conversation ->
                            try {
                                conversationService.updateAdmins(
                                    conversationId = conversation.id,
                                    add = listOf(action.contact.odinId)
                                )
                            } catch (e: Exception) {
                                Logger.e("Failed to add an admin", e)
                                _uiState.update { it.copy(uiEvent = Error("Failed to add an admin")) }
                            }
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.MakeAdmin(action.contact)) }
            }

            is GroupSettingsUiAction.RemoveAdmin -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        uiState.value.conversation?.let { conversation ->
                            try {
                                conversationService.updateAdmins(
                                    conversationId = conversation.id,
                                    remove = listOf(action.contact.odinId)
                                )
                            } catch (e: Exception) {
                                Logger.e("Failed to remove an admin", e)
                                _uiState.update { it.copy(uiEvent = Error("Failed to remove an admin")) }
                            }
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.RemoveAdmin(action.contact)) }
            }

            is GroupSettingsUiAction.RemoveFromGroup -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        uiState.value.conversation?.let { conversation ->
                            try {
                                conversationService.updateGroupMembers(
                                    conversationId = conversation.id,
                                    remove = listOf(action.contact.odinId)
                                )
                                // dismiss sheet on successful removal
                                _uiState.update { it.copy(uiSheet = null) }
                            } catch (e: Exception) {
                                Logger.e("Failed to remove a member", e)
                                _uiState.update { it.copy(uiEvent = Error("Failed to remove a member")) }
                            }
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.RemoveFromGroup(action.contact)) }
            }

            is GroupSettingsUiAction.ConnectToIdentity -> {
                viewModelScope.launch {
                    val currentUser = credentialsManager.requireActiveCredentials().domain
                    val url = currentUser.buildConnectToIdentityUrl(action.odinId)
                    _uiState.update { it.copy(uiEvent = GroupSettingsUiEvent.OpenUrl(url)) }
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    fun dialogClosed() {
        _uiState.update { it.copy(uiDialog = null) }
    }

    fun bottomSheetDismissed() {
        _uiState.update { it.copy(uiSheet = null) }
    }

    /**
     * True when at least one loaded participant is both Connected (transit-reachable)
     * and not already an admin — i.e. someone the sole admin could promote and hand off to.
     */
    private fun hasReachableNonAdmin(conversation: ConversationUiModel): Boolean {
        return uiState.value.contacts.any { contact ->
            contact.connectionState == ContactConnectionState.Connected &&
                    !conversation.isCurrentUserAdmin(contact.odinId)
        }
    }

    private fun loadData(conversation: ConversationUiModel) {
        viewModelScope.launch {
            try {
                val domain = credentialsManager.requireActiveCredentials().domain

                val contacts = conversation.participants
                    .filter { it != domain }
                    .map { odinId ->
                        contactService.resolveByOdinId(odinId) ?: ContactUiModel(
                            id = Uuid.random(),
                            odinId = odinId,
                            name = odinId.domainName,
                            avatarInitials = odinId.domainName.take(2).uppercase(),
                            connectionState = ContactConnectionState.NotConnected
                        )
                    }

                _uiState.update {
                    it.copy(
                        conversation = conversation,
                        contacts = contacts,
                        currentOdinId = domain,
                        isCurrentUserGroupAdmin = conversation.isCurrentUserAdmin(domain),
                        isLegacyGroup = conversation.isLegacyGroup,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}