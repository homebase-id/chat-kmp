package id.homebase.chat.contactinfo

import androidx.compose.runtime.Immutable
import id.homebase.chat.data.ContactUiModel

@Immutable
data class ContactInfoUiState(
    val isLoading: Boolean = true,
    val contact: ContactUiModel? = null,
    val uiEvent: ContactInfoUiEvent? = null,
)

sealed interface ContactInfoUiEvent {
    object Back : ContactInfoUiEvent
}
