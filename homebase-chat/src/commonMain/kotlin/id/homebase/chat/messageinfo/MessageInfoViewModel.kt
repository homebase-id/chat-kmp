package id.homebase.chat.messageinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
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

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
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
                            )
                        }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(isTransferHistoryLoading = false) }
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

    fun onUiAction(action: MessageInfoUiAction) {
        when (action) {
            is MessageInfoUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = MessageInfoUiEvent.Back) }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}
