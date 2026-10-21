package id.homebase.core.ui.screens.account

import id.homebase.api.client.storage.StorageUsage

data class AccountSettingsUiState(
    /** The identity's plan and storage, from GET /api/v2/plan; null when the server has no hosting plans. */
    val usage: StorageUsage? = null,
    val isFull: Boolean = false,
    val isLoading: Boolean = true,
    val uiEvent: AccountSettingsUiEvent? = null,
)

sealed interface AccountSettingsUiAction {
    data object SeeWhatEachAppStoresClicked : AccountSettingsUiAction
    data object HowToUpgradeClicked : AccountSettingsUiAction
}

sealed interface AccountSettingsUiEvent {
    data class OpenUrl(val url: String) : AccountSettingsUiEvent
}
