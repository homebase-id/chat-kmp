package id.homebase.chat.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.PlatformType
import id.homebase.api.getPlatform
import id.homebase.api.youauth.UsernameStorage
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.chat.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.chat.config.AppConfig
import id.homebase.chat.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.chat.config.appPermissions
import id.homebase.chat.config.circleDriveTargetRequest
import id.homebase.chat.config.targetDriveAccessRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val youAuthFlowManager: YouAuthFlowManager,
    private val usernameStorage: UsernameStorage,
    private val httpClient: HttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    private val _uiEvent = Channel<LoginUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        loadUsernameFromStorage()
        observeAuthState()

        viewModelScope.launch {
            try {
                checkExistingSession()
            } catch (e: Exception) {
                Logger.e("LoginViewModel", e) { "Error checking existing session: ${e.message}" }
            }
        }
    }


    fun onAction(action: LoginUiAction) {
        when (action) {
            is LoginUiAction.HomebaseIdChanged -> {
                _uiState.update {
                    it.copy(
                        homebaseId = action.value,
                        errorMessage = null
                    )
                }
            }

            LoginUiAction.LoginClicked -> {
                startLogin()
            }

            LoginUiAction.RetryClicked -> {
                _uiState.update {
                    it.copy(
                        errorMessage = null,
                        isLoading = false
                    )
                }
                startLogin()

            }

            LoginUiAction.AppResumed -> {
                // Auth flow may have completed or been cancelled
                observeAuthState()
            }
        }
    }

    /* ---------------- PRIVATE ---------------- */

    suspend fun isValidHomebaseId(identity: String): Boolean {
        try {
            val response = httpClient.get("https://$identity/api/v2/health/ping")
            return when (response.status.value) {
                200 -> true
                else -> false
            }
        }catch (t: Throwable)
        {
//            Logger.e("LoginViewModel", t, "failed while trying to ping $identity")
            return false
        }
    }

    private fun startLogin() {
        val homebaseId = _uiState.value.homebaseId.trim()

        if (homebaseId.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Homebase ID is required")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
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
                youAuthFlowManager.authorize(
                    identity = homebaseId,
                    scope = viewModelScope,
                    appId = AppConfig.APP_ID,
                    appName = AppConfig.APP_NAME,
                    drives = targetDriveAccessRequest,
                    permissions = appPermissions,
                    circleDrives = circleDriveTargetRequest,
                    circles =
                        listOf(CONFIRMED_CONNECTIONS_CIRCLE_ID, AUTO_CONNECTIONS_CIRCLE_ID)
                )
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

    private suspend fun checkExistingSession() {
        if (youAuthFlowManager.restoreSession()) {
            _uiState.update { it.copy(isAuthenticated = true) }
            viewModelScope.launch { _uiEvent.send(LoginUiEvent.NavigateToHome) }
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            youAuthFlowManager.authState.collect { authState ->
                when (authState) {
                    is YouAuthState.Authenticated -> {
                        usernameStorage.saveUsername(_uiState.value.homebaseId)

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = true,
                                errorMessage = null
                            )
                        }
                        _uiEvent.send(LoginUiEvent.NavigateToHome)

                    }

                    is YouAuthState.Unauthenticated -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = false
                            )
                        }
                    }

                    is YouAuthState.Authenticating -> {
                        _uiState.update {
                            it.copy(isLoading = true)
                        }
                    }

                    is YouAuthState.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = false,
                                errorMessage = authState.message
                            )
                        }
                    }
                }
            }
        }
    }
}
