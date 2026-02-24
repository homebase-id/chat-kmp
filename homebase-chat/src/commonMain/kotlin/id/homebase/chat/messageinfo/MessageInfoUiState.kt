package id.homebase.chat.messageinfo

import androidx.compose.runtime.Immutable

@Immutable
data class MessageInfoUiState(
    val text: String,

    val uiEvent: MessageInfoUiEvent? = null,
)

sealed interface MessageInfoUiEvent {
    object Back : MessageInfoUiEvent
}
