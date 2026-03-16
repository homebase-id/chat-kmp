package id.homebase.core.ui.screens.home

import kotlinx.io.files.Path

data class HomeUiState(
    val isLoading: Boolean = false,
    val appVersion: String,
    val appName: String = "Homebase Chat",
    val uiEvent: HomeUiEvent? = null
)

/** All possible user actions on Home screen. */
sealed interface HomeUiAction {
    data object ExportLogClicked : HomeUiAction
    data object ExamplesClicked : HomeUiAction
}

/** One-off events for side effects (navigation). */
sealed interface HomeUiEvent {
    data class ShareFile(val file: Path) : HomeUiEvent
    data class OpenFileBrowser(val file: Path) : HomeUiEvent
    data object NavigateToExample : HomeUiEvent
}
