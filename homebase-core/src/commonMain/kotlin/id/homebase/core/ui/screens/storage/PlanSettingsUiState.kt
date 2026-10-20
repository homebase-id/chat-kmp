package id.homebase.core.ui.screens.storage

import id.homebase.api.client.storage.StorageUsage

data class PlanSettingsUiState(
    /** The identity's plan and storage, from GET /api/v2/plan; null when the server has no hosting plans. */
    val usage: StorageUsage? = null,
    val isFull: Boolean = false,
    val isLoading: Boolean = true,
    val uiEvent: PlanSettingsUiEvent? = null,
)

sealed interface PlanSettingsUiAction {
    data object SeeWhatEachAppStoresClicked : PlanSettingsUiAction
    data object HowToUpgradeClicked : PlanSettingsUiAction
}

sealed interface PlanSettingsUiEvent {
    data class OpenUrl(val url: String) : PlanSettingsUiEvent
}
