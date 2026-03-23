package id.homebase.chat.messageinfo

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.drives.files.TransferHistory
import id.homebase.chat.data.MessageUiModel

@Immutable
data class MessageInfoUiState(
    val isLoading: Boolean = true,
    val isTransferHistoryLoading: Boolean = false,
    val message: MessageUiModel? = null,
    val transferHistory: TransferHistory? = null,
    val ownerSession: OwnerSession? = null,
    val uiEvent: MessageInfoUiEvent? = null,
)

sealed interface MessageInfoUiEvent {
    object Back : MessageInfoUiEvent
}
