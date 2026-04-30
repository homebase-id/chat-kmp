package id.homebase.chat.groupsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.RecipientTransferHistoryEntry
import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.common.OdinId
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
import id.homebase.chat.services.toErrorDetailRes
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.buildConnectToIdentityUrl
import id.homebase.core.util.initials
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
    private val driveFileProvider: DriveFileProvider,
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
                    // Show full-screen overlay while the leave is in flight. Cleared on
                    // error; on success the screen pops via the Back event below so
                    // explicit clearing is unnecessary.
                    _uiState.update { it.copy(isLeaving = true) }
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
                        _uiState.update {
                            it.copy(
                                isLeaving = false,
                                uiEvent = Error("Failed to leave group"),
                            )
                        }
                    }
                }
            }

            is GroupSettingsUiAction.LeaveGroupClicked -> {
                uiState.value.conversation?.let { conversation ->
                    uiState.value.currentOdinId?.let { currentUser ->
                        val isAdmin = conversation.isCurrentUserAdmin(currentUser)
                        val isSoleAdmin = isAdmin
                                && conversation.admins.size == 1
                                && conversation.isGroupConversation
                        when {
                            // Legacy groups don't support admin management, so the
                            // "choose another admin first" path is a dead end for
                            // legacy admins. Surface a strong-warning dialog and let
                            // them proceed; service-side leaveGroup uses the local-
                            // only branch for legacy groups regardless.
                            conversation.isLegacyGroup && isAdmin -> {
                                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.LeaveLegacyAdminWarning) }
                            }
                            // Only force the "choose new admin" dialog when there IS someone
                            // reachable to promote. If the caller has no connected non-admin,
                            // fall through to ConfirmLeave — leaveGroup will mark locally only.
                            isSoleAdmin && hasReachableNonAdmin(conversation) -> {
                                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.LeaveChooseAdmin) }
                            }
                            else -> {
                                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.ConfirmLeave) }
                            }
                        }
                    }
                }
            }

            is GroupSettingsUiAction.MakeAdmin -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        runMemberOp(action.contact.odinId, "Failed to add an admin") { conversation ->
                            conversationService.updateAdmins(
                                conversationId = conversation.id,
                                add = listOf(action.contact.odinId)
                            )
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.MakeAdmin(action.contact)) }
            }

            is GroupSettingsUiAction.RemoveAdmin -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        runMemberOp(action.contact.odinId, "Failed to remove an admin") { conversation ->
                            conversationService.updateAdmins(
                                conversationId = conversation.id,
                                remove = listOf(action.contact.odinId)
                            )
                        }
                    }
                    return
                }
                _uiState.update { it.copy(uiDialog = GroupSettingsUiDialog.RemoveAdmin(action.contact)) }
            }

            is GroupSettingsUiAction.RemoveFromGroup -> {
                if (action.skipConfirmation) {
                    viewModelScope.launch {
                        val ok = runMemberOp(action.contact.odinId, "Failed to remove a member") { conversation ->
                            conversationService.updateGroupMembers(
                                conversationId = conversation.id,
                                remove = listOf(action.contact.odinId)
                            )
                        }
                        if (ok) {
                            // dismiss sheet on successful removal — the row in the
                            // participant list will also disappear once the conversation
                            // file refreshes.
                            _uiState.update { it.copy(uiSheet = null) }
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

            is GroupSettingsUiAction.HealGroupClicked -> {
                val conversation = uiState.value.conversation ?: return
                if (uiState.value.isHealing) return
                _uiState.update { it.copy(isHealing = true) }
                viewModelScope.launch {
                    try {
                        Logger.i("GroupSettings: heal requested for ${conversation.id}")
                        logTransferSnapshot("BEFORE heal", conversation.id, uiState.value)
                        val result = conversationService.healGroupDistribution(conversation.id)
                        Logger.i("GroupSettings: heal result for ${conversation.id} mainHealed=${result.mainHealed} adminHealed=${result.adminHealed}")
                        // Re-load transfer history so the user sees outbox/sending state immediately
                        loadTransferHistory(conversation)
                        logTransferSnapshot("AFTER heal", conversation.id, uiState.value)
                        _uiState.update {
                            it.copy(
                                isHealing = false,
                                uiEvent = GroupSettingsUiEvent.HealCompleted(
                                    mainHealed = result.mainHealed,
                                    adminHealed = result.adminHealed,
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("GroupSettings: heal failed for ${conversation.id}", e)
                        _uiState.update {
                            it.copy(
                                isHealing = false,
                                uiEvent = Error("Failed to heal group: ${e.message ?: "unknown error"}")
                            )
                        }
                    }
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
    /**
     * Wraps a per-member service call with the in-flight tracking that drives the
     * spinner in the member-action sheet. Adds [odinId] to [GroupSettingsUiState.pendingMemberOps]
     * before invoking [block]; clears it in a finally block so the spinner always
     * unwinds even on cancellation. On exception, surfaces an error event with
     * [errorMessage] and returns false; otherwise returns true.
     *
     * If there is no current conversation in state, this is a no-op returning false.
     */
    private suspend fun runMemberOp(
        odinId: OdinId,
        errorMessage: String,
        block: suspend (ConversationUiModel) -> Unit,
    ): Boolean {
        val conversation = uiState.value.conversation ?: return false
        _uiState.update { it.copy(pendingMemberOps = it.pendingMemberOps + odinId) }
        return try {
            block(conversation)
            true
        } catch (e: Exception) {
            Logger.e(errorMessage, e)
            _uiState.update { it.copy(uiEvent = Error(errorMessage)) }
            false
        } finally {
            _uiState.update { it.copy(pendingMemberOps = it.pendingMemberOps - odinId) }
        }
    }

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
                            avatarInitials = odinId.domainName.initials(),
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

                if (conversation.isGroupConversation && !conversation.isLegacyGroup) {
                    loadTransferHistory(conversation)
                }
            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * For each of the two group files (main conversation + admin), fetches the per-recipient
     * transfer-history if (and only if) the current user is the original author of that file.
     * Mirrors the loader in [id.homebase.chat.messageinfo.MessageInfoViewModel] — same call,
     * same status mapping. Result is exposed in [GroupSettingsUiState.mainFileTransfer] /
     * [GroupSettingsUiState.adminFileTransfer]; null map = column hidden in the UI.
     */
    private suspend fun loadTransferHistory(conversation: ConversationUiModel) {
        val domain = credentialsManager.requireActiveDomain()

        val mainFile = try {
            conversationService.getConversationHomebaseFile(conversation.id)
        } catch (e: Exception) {
            Logger.w(throwable = e) { "loadTransferHistory: failed to load main file ${conversation.id}" }
            null
        }
        val adminFile = try {
            conversationService.getConversationAdminHomebaseFile(conversation.id)
        } catch (e: Exception) {
            Logger.w(throwable = e) { "loadTransferHistory: failed to load admin file ${conversation.id}" }
            null
        }

        val mainTransfer = fetchTransferIfAuthor("main", domain, mainFile)
        val adminTransfer = fetchTransferIfAuthor("admin", domain, adminFile)

        Logger.d {
            "loadTransferHistory: ${conversation.id} mainAuthored=${mainTransfer != null} mainEntries=${mainTransfer?.size} " +
                    "adminAuthored=${adminTransfer != null} adminEntries=${adminTransfer?.size} " +
                    "main=${renderTransferMap(mainTransfer)} admin=${renderTransferMap(adminTransfer)}"
        }

        _uiState.update {
            it.copy(
                mainFileTransfer = mainTransfer,
                adminFileTransfer = adminTransfer,
            )
        }
    }

    private fun renderTransferMap(map: Map<OdinId, RecipientFileStatus>?): String {
        if (map == null) return "<column hidden — caller is not author>"
        if (map.isEmpty()) return "<no recipients in transfer history>"
        return map.entries.joinToString(prefix = "{", postfix = "}") { (recipient, status) ->
            val s = when (status) {
                RecipientFileStatus.Ok -> "OK"
                is RecipientFileStatus.Problem -> "PROBLEM(${status.rawStatus})"
            }
            "$recipient=$s"
        }
    }

    /**
     * Logs the per-recipient status of both group files in a single multi-line entry.
     * Called before and after a heal so the diff is easy to read in homebase.log when
     * diagnosing "I clicked heal but recipient X still doesn't have it".
     */
    private fun logTransferSnapshot(label: String, conversationId: Uuid, state: GroupSettingsUiState) {
        Logger.i {
            "GroupSettings: $label snapshot conversationId=$conversationId " +
                    "main=${renderTransferMap(state.mainFileTransfer)} admin=${renderTransferMap(state.adminFileTransfer)}"
        }
    }

    private suspend fun fetchTransferIfAuthor(
        label: String,
        currentUser: OdinId,
        file: HomebaseFile?
    ): Map<OdinId, RecipientFileStatus>? {
        if (file == null) return null
        val author = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId
        if (author != currentUser) {
            Logger.d { "loadTransferHistory: skipping $label — caller=$currentUser is not author=$author" }
            return null
        }

        return try {
            val history = driveFileProvider.getTransferHistory(chatTargetDrive.alias, file.fileId)
            val results = history?.history?.results ?: emptyList()
            results.associate { entry: RecipientTransferHistoryEntry ->
                val odinId = OdinId(entry.recipient)
                // Match the per-message status mapping in
                // ChatDeliveryStatus.toChatDeliveryStatus: when the latest known
                // status is Delivered, that wins over `isInOutbox`. Right after
                // pressing "Heal group" we re-enqueue the file, so the server
                // briefly returns `latestTransferStatus = Delivered` (from the
                // prior successful send) AND `isInOutbox = true` (because we
                // just kicked it back into the outbox). Treating that combo as
                // a Problem produced a misleading "PROBLEM(Delivered)" log
                // entry and a transient red icon for ~180ms after each heal.
                // The recipient is fine — the file has been delivered before
                // and is currently being redistributed.
                val status = if (entry.latestTransferStatus == TransferStatus.Delivered) {
                    RecipientFileStatus.Ok
                } else {
                    RecipientFileStatus.Problem(
                        rawStatus = entry.latestTransferStatus,
                        detailRes = entry.latestTransferStatus.toErrorDetailRes()
                    )
                }
                odinId to status
            }
        } catch (e: Exception) {
            Logger.w(throwable = e) { "loadTransferHistory: getTransferHistory($label) failed for fileId=${file.fileId}" }
            null
        }
    }
}