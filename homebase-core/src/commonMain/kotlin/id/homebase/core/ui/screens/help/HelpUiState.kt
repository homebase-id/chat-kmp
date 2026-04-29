package id.homebase.core.ui.screens.help

import kotlinx.io.files.Path

data class HelpUiState(
    val appVersion: String,
    val isUpdateAvailable: Boolean = false,
    val isUpdateSupported: Boolean = true,
    val isCheckingForUpdate: Boolean = true,
    val errorCollectionEnabled: Boolean,
    val showDeveloperMenu: Boolean,
    val uiEvent: HelpUiEvent? = null,
)

sealed interface HelpUiAction {
    data object SupportCenterClicked : HelpUiAction
    data object ContactUsClicked : HelpUiAction
    data object SubmitDebugLogClicked : HelpUiAction
    data object TermsPrivacyClicked : HelpUiAction
    data object ToggleErrorCollection : HelpUiAction
    data object DeveloperClicked : HelpUiAction
    data object DeveloperMenu : HelpUiAction
    data object DownloadUpdateClicked : HelpUiAction
    data object CheckForUpdatedClicked : HelpUiAction
}

sealed interface HelpUiEvent {
    data object OpenDeveloperMenu : HelpUiEvent
    data class OpenUrl(val url: String) : HelpUiEvent
    data class ShareFile(val filePath: Path) : HelpUiEvent
    data class ShowError(val message: String) : HelpUiEvent
}
