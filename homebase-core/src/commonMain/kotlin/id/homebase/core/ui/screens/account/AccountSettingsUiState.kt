package id.homebase.core.ui.screens.account

import id.homebase.api.client.storage.StorageUsage

data class AccountSettingsUiState(
    /** The identity's plan and storage, from GET /api/v2/plan; null when the server has no hosting plans. */
    val usage: StorageUsage? = null,
    val isFull: Boolean = false,
    val isLoading: Boolean = true,
    /** "How to upgrade" was tapped: the dialog saying where the owner is going (or who to contact) is open */
    val showUpgradeDialog: Boolean = false,
    val uiEvent: AccountSettingsUiEvent? = null,
)

sealed interface AccountSettingsUiAction {
    data object SeeWhatEachAppStoresClicked : AccountSettingsUiAction
    data object HowToUpgradeClicked : AccountSettingsUiAction
    data object UpgradeContinueClicked : AccountSettingsUiAction
    data object UpgradeDialogDismissed : AccountSettingsUiAction
}

/**
 * Continue in the upgrade dialog: close it and open the hosting provider's upgrade page. The identity builds that
 * address (who the owner is, which host), so it is opened exactly as given; without one there is nothing to open.
 */
internal fun AccountSettingsUiState.afterUpgradeContinue(): AccountSettingsUiState {
    val url = usage?.hostingProvider?.upgradeUrl
    return copy(showUpgradeDialog = false, uiEvent = url?.let { AccountSettingsUiEvent.OpenUrl(it) } ?: uiEvent)
}

sealed interface AccountSettingsUiEvent {
    data class OpenUrl(val url: String) : AccountSettingsUiEvent
}
