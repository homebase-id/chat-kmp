package id.homebase.chat.newconversation

import androidx.compose.runtime.Immutable

@Immutable
data class NewConversationUiState(
    val text: String,

    val uiEvent: NewConversationUiEvent? = null,
)

sealed interface NewConversationUiEvent {
    object Back : NewConversationUiEvent
}
