package id.homebase.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.youauth.UsernameStorage
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.AUTO_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.AppConfig
import id.homebase.core.config.CONFIRMED_CONNECTIONS_CIRCLE_ID
import id.homebase.core.config.appPermissions
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.config.circleDriveTargetRequest
import id.homebase.core.config.contactTargetDrive
import id.homebase.core.config.feedTargetDrive
import id.homebase.core.config.syncDrives
import id.homebase.core.config.targetDriveAccessRequest
import id.homebase.core.notifications.NotificationService
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
    private val notificationService: NotificationService,
    private val httpClient: HttpClient,
    private val eventBus: EventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    init {
        loadUsernameFromStorage()
        observeEvents()
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

    private val driveNames = mapOf(
        chatTargetDrive.alias to "Chat",
        feedTargetDrive.alias to "Feed",
        contactTargetDrive.alias to "Contact",
    )

    private fun driveName(driveId: kotlin.uuid.Uuid) = driveNames[driveId] ?: driveId.toString()

    private fun updateDrive(
        current: List<DriveProgress>,
        driveId: kotlin.uuid.Uuid,
        transform: (DriveProgress) -> DriveProgress,
    ): List<DriveProgress> {
        val id = driveId.toString()
        return current.map { if (it.driveId == id) transform(it) else it }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            eventBus.events.collectLatest { event ->
                when (event) {
                    is BackendEvent.DriveEvent.SyncAllStarted -> {
                        val initial = syncDrives.map { drive ->
                            DriveProgress(driveId = drive.alias.toString(), name = driveName(drive.alias))
                        }
                        _uiState.update { it.copy(driveProgresses = initial) }
                    }
                    is BackendEvent.DriveEvent.Started -> {
                        _uiState.update { state ->
                            val id = event.driveId.toString()
                            val exists = state.driveProgresses.any { it.driveId == id }
                            val updated = if (exists) {
                                updateDrive(state.driveProgresses, event.driveId) { it.copy(progress = null) }
                            } else {
                                state.driveProgresses + DriveProgress(driveId = id, name = driveName(event.driveId))
                            }
                            state.copy(driveProgresses = updated)
                        }
                    }
                    is BackendEvent.DriveEvent.Completed -> {
                        _uiState.update { state ->
                            state.copy(driveProgresses = updateDrive(state.driveProgresses, event.driveId) {
                                it.copy(completed = true, progress = 1f, total = event.totalCount, count = event.totalCount)
                            })
                        }
                    }
                    is BackendEvent.DriveEvent.Failed -> {
                        _uiState.update { state ->
                            val id = event.driveId.toString()
                            val exists = state.driveProgresses.any { it.driveId == id }
                            val updated = if (exists) {
                                updateDrive(state.driveProgresses, event.driveId) { it.copy(error = event.errorMessage) }
                            } else {
                                state.driveProgresses + DriveProgress(driveId = id, name = driveName(event.driveId), error = event.errorMessage)
                            }
                            state.copy(driveProgresses = updated)
                        }
                    }
                    is BackendEvent.DriveEvent.BatchReceived -> {
                        _uiState.update { state ->
                            val id = event.driveId.toString()
                            val exists = state.driveProgresses.any { it.driveId == id }
                            val updated = if (exists) {
                                updateDrive(state.driveProgresses, event.driveId) { it.copy(count = event.totalCount, total = event.totalCount, progress = null) }
                            } else {
                                state.driveProgresses + DriveProgress(driveId = id, name = driveName(event.driveId), count = event.totalCount, total = event.totalCount)
                            }
                            state.copy(driveProgresses = updated)
                        }
                    }
                    else -> {}
                }
            }
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

    private suspend fun handleAuthenticatedUser() {
        notificationService.reRegister()
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
}
