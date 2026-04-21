package id.homebase.chat.conversationlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.SecurityContextProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
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
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val config: PermissionExtensionConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ExtendPermissionUiState>(ExtendPermissionUiState.Idle)
    val uiState: StateFlow<ExtendPermissionUiState> = _uiState.asStateFlow()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    init {
        viewModelScope.launch { checkPermissions() }

        viewModelScope.launch {
            eventBus.events
                .filterIsInstance<BackendEvent.DriveAuthorizationFailed>()
                .collect { checkPermissions() }
        }
    }

    private suspend fun checkPermissions() {
        if (_uiState.value is ExtendPermissionUiState.Dismissed) return
        try {
            val domain = credentialsManager.requireActiveCredentials().domain.domainName
            val manager = PermissionExtensionManager.create(securityContextProvider, domain)
            val result = manager.getMissingPermissions(config)

            if (result != null && result.hasMissingPermissions) {
                Logger.i(tag = TAG) {
                    "Missing permissions detected: drives=${result.missingDrives.size}, permissions=${result.missingPermissions.size}, allConnected=${result.missingAllConnectedCircle}"
                }
                _permissionsGranted.value = false
                _uiState.value =
                    ExtendPermissionUiState.ShowDialog(
                        extendPermissionUrl = result.extendPermissionUrl,
                        appName = config.appName
                    )
            } else {
                Logger.d(tag = TAG) { "All permissions are granted" }
                _permissionsGranted.value = true
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Error checking permissions: ${e.message}" }
        }
    }

    fun recheckPermissions() {
        _uiState.value = ExtendPermissionUiState.Idle
        viewModelScope.launch { checkPermissions() }
    }

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
