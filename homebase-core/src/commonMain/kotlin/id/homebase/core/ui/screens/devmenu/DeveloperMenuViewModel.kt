package id.homebase.core.ui.screens.devmenu

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.notifications.RichNotificationData
import id.homebase.core.notifications.RichNotificationDisplayer
import id.homebase.core.vault.VaultPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

class DeveloperMenuViewModel(
    private val driveSyncManager: DriveSyncManager,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val vaultPreferences: VaultPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeveloperMenuUiState())
    val uiState: StateFlow<DeveloperMenuUiState> = _uiState.asStateFlow()

    fun onUiAction(action: DeveloperMenuUiAction) {
        when (action) {
            is DeveloperMenuUiAction.BackClicked -> {
                sendEvent(DeveloperMenuUiEvent.Back)
            }

            is DeveloperMenuUiAction.TestRichNotification -> {
                testRichNotification()
            }

            is DeveloperMenuUiAction.ForceSyncAll -> {
                forceSyncAll()
            }

            is DeveloperMenuUiAction.ClearAllData -> {
                clearAllData()
            }

            is DeveloperMenuUiAction.RestartVaultOnboarding -> {
                restartVaultOnboarding()
            }

            is DeveloperMenuUiAction.ForceReconnectWebSocket -> {
                // Note: WebSocket client is managed by AuthConnectionCoordinator
                // We can trigger reconnection by calling disconnect, which will auto-reconnect
                sendEvent(DeveloperMenuUiEvent.Error(
                    "WebSocket reconnect: Use 'Force Sync All' to trigger reconnection logic"
                ))
            }
        }
    }

    private fun testRichNotification() {
        val richData = RichNotificationData(
            notificationId = 1,
            channelId = "messages",
            conversationId = null,
            title = "Test notification",
            body = "Body text of notification",
            senderName = "John Sender",
            senderId = "john.sender.homebase",
            senderImageBytes = null,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            payloadData = mapOf(),
            silent = false,
        )

        RichNotificationDisplayer().show(richData)
    }

    private fun forceSyncAll() {
        viewModelScope.launch {
            try {
                Logger.i(tag = "DeveloperMenu") { "Force sync all triggered" }
                driveSyncManager.syncAll()
                sendEvent(DeveloperMenuUiEvent.Success("Sync completed successfully"))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "DeveloperMenu") { "Force sync failed" }
                sendEvent(DeveloperMenuUiEvent.Error("Sync failed: ${e.message}"))
            }
        }
    }

    private fun clearAllData() {
        viewModelScope.launch {
            try {
                Logger.i(tag = "DeveloperMenu") { "Clearing all data" }

                // Clear all sync data
                driveSyncManager.clearStorage()

                // Clear notifications (if user is logged in)
                val identityId = credentialsManager.getActiveCredentials()?.getIdentityId()
                if (identityId != null) {
                    databaseManager.appNotifications.deleteAll(identityId)
                }

                Logger.i(tag = "DeveloperMenu") { "All data cleared successfully" }
                sendEvent(DeveloperMenuUiEvent.Success("All data cleared. Please restart the app."))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "DeveloperMenu") { "Clear data failed" }
                sendEvent(DeveloperMenuUiEvent.Error("Failed to clear data: ${e.message}"))
            }
        }
    }

    private fun restartVaultOnboarding() {
        viewModelScope.launch {
            try {
                vaultPreferences.setActivated(false)
                vaultPreferences.setIconVisible(true)
                sendEvent(DeveloperMenuUiEvent.Success("Vault onboarding reset. Tap the Vault icon to start onboarding."))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "DeveloperMenu") { "Vault onboarding reset failed" }
                sendEvent(DeveloperMenuUiEvent.Error("Failed to reset vault onboarding: ${e.message}"))
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: DeveloperMenuUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }
}

@Immutable
data class DeveloperMenuUiState(
    val uiEvent: DeveloperMenuUiEvent? = null,
)

sealed interface DeveloperMenuUiEvent {
    data object Back : DeveloperMenuUiEvent
    data class Error(val errorMessage: String) : DeveloperMenuUiEvent
    data class Success(val message: String) : DeveloperMenuUiEvent
}

sealed interface DeveloperMenuUiAction {
    data object BackClicked : DeveloperMenuUiAction
    data object TestRichNotification : DeveloperMenuUiAction
    data object ForceSyncAll : DeveloperMenuUiAction
    data object ClearAllData : DeveloperMenuUiAction
    data object ForceReconnectWebSocket : DeveloperMenuUiAction
    data object RestartVaultOnboarding : DeveloperMenuUiAction
}