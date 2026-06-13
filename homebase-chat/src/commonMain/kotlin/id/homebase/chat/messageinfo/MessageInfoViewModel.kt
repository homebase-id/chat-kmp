package id.homebase.chat.messageinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ResendOutcome
import id.homebase.chat.services.toChatDeliveryStatus
import id.homebase.chat.services.toErrorDetailRes
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.ui.navigation.Route
import id.homebase.resources.MR
import id.homebase.resources.msg_retry_failed
import id.homebase.resources.msg_retry_media_unavailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

class MessageInfoViewModel(
    savedStateHandle: SavedStateHandle,
    private val chatMessageStream: ChatMessageStream,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val driveFileProvider: DriveFileProvider,
    private val contactService: ContactService,
    private val chatMessageActionService: ChatMessageActionService,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val outboxSync: OutboxSync,
) : ViewModel() {

    val messageInfo = savedStateHandle.toRoute<Route.MessageInfo>()
    private val _uiState = MutableStateFlow(MessageInfoUiState())
    val uiState: StateFlow<MessageInfoUiState> = _uiState.asStateFlow()

    // Guards the one-shot server existence check so a session arriving after the
    // message (or vice-versa) can't kick it twice.
    private var serverCheckStarted = false

    // Same one-shot guard for the outbox-row read (Queued / Try-now display).
    private var outboxReadStarted = false

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
                recomputeSendStatus()
                startServerCheckIfNeeded()
                startOutboxReadIfNeeded()
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
                startOutboxReadIfNeeded()

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
            // The outbox row (when present) overrides the tag/server view —
            // a queued item is "still sending", whatever the tags claim.
            val row = state.outboxRow.takeIf { isOwn }
            val overlay = retryUiState(
                base = result,
                inOutbox = row != null,
                isCheckedOut = row?.isCheckedOut == true,
                nextRunTimeRaw = row?.nextRunTime,
                nowMs = UnixTimeUtc.now().milliseconds,
            )
            state.copy(
                isOwnMessage = isOwn,
                sendState = overlay.sendState,
                retryMode = overlay.retryMode,
                canRetry = overlay.canRetry,
                canTryNow = overlay.canTryNow,
                nextAttemptInMinutes = overlay.nextAttemptInMinutes,
                // Diagnostic detail for a queued/backed-off message.
                attemptNumber = row?.takeIf { it.checkOutCount >= 1 }?.let { (it.checkOutCount + 1).toInt() },
                nextAttemptAtMs = row?.let { nextAttemptDeadlineMs(it.isCheckedOut, it.nextRunTime) },
                waitingOnEarlierMessage = row?.dependencyPending == true,
                stuckReason = row?.lastError,
            )
        }
    }

    /**
     * One-shot read of the message's pending outbox row at open (own message
     * still pending or failed only). A row means the send is queued/backed-off:
     * the screen shows Queued + "next attempt in ~N min" + Try now instead of
     * the bare Sending spinner or a Retry that would be a no-op.
     */
    private fun startOutboxReadIfNeeded() {
        if (outboxReadStarted) return
        val state = _uiState.value
        val message = state.message ?: return
        if (!message.isAuthoredBy(state.ownerSession?.odinId)) return
        if (!message.isPendingSend && !message.isFailedSend) return

        outboxReadStarted = true
        viewModelScope.launch { refreshOutboxRow() }
    }

    private suspend fun refreshOutboxRow() {
        val message = _uiState.value.message ?: return
        val row = outboxSync.pendingRowSnapshot(chatTargetDrive.alias, message.id)
        _uiState.update { it.copy(outboxRow = row) }
        recomputeSendStatus()
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
            is MessageInfoUiAction.RetryClicked -> retryFailedSend()
            is MessageInfoUiAction.TryNowClicked -> tryNow()
        }
    }

    /**
     * Reset the queued item's backoff so it runs immediately
     * ([OutboxSync.runNow]). Optimistically collapses the wait to "shortly";
     * one delayed re-read then settles the row's real state (usually gone →
     * back to the plain tag-derived status). No polling loop — the screen is
     * a snapshot, not a live monitor.
     */
    private fun tryNow() {
        val state = _uiState.value
        val message = state.message ?: return
        if (!state.canTryNow) return
        _uiState.update { it.copy(canTryNow = false, nextAttemptInMinutes = 0L) }
        viewModelScope.launch {
            outboxSync.runNow(chatTargetDrive.alias, message.id)
            delay(POST_TRY_NOW_REFRESH_MS)
            refreshOutboxRow()
        }
    }

    /**
     * Re-enqueue the failed send via [ChatMessageSenderService.resendMessage]
     * (create vs update is decided there by a fresh server check). Optimistically
     * flips the status row to *Sending* and hides the button; on a refused retry
     * the Failed state is restored from the (unchanged) local tags and the reason
     * surfaces as a one-time snackbar event.
     */
    private fun retryFailedSend() {
        val state = _uiState.value
        val message = state.message ?: return
        if (!state.canRetry) return
        _uiState.update {
            it.copy(sendState = OutgoingSendState.Sending, canRetry = false, retryMode = null)
        }
        viewModelScope.launch {
            when (val outcome = chatMessageSenderService.resendMessage(message.id)) {
                ResendOutcome.EnqueuedCreate,
                ResendOutcome.EnqueuedUpdate,
                ResendOutcome.AlreadyQueued -> {
                    Logger.i { "MessageInfo: retry enqueued ($outcome) uniqueId=${message.id}" }
                    // Refresh so the swapped tags (failed→pending) and the fresh
                    // outbox row drive any later recompute instead of the stale
                    // Failed snapshot.
                    val refreshed = chatMessageStream.getMessage(message.id)
                    _uiState.update { it.copy(message = refreshed ?: it.message) }
                    refreshOutboxRow()
                }

                ResendOutcome.UnrecoverableMedia ->
                    revertRetryWithError(MR.string.msg_retry_media_unavailable)

                is ResendOutcome.Failed -> {
                    Logger.w(outcome.error) { "MessageInfo: retry failed uniqueId=${message.id}" }
                    revertRetryWithError(MR.string.msg_retry_failed)
                }
            }
        }
    }

    private fun revertRetryWithError(messageRes: StringResource) {
        // Tags are unchanged (still failed) — recompute restores Failed + canRetry.
        recomputeSendStatus()
        _uiState.update { it.copy(uiEvent = MessageInfoUiEvent.RetryFailed(messageRes)) }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private companion object {
        /** Single post-Try-now settle re-read — long enough for a fast send to
         *  drain the row, short enough to feel responsive. */
        const val POST_TRY_NOW_REFRESH_MS = 3_000L
    }
}
