package id.homebase.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.api.exception.AuthInProgressException
import id.homebase.api.isIos
import id.homebase.api.sync.DriveState
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.SyncState
import id.homebase.api.youauth.UsernameStorage
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.AppConfig
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.appPermissions
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.notifications.NotificationService
import id.homebase.resources.MR
import id.homebase.resources.error_unknown
import id.homebase.resources.login_error_generic
import id.homebase.resources.login_error_invalid_id
import id.homebase.resources.login_error_not_homebase
import id.homebase.resources.login_error_tls
import id.homebase.resources.login_error_unreachable
import io.ktor.client.HttpClient
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class LoginViewModel(
    private val youAuthFlowManager: YouAuthFlowManager,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val usernameStorage: UsernameStorage,
    private val notificationService: NotificationService,
    private val httpClient: HttpClient,
    private val driveSyncManager: DriveSyncManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    // Tracks the active auth-state observer so AppResumed doesn't stack multiple collectors.
    private var authStateJob: Job? = null
    // One-shot guard — prevents handleAuthenticatedUser() firing more than once per login.
    private var didHandleAuthenticated = false

    init {
        loadUsernameFromStorage()
        observeDriveStatuses()
        observeAuthState()
    }

    fun eventConsumed() {
        _uiState.update {
            it.copy(uiEvent = null)
        }
    }

    fun onAction(action: LoginUiAction) {
        when (action) {
            is LoginUiAction.CreateAccount -> {
                _uiState.update { it.copy(uiEvent = LoginUiEvent.OpenUrl("https://homebase.id/app-create-account")) }
            }

            is LoginUiAction.LoginClicked -> {
                startLogin(action.homebaseId)
            }

            LoginUiAction.AppResumed -> {
                // Auth flow may have completed or been cancelled
                observeAuthState()
            }
        }
    }

    fun onCallbackUrl(url: String) {
        viewModelScope.launch {
            Logger.i("Is this line hit?")
            youAuthFlowManager.handleCallback(url)
        }
    }

    /* ---------------- PRIVATE ---------------- */

    private fun startLogin(homebaseIdValue: String) {
        Logger.i(tag = "LoginViewModel", messageString = "startLogin($homebaseIdValue)")

        val homebaseId = try {
            OdinId(homebaseIdValue)
        } catch (_: Exception) {
            Logger.w(tag = "LoginViewModel", messageString = "Invalid Homebase ID: $homebaseIdValue")
            _uiState.update {
                it.copy(error = LoginError.Res(MR.string.login_error_invalid_id))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    homebaseId = homebaseId.domainName,
                    isLoading = true,
                    isPinging = true,
                    error = null,
                    errorDetails = null,
                )
            }

            // Be honest about the cause: a connectivity failure must not be reported as
            // "that isn't a Homebase ID". Only a server that answered non-200 earns that.
            // Either way carry a raw detail (exception/status) for the details toggle.
            val ping = pingIdentity(httpClient, homebaseId)
            if (ping != IdentityPingResult.Ok) {
                Logger.w(tag = "LoginViewModel", messageString = "Identity $homebaseId ping=$ping, aborting login")
                val errorRes = when (ping) {
                    is IdentityPingResult.TlsError -> MR.string.login_error_tls
                    is IdentityPingResult.Unreachable -> MR.string.login_error_unreachable
                    else -> MR.string.login_error_not_homebase
                }
                val details = when (ping) {
                    is IdentityPingResult.TlsError -> ping.detail
                    is IdentityPingResult.Unreachable -> ping.detail
                    is IdentityPingResult.NotHomebase ->
                        "HTTP ${ping.statusCode} from https://${homebaseId.domainName}/api/v2/health/ping"
                    IdentityPingResult.Ok -> null
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPinging = false,
                        error = LoginError.Res(errorRes, homebaseId.domainName),
                        errorDetails = details,
                    )
                }

                return@launch
            }

            _uiState.update { it.copy(isPinging = false) }

            try {
                Logger.i(tag = "LoginViewModel", messageString = "Ping OK, calling youAuthFlowManager.authorize()...")
                val authUrl = youAuthFlowManager.authorize(
                    identity = homebaseId,
                    appId = AppConfig.APP_ID,
                    appName = AppConfig.APP_NAME,
                    drives = targetDriveAccessRequest,
                    permissions = appPermissions,
                    circleDrives = circleDriveTargetRequest,
                    circles =
                        listOf(CONFIRMED_CONNECTIONS_CIRCLE_ID, AUTO_CONNECTIONS_CIRCLE_ID)
                )
                Logger.i(tag = "LoginViewModel", messageString = "Auth URL ready, launching browser")
                _uiState.update { it.copy(uiEvent = LoginUiEvent.OpenAuthUrl(authUrl)) }
            } catch (_: AuthInProgressException) {
                Logger.w(tag = "LoginViewModel", messageString = "Auth already in progress, ignoring")
            } catch (e: Exception) {
                Logger.e(tag = "LoginViewModel", messageString = "authorize() failed: ${e::class.simpleName}: ${e.message}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message?.let { msg -> LoginError.Message(msg) }
                            ?: LoginError.Res(MR.string.login_error_generic),
                        errorDetails = "${e::class.simpleName ?: "Error"}: ${e.message ?: "(no message)"}",
                    )
                }
            }
        }
    }

    private fun loadUsernameFromStorage() {
        val savedUsername = usernameStorage.loadUsername()
        if (savedUsername.isNotBlank()) {
            _uiState.update { it.copy(homebaseId = savedUsername) }
        }
    }

    private fun handleAppResumed() {
        viewModelScope.launch {
            if (isIos()) youAuthFlowManager.onAppResumed()
        }
    }

    private fun observeDriveStatuses() {
        viewModelScope.launch {
            driveSyncManager.driveStatuses.collect { statuses ->
                val progresses = statuses.values.map { status ->
                    when (val state = status.state) {
                        is DriveState.Initialized -> DriveProgress(
                            driveId = status.driveId.toString(),
                            name = status.label,
                        )

                        is DriveState.Synchronizing -> DriveProgress(
                            driveId = status.driveId.toString(),
                            name = status.label,
                            count = state.count,
                            total = state.count,
                        )

                        is DriveState.Completed -> DriveProgress(
                            driveId = status.driveId.toString(),
                            name = status.label,
                            completed = true,
                            progress = 1f,
                            count = state.totalCount,
                            total = state.totalCount,
                        )

                        is DriveState.Failed -> DriveProgress(
                            driveId = status.driveId.toString(),
                            name = status.label,
                            error = state.message,
                        )
                    }
                }
                _uiState.update { it.copy(driveProgresses = progresses.toImmutableList()) }
            }
        }
    }

    private fun observeAuthState() {
        // Cancel any in-flight observer (e.g. from a previous AppResumed) before starting a new one.
        authStateJob?.cancel()
        didHandleAuthenticated = false
        authStateJob = viewModelScope.launch {
            combine(
                youAuthFlowManager.authState,
                authConnectionCoordinator.connectionState,
                driveSyncManager.syncState
            ) { authState, connectionState, syncState ->
                Triple(authState, connectionState.isConnecting, syncState)
            }
                .distinctUntilChanged() // Ensures only unique combined results are emitted
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            error = error.message?.let { msg -> LoginError.Message(msg) }
                                ?: LoginError.Res(MR.string.error_unknown)
                        )
                    }
                }
                .collectLatest { (authState, isConnecting, syncState) ->
                    Logger.i(
                        tag = "LoginViewModel",
                        messageString = "AuthState: ${authState::class.simpleName} " +
                            "sync=${syncState::class.simpleName} connecting=$isConnecting"
                    )
                    when (authState) {
                        is YouAuthState.Authenticated -> {
                            // Hold the initial loading screen until the drive sync has STOPPED —
                            // Completed OR Failed (a drive "Stopped" event). A premature churn
                            // (e.g. network drop) still yields a terminal state, so we close the
                            // screen and the running client resumes syncing on WS reconnect. If the
                            // sync can't even start (Idle once the connection has settled), skip the
                            // loading screen entirely. Identical on every platform — driven only by
                            // the shared DriveSyncManager.syncState + connection state.
                            val syncStopped =
                                syncState is SyncState.Completed || syncState is SyncState.Failed
                            val syncCannotStart = syncState is SyncState.Idle && !isConnecting
                            if (syncStopped || syncCannotStart) {
                                handleAuthenticatedUser()
                            } else {
                                _uiState.update { it.copy(isLoading = true) }
                            }
                        }

                        is YouAuthState.Authenticating,
                        is YouAuthState.Initializing -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }

                        is YouAuthState.Unauthenticated -> {
                            _uiState.update { it.copy(isLoading = false, isAuthenticated = false) }
                        }

                        is YouAuthState.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = false,
                                    error = LoginError.Message(authState.message)
                                )
                            }
                        }
                    }
                }
        }
    }

    private fun handleAuthenticatedUser() {
        if (didHandleAuthenticated) return
        didHandleAuthenticated = true
        notificationService.reRegisterAsync()
        usernameStorage.saveUsername(_uiState.value.homebaseId)
        _uiState.update {
            it.copy(
                isLoading = false,
                isAuthenticated = true,
                error = null,
                uiEvent = LoginUiEvent.NavigateToHome
            )
        }
    }
}
