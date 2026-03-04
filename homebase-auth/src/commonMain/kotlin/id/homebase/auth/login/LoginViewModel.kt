package id.homebase.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.PlatformType
import id.homebase.api.common.OdinId
import id.homebase.api.getPlatform
import id.homebase.api.youauth.UsernameStorage
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.AppConfig
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.appPermissions
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.util.StartupState
import id.homebase.core.util.mapToStartupState
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val youAuthFlowManager: YouAuthFlowManager,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val usernameStorage: UsernameStorage,
    private val httpClient: HttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    init {
        loadUsernameFromStorage()
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
            val response = httpClient.get("https://$identity/api/v2/health/ping")
            return when (response.status.value) {
                200 -> true
                else -> false
            }
        } catch (_: Throwable) {
//            Logger.e("LoginViewModel", t, "failed while trying to ping $identity")
            return false
        }
    }

    private fun startLogin(homebaseIdValue: String) {

        val homebaseId = try {
            OdinId(homebaseIdValue)
        } catch (_: Exception) {
            _uiState.update {
                it.copy(errorMessage = "Valid Homebase ID is required")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    homebaseId = homebaseId.domainName,
                    isLoading = true,
                    errorMessage = null
                )
            }

            if (!isValidHomebaseId(homebaseId)) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Unable to ping $homebaseId - are you sure it's a Homebase ID?"
                    )
                }

                return@launch
            }

            try {
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
                _uiState.update { it.copy(uiEvent = LoginUiEvent.OpenAuthUrl(authUrl)) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Login failed")
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
            if (getPlatform().name != PlatformType.IOS) youAuthFlowManager.onAppResumed()
        }
    }

    private fun observeAuthState() {

        viewModelScope.launch {

            combine(
                authConnectionCoordinator.connectionState,
                youAuthFlowManager.authState
            ) { connectionState, authState ->
                authState.mapToStartupState(connectionState.isDoingInitialConnection)
            }
                .distinctUntilChanged() // Ensures only unique combined results are emitted
                .catch { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message ?: "Unknown error")
                    }
                }
                .collectLatest { authState ->
                    Logger.i(tag = "LoginViewModel", messageString = "AuthState: $authState")
                    when (authState) {
                        is StartupState.Authenticated -> {
                            usernameStorage.saveUsername(_uiState.value.homebaseId)

                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = true,
                                    errorMessage = null,
                                    uiEvent = LoginUiEvent.NavigateToHome
                                )
                            }

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
                                    errorMessage = authState.message
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
}
