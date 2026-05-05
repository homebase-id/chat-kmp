package id.homebase.chat.conversationlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.youauth.MissingPermissionsResult
import id.homebase.api.youauth.PermissionExtensionConfig
import id.homebase.api.youauth.PermissionExtensionManager
import id.homebase.api.youauth.SecurityContextProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

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

    /**
     * Flips to true once [checkPermissions] has actually completed against the active
     * credentials at least once. Lets callers (e.g. the feed webview) wait for a
     * permissions verdict before loading content that would otherwise hit the network
     * with insufficient grants. Stale-VM and no-credentials short-circuits do not flip
     * this.
     */
    private val _permissionsChecked = MutableStateFlow(false)
    val permissionsChecked: StateFlow<Boolean> = _permissionsChecked.asStateFlow()

    /**
     * Credentials token captured on the first successful check. If the active credentials'
     * token ever differs from this (because a leaked VM from a previous sign-in is still
     * collecting eventBus events after logout/login — including a logout/login as the same
     * identity), [checkPermissions] no-ops so the stale VM can't push state into a
     * composable that may still be observing it. Token is used instead of domain because
     * the domain is the same across re-logins of the same identity.
     */
    private var boundToken: String? = null

    /**
     * One-shot signal for the host screen to navigate the user back to the chat tab
     * (or clear the selected conversation, if already there) when they explicitly
     * cancel — either by tapping Cancel on the dialog or by aborting the owner-console
     * flow (`status=canceled`). Emitted on a SharedFlow so the screen consumes it
     * exactly once per cancel.
     */
    private val _navigateAwayRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateAwayRequest: SharedFlow<Unit> = _navigateAwayRequest.asSharedFlow()

    /**
     * Latest result from [PermissionExtensionManager.getMissingPermissions]. Held so the
     * dialog's confirm-click can rebuild the extend-permission URL with a freshly
     * resolved `returnUrl` (which on JVM may need to start a new callback server if
     * the previous one was stopped).
     */
    private var lastMissingResult: MissingPermissionsResult? = null

    init {
        viewModelScope.launch { checkPermissions() }

        viewModelScope.launch {
            eventBus.events
                .filterIsInstance<BackendEvent.DriveAuthorizationFailed>()
                .collect { checkPermissions() }
        }

        viewModelScope.launch {
            eventBus.events
                .filterIsInstance<BackendEvent.PermissionsExtensionReturned>()
                .collect {
                    Logger.i(tag = TAG) { "Permissions-extension return — rechecking" }
                    recheckPermissions()
                }
        }

        viewModelScope.launch {
            eventBus.events
                .filterIsInstance<BackendEvent.PermissionsExtensionCanceled>()
                .collect {
                    Logger.i(tag = TAG) {
                        "Permissions-extension canceled — dismissing dialog, requesting nav-away"
                    }
                    // Dismiss without rechecking — the user already said "no thanks"
                    // in the owner console, so re-prompting with the same dialog would
                    // just be the second of two cancel taps.
                    _uiState.value = ExtendPermissionUiState.Dismissed
                    _navigateAwayRequest.tryEmit(Unit)
                }
        }
    }

    private suspend fun checkPermissions() {
        if (_uiState.value is ExtendPermissionUiState.Dismissed) return
        try {
            val active = credentialsManager.getActiveCredentials()
            if (active == null) {
                Logger.d(tag = TAG) { "No active credentials — skipping permission check" }
                return
            }
            val activeDomain = active.domain.domainName
            val activeToken = active.clientAccessToken
            val bound = boundToken
            if (bound == null) {
                boundToken = activeToken
            } else if (bound != activeToken) {
                Logger.d(tag = TAG) {
                    "Stale VM (token mismatch, domain=$activeDomain) — skipping permission check"
                }
                return
            }
            val manager = PermissionExtensionManager.create(securityContextProvider, activeDomain)
            val result = manager.getMissingPermissions(config)

            if (result != null && result.hasMissingPermissions) {
                Logger.i(tag = TAG) {
                    "Missing permissions detected: drives=${result.missingDrives.size}, permissions=${result.missingPermissions.size}, allConnected=${result.missingAllConnectedCircle}"
                }
                lastMissingResult = result
                _permissionsGranted.value = false
                _uiState.value =
                    ExtendPermissionUiState.ShowDialog(appName = config.appName)
            } else {
                Logger.d(tag = TAG) { "All permissions are granted" }
                lastMissingResult = null
                _permissionsGranted.value = true
            }
            _permissionsChecked.value = true
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Error checking permissions: ${e.message}" }
        }
    }

    fun recheckPermissions() {
        _uiState.value = ExtendPermissionUiState.Idle
        // Note: we deliberately do NOT reset _permissionsChecked here. Once a check has
        // completed against the current credentials, callers (e.g. the feed webview
        // gate) should keep seeing a stable verdict. Resetting it here would make
        // every ON_RESUME / event-bus-triggered recheck unmount any UI gated on
        // "permissions checked" and reload from scratch.
        viewModelScope.launch { checkPermissions() }
    }

    fun dismissDialog() {
        _uiState.value = ExtendPermissionUiState.Dismissed
    }

    /**
     * Builds the extend-permission URL freshly at the moment of the user's click —
     * this re-evaluates [PermissionExtensionConfig.returnUrl] so the URL carries the
     * live callback-server port (and, on JVM, restarts the server if it was stopped).
     * Returns null if no missing-permissions result is cached (shouldn't happen while
     * the dialog is shown).
     */
    fun buildExtendPermissionUrl(): String? = lastMissingResult?.buildExtendPermissionUrl?.invoke()

    companion object {
        private const val TAG = "ExtendPermissionViewModel"
    }
}

/** UI state for the extend permission dialog. */
sealed interface ExtendPermissionUiState {
    /** Initial state — permission check in progress or all permissions granted. */
    data object Idle : ExtendPermissionUiState

    /** Missing permissions detected, dialog should be shown. */
    data class ShowDialog(val appName: String) : ExtendPermissionUiState

    /** User dismissed the dialog. */
    data object Dismissed : ExtendPermissionUiState
}
