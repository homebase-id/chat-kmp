package id.homebase.core.ui.screens.lists

data class ListsSettingsUiState(
    val iconVisible: Boolean = false,
)

sealed interface ListsSettingsUiAction {
    data object OpenListsClicked : ListsSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : ListsSettingsUiAction
}
