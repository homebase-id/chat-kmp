package id.homebase.core.ui.screens.filesync.settings

data class FilesyncSettingsUiState(
    val iconVisible: Boolean = false,
)

sealed interface FilesyncSettingsUiAction {
    data class SetIconVisible(val visible: Boolean) : FilesyncSettingsUiAction
}
