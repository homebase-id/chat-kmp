package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.AutoConnectOutcome
import id.homebase.api.client.connections.ConnectionRequestHeader
import id.homebase.api.client.connections.ConnectionRequestResult
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToContactInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToConversationSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToGroupSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToMessageInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.config.AppConfig
import id.homebase.core.util.buildBlockUrl
import id.homebase.core.util.buildConnectToIdentityUrl
import id.homebase.resources.MR
import id.homebase.resources.auto_connect_blocked
import id.homebase.resources.auto_connect_duplicate_introductory_request
import id.homebase.resources.auto_connect_failed_generic
import id.homebase.resources.auto_connect_invalid_request
import id.homebase.resources.auto_connect_invalid_request_with_detail
import id.homebase.resources.auto_connect_outgoing_request_exists
import id.homebase.resources.auto_connect_pending_manual_approval
import id.homebase.resources.auto_connect_recipient_not_configured
import id.homebase.resources.auto_connect_recipient_rejected
import id.homebase.resources.auto_connect_recipient_requires_upgrade
import id.homebase.resources.auto_connect_recipient_unreachable
import id.homebase.resources.chat_conversation_deleted_confirmation
import id.homebase.resources.chat_conversation_deleting_in_progress
import id.homebase.resources.chat_conversation_leaving_and_deleting_in_progress
import id.homebase.resources.chat_error_accept_rejoin
import id.homebase.resources.chat_error_archive_conversation
import id.homebase.resources.chat_error_clear_conversation
import id.homebase.resources.chat_error_decline_rejoin
import id.homebase.resources.chat_error_delete_conversation
import id.homebase.resources.chat_error_no_ready_recipients
import id.homebase.resources.chat_error_toggle_pin
import id.homebase.resources.chat_error_unarchive_conversation
import id.homebase.resources.chat_group_introduce_everyone_status
import id.homebase.resources.chat_introduce_preflight_in_progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Handles conversation-lifecycle action arms (archive / pin / clear / delete /
 * leave-and-delete / rejoin, introduce, connect identities, auto-connect,
 * sheet routing, BlockUser / ReportContent) extracted from
 * `ConversationListViewModel.onAction`.
 *
 * Behavior is byte-identical to the previous in-VM implementation; the move
 * is purely structural — see PR 4b-1 in `lucky-chasing-valley.md` for context.
 */
