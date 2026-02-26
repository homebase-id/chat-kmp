package id.homebase.chat.groupsettings

import androidx.compose.runtime.Immutable

@Immutable
data class GroupSettingsUiState(
    val text: String,

    val uiEvent: GroupSettingsUiEvent? = null,
)

sealed interface GroupSettingsUiEvent {
    object Back : GroupSettingsUiEvent
}
