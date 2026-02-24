package id.homebase.chat.contactinfo

import androidx.compose.runtime.Immutable

@Immutable
data class ContactInfoUiState(
    val text: String,

    val uiEvent: ContactInfoUiEvent? = null,
)

sealed interface ContactInfoUiEvent {
    object Back : ContactInfoUiEvent
}
