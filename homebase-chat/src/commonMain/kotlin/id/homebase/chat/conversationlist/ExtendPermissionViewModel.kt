package id.homebase.chat.conversationlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.core.config.getPermissionExtensionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that checks for missing app permissions on startup and exposes a UI state to drive the
 * extend permission dialog.
 *
 * This is the KMP equivalent of the React Native `ExtendPermissionDialog` component's
 * permission-checking logic.
 */
class ExtendPermissionViewModel(
        private val securityContextProvider: SecurityContextProvider,
        private val credentialsManager: CredentialsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExtendPermissionUiState>(ExtendPermissionUiState.Idle)
    val uiState: StateFlow<ExtendPermissionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { checkPermissions() }
                .onFailure { Logger.e(TAG, it) { "Permission check failed" } }
        }
    }

    private suspend fun checkPermissions() {
        try {
            val domain = credentialsManager.requireActiveCredentials().domain.domainName
            val manager = PermissionExtensionManager.create(securityContextProvider, domain)
            val config = getPermissionExtensionConfig()
            val result = manager.getMissingPermissions(config)

            if (result != null && result.hasMissingPermissions) {
                Logger.i(TAG) {
                    "Missing permissions detected: drives=${result.missingDrives.size}, permissions=${result.missingPermissions.size}, allConnected=${result.missingAllConnectedCircle}"
                }
                _uiState.value =
                        ExtendPermissionUiState.ShowDialog(
                                extendPermissionUrl = result.extendPermissionUrl,
                                appName = config.appName
                        )
            } else {
                Logger.d(TAG) { "All permissions are granted" }
            }
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Error checking permissions: ${e.message}" }
        }
    }

    /** Dismiss the permission dialog. Won't show again for this session. */
    fun dismissDialog() {
        _uiState.value = ExtendPermissionUiState.Dismissed
    }

    companion object {
        private const val TAG = "ExtendPermissionViewModel"
    }
}

/** UI state for the extend permission dialog. */
sealed interface ExtendPermissionUiState {
    /** Initial state — permission check in progress or all permissions granted. */
    data object Idle : ExtendPermissionUiState

    /** Missing permissions detected, dialog should be shown. */
    data class ShowDialog(val extendPermissionUrl: String, val appName: String) :
            ExtendPermissionUiState

    /** User dismissed the dialog. */
    data object Dismissed : ExtendPermissionUiState
}
