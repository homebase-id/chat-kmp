package id.homebase.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.api.exception.AuthInProgressException
import id.homebase.api.isIos
import id.homebase.api.sync.DriveState
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.youauth.UsernameStorage
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.AppConfig
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.appPermissions
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.notifications.NotificationService
import id.homebase.core.util.StartupState
import id.homebase.core.util.mapToStartupState
import id.homebase.resources.MR
import id.homebase.resources.error_unknown
import id.homebase.resources.login_error_generic
import id.homebase.resources.login_error_invalid_id
import id.homebase.resources.login_error_ping_failed
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
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
                _uiState.update { it.copy(uiEvent = LoginUiEvent.OpenUrl("https://homebase.id/sign-up")) }
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

    suspend fun isValidHomebaseId(identity: OdinId): Boolean {
        try {
            Logger.i(tag = "LoginViewModel", messageString = "Pinging https://$identity/api/v2/health/ping ...")
            val response = httpClient.get("https://$identity/api/v2/health/ping") {
                timeout {
                    requestTimeoutMillis = 15_000
                    connectTimeoutMillis = 10_000
                }
            }
            Logger.i(tag = "LoginViewModel", messageString = "Ping response: ${response.status.value}")
            return when (response.status.value) {
                200 -> true
                else -> false
            }
        } catch (t: Throwable) {
            Logger.e(tag = "LoginViewModel", messageString = "Ping failed for $identity: ${t::class.simpleName}: ${t.message}")
            return false
        }
    }

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
                    error = null
                )
            }

            if (!isValidHomebaseId(homebaseId)) {
                Logger.w(tag = "LoginViewModel", messageString = "Identity $homebaseId failed ping check, aborting login")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPinging = false,
                        error = LoginError.Res(MR.string.login_error_ping_failed, homebaseId.domainName)
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
                            ?: LoginError.Res(MR.string.login_error_generic)
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
                authConnectionCoordinator.connectionState,
                youAuthFlowManager.authState
            ) { connectionState, authState ->
                authState.mapToStartupState(connectionState.isConnecting)
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
                .collectLatest { authState ->
                    Logger.i(tag = "LoginViewModel", messageString = "AuthState: $authState")
                    when (authState) {
                        is StartupState.Authenticated -> {
                            handleAuthenticatedUser()
                        }

                        is StartupState.Unauthenticated -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = false
                                )
                            }
                        }

                        is StartupState.Authenticating -> {
                            _uiState.update {
                                it.copy(isLoading = true)
                            }
                        }

                        is StartupState.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = false,
                                    error = LoginError.Message(authState.message)
                                )
                            }
                        }

                        else -> {
                            // ignore
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
