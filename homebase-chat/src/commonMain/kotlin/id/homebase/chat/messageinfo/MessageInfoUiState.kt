package id.homebase.chat.messageinfo

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatDeliveryStatus
import org.jetbrains.compose.resources.StringResource

data class RecipientStatusUiModel(
    val odinId: String,
    val displayName: String,
    val deliveryStatus: ChatDeliveryStatus,
    val errorDetailRes: StringResource? = null,
)

data class ReactionUiModel(
    val odinId: String,
    val displayName: String,
    val emoji: String,
)

@Immutable
data class MessageInfoUiState(
    val isLoading: Boolean = true,
    val isTransferHistoryLoading: Boolean = false,
    val isReactionsLoading: Boolean = false,
    val message: MessageUiModel? = null,
    val recipients: List<RecipientStatusUiModel> = emptyList(),
    val reactions: List<ReactionUiModel> = emptyList(),
    val ownerSession: OwnerSession? = null,
    val uiEvent: MessageInfoUiEvent? = null,
)

sealed interface MessageInfoUiEvent {
    object Back : MessageInfoUiEvent
}