internal class ConversationLifecycleHandler(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ConversationListUiState>,
    private val messagesUiState: MutableStateFlow<MessageListUiState>,
    private val conversationService: ConversationService,
    private val conversationStream: ConversationStream,
    private val connectionRequestService: ConnectionRequestService,
    private val credentialsManager: CredentialsManager,
    private val sendEvent: (ConversationListUiEvent) -> Unit,
    private val dispatch: (ConversationListUiAction) -> Unit,
) {

    fun handleTogglePinConversation(action: ConversationListUiAction.TogglePinConversation) {
        scope.launch {
            try {
                val conversation =
                    conversationService.getConversation(action.conversationId)
                        ?: return@launch
                if (conversation.isPinned) {
                    conversationService.unpinConversation(action.conversationId)
                } else {
                    conversationService.pinConversation(action.conversationId)
                }
            } catch (e: Exception) {
                sendEvent(
                    ShowErrorMessage(
                        MR.string.chat_error_toggle_pin,
                        e.message ?: "",
                    )
                )
            }
        }
    }

    fun handleShowContactInfo(action: ConversationListUiAction.ShowContactInfo) {
        if (action.odinId == uiState.value.ownerSession?.odinId?.domainName) {
            // Show self-conversation settings as owner profile
            uiState.update {
                it.copy(
                    uiEvent = NavigateToConversationSettings(
                        ChatProtocol.ConversationWithYourselfId.toString()
                    )
                )
            }
        } else {
            uiState.update {
                it.copy(
                    uiEvent = NavigateToContactInfo(action.odinId)
                )
            }
        }
    }

    fun handleShowMessageInfo(action: ConversationListUiAction.ShowMessageInfo) {
        uiState.update {
            it.copy(
                uiEvent = NavigateToMessageInfo((action.message))
            )
        }
    }

    fun handleDismissSheet() {
        messagesUiState.update { it.copy(uiSheet = null) }
    }

    /* Group options */

    fun handleConnectIdentities(action: ConversationListUiAction.ConnectIdentities) {
        messagesUiState.update {
            it.copy(
                uiSheet = MessageListUiSheet.ConnectIdentities(
                    action.identities
                )
            )
        }
    }

    fun handleConnectToIdentity(action: ConversationListUiAction.ConnectToIdentity) {
        uiState.value.ownerSession?.odinId?.let { currentUser ->
            val url = currentUser.buildConnectToIdentityUrl(action.odinId)
            uiState.update { it.copy(uiEvent = ConversationListUiEvent.OpenUrl(url)) }
        }
    }

    fun handleAutoConnect(action: ConversationListUiAction.AutoConnect) {
        autoConnect(action.odinId)
    }

    fun handleOpenConnectionRequestInOwnerConsole(action: ConversationListUiAction.OpenConnectionRequestInOwnerConsole) {
        uiState.value.ownerSession?.odinId?.let { currentUser ->
            val url =
                "https://${currentUser.domainName}/owner/connections/${action.odinId.domainName}"
            uiState.update { it.copy(uiEvent = ConversationListUiEvent.OpenUrl(url)) }
        }
    }

    fun handleOpenSendConnectionRequestDialog(action: ConversationListUiAction.OpenSendConnectionRequestDialog) {
        uiState.update {
            it.copy(
                uiEvent = ConversationListUiEvent.OpenSendConnectionRequestDialog(
                    action.odinId
                )
            )
        }
    }

    /* Conversation options */
    fun handleShowConversationSettings(action: ConversationListUiAction.ShowConversationSettings) {
        val conversation = action.conversation
        // A 1:1 conversation's "info" is the contact — open the full contact-detail screen
        // (keyed by the peer's odinId) instead of a separate conversation-overview screen.
        // Groups go to group settings; note-to-self has no contact, so it keeps the settings screen.
        // `participants` is the raw recipient list and includes the owner, so the peer is the
        // first participant that is NOT the current user — otherwise tapping the header could
        // open the owner's own contact detail instead of the person they're chatting with.
        val ownerOdinId = uiState.value.ownerSession?.odinId
        val peerOdinId = conversation.participants.firstOrNull { it != ownerOdinId }?.domainName
        if (conversation.isGroupConversation) {
            uiState.update {
                it.copy(
                    uiEvent = NavigateToGroupSettings(
                        (action.conversation.id.toString())
                    )
                )
            }
        } else if (!conversation.isWithSelf && peerOdinId != null) {
            uiState.update { it.copy(uiEvent = NavigateToContactInfo(peerOdinId)) }
        } else {
            uiState.update {
                it.copy(
                    uiEvent = NavigateToConversationSettings(
                        (action.conversation.id.toString())
                    )
                )
            }
        }
    }

    fun handleIntroduceEveryone(action: ConversationListUiAction.IntroduceEveryone) {
        introduceEveryone(action.conversationId)
    }

    fun handleIntroduceSendAnyway(action: ConversationListUiAction.IntroduceSendAnyway) {
        scope.launch {
            uiState.update { it.copy(uiDialog = null) }
            conversationService.introduceEveryone(action.conversationId, action.message)
            sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
        }
    }

    fun handleIntroduceSendReadyOnly(action: ConversationListUiAction.IntroduceSendReadyOnly) {
        scope.launch {
            uiState.update { it.copy(uiDialog = null) }
            if (action.readyRecipients.isEmpty()) {
                // No-op — the dialog should not have allowed this, but be defensive.
                sendEvent(ShowErrorMessage(MR.string.chat_error_no_ready_recipients))
                return@launch
            }
            conversationService.introduceRecipients(
                conversationId = action.conversationId,
                recipients = action.readyRecipients,
                message = action.message,
            )
            sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
        }
    }

    /**
     * Re-run preflight from the dialog's "Check again" affordance and swap in
     * the fresh result. Three outcomes:
     *  - preflight itself failed (null): leave the dialog exactly as it was, so
     *    a network blip doesn't erase what the user was reading.
     *  - everything is Ready now: send, same as if the first check had passed.
     *  - still mixed: replace the dialog's result so the list re-renders with
     *    the new per-recipient reasons.
     */
    fun handleIntroduceRetryPreflight(action: ConversationListUiAction.IntroduceRetryPreflight) {
        scope.launch {
            uiState.update {
                it.copy(inFlightOperationLabel = MR.string.chat_introduce_preflight_in_progress)
            }
            val preflight = conversationService.previewIntroduceEveryone(action.conversationId)
            uiState.update { it.copy(inFlightOperationLabel = null) }
            if (preflight == null) return@launch
            if (preflight.allReady) {
                uiState.update { it.copy(uiDialog = null) }
                conversationService.introduceEveryone(action.conversationId, action.message)
                sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
                return@launch
            }
            uiState.update { state ->
                val dialog = state.uiDialog as? ConversationListUiDialog.IntroducePreflight
                    ?: return@update state
                state.copy(uiDialog = dialog.copy(result = preflight))
            }
        }
    }

    fun handleIntroduceCancel() {
        uiState.update { it.copy(uiDialog = null) }
    }

    fun handleArchiveConversation(action: ConversationListUiAction.ArchiveConversation) {
        scope.launch {
            try {
                conversationService.archiveConversation(action.conversationId)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to archive conversation: ${e.message}"
                }
                sendEvent(ShowErrorMessage(MR.string.chat_error_archive_conversation, e.message ?: ""))
            }
        }
    }

    fun handleUnarchiveConversation(action: ConversationListUiAction.UnarchiveConversation) {
        scope.launch {
            try {
                conversationService.unarchiveConversation(action.conversationId)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to unarchive conversation: ${e.message}"
                }
                sendEvent(ShowErrorMessage(MR.string.chat_error_unarchive_conversation, e.message ?: ""))
            }
        }
    }

    fun handleShowArchivedMessagesClicked() {
        uiState.update { it.copy(showArchived = true) }
    }

    fun handleArchiveBackClicked() {
        uiState.update { it.copy(showArchived = false) }
    }

    fun handleClearConversation(action: ConversationListUiAction.ClearConversation) {
        scope.launch {
            try {
                conversationService.clearConversation(action.conversationId)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to clear conversation: ${e.message}"
                }
                sendEvent(ShowErrorMessage(MR.string.chat_error_clear_conversation, e.message ?: ""))
            }
        }
    }

    fun handleDeleteConversation(action: ConversationListUiAction.DeleteConversation) {
        uiState.update {
            it.copy(uiDialog = ConversationListUiDialog.DeleteConversation(action.conversationId))
        }
    }

    fun handleConfirmDeleteConversation(action: ConversationListUiAction.ConfirmDeleteConversation) {
        scope.launch {
            runDeleteConversationFlow(
                conversationId = action.conversationId,
                leaveFirst = false,
                overlayLabel = MR.string.chat_conversation_deleting_in_progress,
            )
        }
    }

    fun handleConfirmLeaveAndDeleteConversation(action: ConversationListUiAction.ConfirmLeaveAndDeleteConversation) {
        scope.launch {
            runDeleteConversationFlow(
                conversationId = action.conversationId,
                leaveFirst = true,
                overlayLabel = MR.string.chat_conversation_leaving_and_deleting_in_progress,
            )
        }
    }

    fun handleCloseDetailPaneRequestConsumed() {
        uiState.update { it.copy(closeDetailPaneRequest = null) }
    }

    fun handleAcceptRejoin(action: ConversationListUiAction.AcceptRejoin) {
        scope.launch {
            try {
                conversationService.acceptRejoin(action.conversationId)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to accept rejoin: ${e.message}"
                }
                sendEvent(ShowErrorMessage(MR.string.chat_error_accept_rejoin, e.message ?: ""))
            }
        }
    }

    fun handleDeclineRejoin(action: ConversationListUiAction.DeclineRejoin) {
        scope.launch {
            try {
                conversationService.declineRejoin(action.conversationId)
                conversationStream.onConversationLeft(action.conversationId)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to decline rejoin: ${e.message}"
                }
                sendEvent(ShowErrorMessage(MR.string.chat_error_decline_rejoin, e.message ?: ""))
            }
        }
    }

    fun handleBlockUser(action: ConversationListUiAction.BlockUser) {
        uiState.value.ownerSession?.odinId?.let { currentUser ->
            val url = currentUser.buildBlockUrl(action.authorOdinId)
            sendEvent(ConversationListUiEvent.OpenUrl(url))
        }
    }

    fun handleReportContent() {
        sendEvent(ConversationListUiEvent.OpenUrl(AppConfig.REPORT_CONTENT_URL))
    }

    private fun introduceEveryone(conversationId: Uuid) {
        scope.launch {
            val defaultMessage =
                "${uiState.value.ownerSession?.displayName ?: "Unknown"} has added you to group chat"
            // Show the in-flight overlay during preflight — without this the user
            // sees nothing for ~hundreds of ms and the app feels stuck.
            uiState.update { it.copy(inFlightOperationLabel = MR.string.chat_introduce_preflight_in_progress) }
            // Best-effort preflight: if every recipient is Ready, proceed silently
            // as before; if any recipient is non-Ready, surface a dialog so the
            // user can choose Send anyway / Skip and send to the rest / Cancel.
            // If preflight itself fails (returns null), fall through to the
            // existing send-everyone path — preflight is advisory, not enforcing.
            val preflight = conversationService.previewIntroduceEveryone(conversationId)
            // Always clear the overlay before doing anything follow-up; the dialog
            // (if shown) draws on top of the conversation view, not the overlay.
            uiState.update { it.copy(inFlightOperationLabel = null) }
            if (preflight == null || preflight.allReady) {
                conversationService.introduceEveryone(conversationId, defaultMessage)
                sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
                return@launch
            }
            // Some recipients are not ready; let the user decide.
            uiState.update {
                it.copy(
                    uiDialog = ConversationListUiDialog.IntroducePreflight(
                        conversationId = conversationId,
                        message = defaultMessage,
                        result = preflight,
                    )
                )
            }
        }
    }

    /**
     * Combined delete (and optional leave-first) flow. Drives the in-flight overlay
     * via [ConversationListUiState.inFlightOperationLabel], runs the service ops,
     * then reconciles UI state (drops the row from the in-memory list, pops the
     * detail pane if the deleted conversation was open, fires the snackbar).
     *
     * @param leaveFirst when true, calls [ConversationService.leaveGroup] before
     *                   the delete. Required when the user is still an active
     *                   member of a group conversation; the service-side delete
     *                   guard otherwise rejects with IllegalStateException.
     */
    private suspend fun runDeleteConversationFlow(
        conversationId: Uuid,
        leaveFirst: Boolean,
        overlayLabel: org.jetbrains.compose.resources.StringResource,
    ) {
        uiState.update { it.copy(inFlightOperationLabel = overlayLabel) }
        try {
            if (leaveFirst) {
                // Mirror GroupSettingsViewModel.LeaveGroupConfirm logic so a sole-admin
                // with no reachable non-admin still goes through the local-only branch.
                val enriched = uiState.value.activeConversations
                    .find { it.conversation.id == conversationId }
                val conversation = enriched?.conversation
                val currentUser = credentialsManager.requireActiveCredentials().domain
                val isSoleAdmin = conversation != null
                        && conversation.isCurrentUserAdmin(currentUser)
                        && conversation.admins.size == 1
                val hasReachableNonAdmin = enriched != null && enriched.participants.any {
                    it.connectionState ==
                            id.homebase.chat.services.convo.contact.ContactConnectionState.Connected
                            && conversation?.isCurrentUserAdmin(it.odinId) == false
                }
                val forceLocalOnly = isSoleAdmin && !hasReachableNonAdmin
                conversationService.leaveGroup(
                    conversationId = conversationId,
                    forceLocalOnly = forceLocalOnly,
                )
            }
            conversationService.deleteConversation(conversationId)

            // Drop the row from the in-memory list immediately and tell the stream
            // to keep it filtered across reloads — see ConversationStream.deletedIds.
            conversationStream.onConversationDeleted(conversationId)

            val close = uiState.value.selectedConversationId == conversationId
            uiState.update {
                it.copy(
                    inFlightOperationLabel = null,
                    selectedConversationId = if (close) null else it.selectedConversationId,
                    closeDetailPaneRequest = if (close) conversationId else it.closeDetailPaneRequest,
                )
            }
            if (close) {
                // ClearSelection also resets messages and stops the per-convo job.
                dispatch(ConversationListUiAction.ClearSelection)
            }
            sendEvent(ShowInfoMessage(MR.string.chat_conversation_deleted_confirmation))
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = "ConversationListViewModel") {
                "Failed to delete conversation (leaveFirst=$leaveFirst): ${e.message}"
            }
            uiState.update { it.copy(inFlightOperationLabel = null) }
            sendEvent(ShowErrorMessage(MR.string.chat_error_delete_conversation, e.message ?: ""))
        }
    }


    private fun autoConnect(recipient: OdinId) {
        // Only operate while the Connect-identities sheet is open; otherwise there is no
        // row UI to reflect the state change against.
        val openSheet = messagesUiState.value.uiSheet as? MessageListUiSheet.ConnectIdentities
            ?: return
        if (openSheet.autoConnectStates[recipient] == AutoConnectRowState.Connecting) return

        updateConnectSheetRow(recipient, AutoConnectRowState.Connecting)

        scope.launch {
            val header = ConnectionRequestHeader(
                id = Uuid.random(),
                recipient = recipient,
                message = null,
                circleIds = null,
                introducerOdinId = null,
                connectionRequestOrigin = null,
            )
            try {
                val result = connectionRequestService.autoConnect(header)
                val succeeded = when (result.outcome) {
                    AutoConnectOutcome.Connected,
                    AutoConnectOutcome.AcceptedFromExistingIncoming,
                    AutoConnectOutcome.AlreadyConnected -> true
                    else -> false
                }
                if (succeeded) {
                    updateConnectSheetRow(recipient, AutoConnectRowState.Succeeded)
                } else {
                    updateConnectSheetRow(recipient, failedOutcomeRowState(result, recipient))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientException) {
                Logger.w(e) { "autoConnect($recipient) ClientException code=${e.errorCode}" }
                updateConnectSheetRow(recipient, clientExceptionRowState(e, recipient))
            } catch (e: Exception) {
                Logger.e(e) { "autoConnect($recipient) failed: ${e::class.simpleName}: ${e.message}" }
                updateConnectSheetRow(
                    recipient,
                    AutoConnectRowState.Failed(
                        MR.string.auto_connect_recipient_unreachable,
                        listOf(recipient.domainName),
                    ),
                )
            }
        }
    }

    private fun updateConnectSheetRow(recipient: OdinId, newState: AutoConnectRowState?) {
        messagesUiState.update { state ->
            val sheet = state.uiSheet as? MessageListUiSheet.ConnectIdentities
                ?: return@update state
            val next = if (newState == null) {
                sheet.autoConnectStates - recipient
            } else {
                sheet.autoConnectStates + (recipient to newState)
            }
            state.copy(uiSheet = sheet.copy(autoConnectStates = next))
        }
    }

    /** Translate a server-side 400 ClientException into the same row state we'd show for the
     *  corresponding in-band AutoConnectOutcome. Keeps the UX consistent whether the server
     *  returns a typed outcome or bubbles the legacy error-code path. */
    private fun clientExceptionRowState(
        e: ClientException,
        recipient: OdinId,
    ): AutoConnectRowState.Failed {
        val who = recipient.domainName
        return when (e.errorCode) {
            OdinClientErrorCode.ConnectionRequestAlreadySent ->
                AutoConnectRowState.Failed(
                    MR.string.auto_connect_outgoing_request_exists,
                    listOf(who),
                )
            OdinClientErrorCode.BlockedConnection ->
                AutoConnectRowState.Failed(MR.string.auto_connect_blocked, listOf(who))
            OdinClientErrorCode.CannotSendConnectionRequestToValidConnection ->
                AutoConnectRowState.Failed(MR.string.auto_connect_failed_generic)
            OdinClientErrorCode.ConnectionRequestToYourself ->
                AutoConnectRowState.Failed(MR.string.auto_connect_invalid_request)
            else -> AutoConnectRowState.Failed(
                MR.string.auto_connect_invalid_request_with_detail,
                listOf(e.message ?: "Failed"),
            )
        }
    }

    private fun failedOutcomeRowState(
        result: ConnectionRequestResult,
        recipient: OdinId,
    ): AutoConnectRowState.Failed {
        val who = recipient.domainName
        return when (result.outcome) {
            AutoConnectOutcome.PendingManualApproval ->
                AutoConnectRowState.Failed(MR.string.auto_connect_pending_manual_approval, listOf(who))
            AutoConnectOutcome.Blocked ->
                AutoConnectRowState.Failed(MR.string.auto_connect_blocked, listOf(who))
            AutoConnectOutcome.OutgoingRequestAlreadyExists ->
                AutoConnectRowState.Failed(MR.string.auto_connect_outgoing_request_exists, listOf(who))
            AutoConnectOutcome.DuplicateIntroductoryRequest ->
                AutoConnectRowState.Failed(MR.string.auto_connect_duplicate_introductory_request, listOf(who))
            AutoConnectOutcome.RecipientUnreachable ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_unreachable, listOf(who))
            AutoConnectOutcome.RecipientRejected ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_rejected, listOf(who))
            AutoConnectOutcome.RecipientIdentityNotConfigured ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_not_configured, listOf(who))
            AutoConnectOutcome.RecipientRequiresUpgrade ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_requires_upgrade, listOf(who))
            AutoConnectOutcome.InvalidRequest ->
                result.detail?.let {
                    AutoConnectRowState.Failed(
                        MR.string.auto_connect_invalid_request_with_detail,
                        listOf(it),
                    )
                } ?: AutoConnectRowState.Failed(MR.string.auto_connect_invalid_request)
            AutoConnectOutcome.Failed,
            AutoConnectOutcome.Unknown ->
                AutoConnectRowState.Failed(MR.string.auto_connect_failed_generic)
            // Success outcomes never route here, but keep `when` exhaustive.
            AutoConnectOutcome.Connected,
            AutoConnectOutcome.AcceptedFromExistingIncoming,
            AutoConnectOutcome.AlreadyConnected ->
                AutoConnectRowState.Failed(MR.string.auto_connect_failed_generic)
        }
    }
}
