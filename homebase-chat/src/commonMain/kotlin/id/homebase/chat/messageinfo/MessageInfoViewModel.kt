package id.homebase.chat.messageinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.toChatDeliveryStatus
import id.homebase.chat.services.toErrorDetailRes
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class MessageInfoViewModel(
    savedStateHandle: SavedStateHandle,
    private val chatMessageStream: ChatMessageStream,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val driveFileProvider: DriveFileProvider,
    private val contactService: ContactService,
    private val chatMessageActionService: ChatMessageActionService,
) : ViewModel() {

    val messageInfo = savedStateHandle.toRoute<Route.MessageInfo>()
    private val _uiState = MutableStateFlow(MessageInfoUiState())
    val uiState: StateFlow<MessageInfoUiState> = _uiState.asStateFlow()

    // Guards the one-shot server existence check so a session arriving after the
    // message (or vice-versa) can't kick it twice.
    private var serverCheckStarted = false

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
                recomputeSendStatus()
                startServerCheckIfNeeded()
            }
        }
        viewModelScope.launch {
            try {
                val message = chatMessageStream.getMessage(Uuid.parse(messageInfo.messageId))
                _uiState.update {
                    it.copy(
                        message = message,
                        isLoading = false,
                        isTransferHistoryLoading = true,
                        isReactionsLoading = true,
                    )
                }
                recomputeSendStatus()
                startServerCheckIfNeeded()

                // Load transfer history
                viewModelScope.launch {
                    try {
                        val transferHistory =
                            driveFileProvider.getTransferHistory(
                                chatTargetDrive.alias,
                                message?.fileId ?: return@launch
                            )
                        val recipients = transferHistory?.history?.results?.map { entry ->
                            val odinId = OdinId(entry.recipient)
                            val displayName = contactService.resolveByOdinId(odinId)?.name
                                ?: odinId.domainName
                            RecipientStatusUiModel(
                                odinId = entry.recipient,
                                displayName = displayName,
                                deliveryStatus = entry.toChatDeliveryStatus(),
                                errorDetailRes = entry.latestTransferStatus.toErrorDetailRes(),
                            )
                        } ?: emptyList()
                        _uiState.update {
                            it.copy(
                                recipients = recipients,
                                isTransferHistoryLoading = false,
                                transferHistoryFailed = false,
                            )
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                isTransferHistoryLoading = false,
                                transferHistoryFailed = true,
                            )
                        }
                    }
                }

                // Load reactions
                viewModelScope.launch {
                    try {
                        val messageId = message?.id ?: return@launch
                        val rawReactions = chatMessageActionService.getReactions(messageId)
                        val reactions = rawReactions.map { reaction ->
                            val displayName = contactService.resolveByOdinId(reaction.odinId)?.name
                                ?: reaction.odinId.domainName
                            ReactionUiModel(
                                odinId = reaction.odinId.domainName,
                                displayName = displayName,
                                emoji = reaction.emoji,
                            )
                        }
                        _uiState.update {
                            it.copy(
                                reactions = reactions,
                                isReactionsLoading = false,
                            )
                        }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(isReactionsLoading = false) }
                    }
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isTransferHistoryLoading = false,
                        isReactionsLoading = false,
                    )
                }
            }
        }
    }

    /**
     * Recompute the outgoing send-status fields from the current message, owner
     * session, and server-presence. Cheap and idempotent — safe to call whenever
     * any of those inputs change (message load, session arrival, server check).
     */
    private fun recomputeSendStatus() {
        _uiState.update { state ->
            val message = state.message ?: return@update state
            val isOwn = message.isAuthoredBy(state.ownerSession?.odinId)
            val result = deriveSendStatus(
                isOwnMessage = isOwn,
                isPendingSend = message.isPendingSend,
                isFailedSend = message.isFailedSend,
                deliveryFailed = message.messageAppData.deliveryStatus == ChatDeliveryStatus.Failed.value,
                serverPresence = state.serverPresence,
            )
            state.copy(
                isOwnMessage = isOwn,
                sendState = result.sendState,
                retryMode = result.retryMode,
                canRetry = result.canRetry,
            )
        }
    }

    /**
     * For an own message that is still pending or has failed to send, confirm
     * by uniqueId whether the file actually exists on the server. This resolves
     * the "opened info before the send settled" race (pending + Present ⇒ Sent)
     * and decides whether a future retry is a Create or an Update. A network
     * failure leaves presence Unknown — never throws into the UI.
     */
    private fun startServerCheckIfNeeded() {
        if (serverCheckStarted) return
        val state = _uiState.value
        val message = state.message ?: return
        val isOwn = message.isAuthoredBy(state.ownerSession?.odinId)
        if (!isOwn) return
        if (!message.isPendingSend && !message.isFailedSend) return

        serverCheckStarted = true
        viewModelScope.launch {
            _uiState.update { it.copy(isServerCheckLoading = true) }
            val presence = resolveServerPresence(
                existsOnServer = {
                    driveFileProvider.getFileHeaderByUid(chatTargetDrive.alias, message.id) != null
                },
                onError = { e ->
                    Logger.w(e) { "MessageInfo: server presence check failed for uniqueId=${message.id}" }
                },
            )
            _uiState.update { it.copy(serverPresence = presence, isServerCheckLoading = false) }
            recomputeSendStatus()
        }
    }

    fun onUiAction(action: MessageInfoUiAction) {
        when (action) {
            is MessageInfoUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = MessageInfoUiEvent.Back) }
            // Stub: Layer 1 will re-enqueue the outbox item. retryMode tells it
            // whether to re-upload a never-landed file (Create) or re-push an
            // edit against an existing server file (Update).
            is MessageInfoUiAction.RetryClicked -> {
                val state = _uiState.value
                Logger.i {
                    "MessageInfo: retry requested (stub) uniqueId=${state.message?.id} retryMode=${state.retryMode}"
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}
