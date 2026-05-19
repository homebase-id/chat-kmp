package id.homebase.core.ui.screens.help

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.video.FFmpegUtils
import id.homebase.core.logging.LogFileExporter
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.logging.setErrorCollectionEnabled
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.screens.help.HelpUiEvent.OpenUrl
import id.homebase.core.ui.screens.help.HelpUiEvent.ShareFile
import id.homebase.core.ui.screens.help.HelpUiEvent.ShowError
import id.homebase.core.updater.UpdateAppManager
import id.homebase.core.util.PlatformInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HelpViewModel(
    platformInfo: PlatformInfo,
    private val userPreferences: UserPreferences,
    private val updateAppManager: UpdateAppManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HelpUiState(
            appVersion = platformInfo.versionName,
            errorCollectionEnabled = userPreferences.errorCollectionEnabled,
            showDeveloperMenu = userPreferences.showDeveloperMenu,
        )
    )
    val uiState: StateFlow<HelpUiState> = _uiState.asStateFlow()
    private var developerTapCount = 0

    init {
        checkForUpdate()
        loadFfmpegVersion()
    }

    private fun loadFfmpegVersion() {
        viewModelScope.launch {
            val version = try {
                FFmpegUtils.getFfmpegVersion()
            } catch (e: Exception) {
                Logger.w(e) { "Failed to read ffmpeg version" }
                null
            }
            _uiState.update { it.copy(ffmpegVersion = version) }
        }
    }

    fun onAction(action: HelpUiAction) {
        when (action) {
            HelpUiAction.SupportCenterClicked -> {
                _uiState.update { it.copy(uiEvent = OpenUrl("https://id.homebase.id/links")) }
            }

            HelpUiAction.ContactUsClicked -> {
                _uiState.update { it.copy(uiEvent = OpenUrl("https://id.homebase.id/links")) }
            }

            HelpUiAction.SubmitDebugLogClicked -> {
                val logDir = LoggerConfig.logDirectory
                if (logDir != null) {
                    val logFile = LogFileExporter.getMostRecentLogFile(logDir)
                    if (logFile != null) {
                        _uiState.update { it.copy(uiEvent = ShareFile(logFile)) }
                    } else {
                        _uiState.update { it.copy(uiEvent = ShowError("No log files found")) }
                    }
                } else {
                    _uiState.update { it.copy(uiEvent = ShowError("Logging not initialized")) }
                }
            }

            HelpUiAction.TermsPrivacyClicked -> {
                _uiState.update { it.copy(uiEvent = OpenUrl("https://homebase.id/terms-and-conditions")) }
            }

            HelpUiAction.ToggleErrorCollection -> {
                try {
                    val isEnabled = !userPreferences.errorCollectionEnabled
                    userPreferences.errorCollectionEnabled = isEnabled
                    _uiState.update { it.copy(errorCollectionEnabled = isEnabled) }
                    setErrorCollectionEnabled(isEnabled)
                } catch (e: Exception) {
                    Logger.e { "Failed to toggle error collection: ${e.message}" }
                }
            }

            HelpUiAction.DeveloperClicked -> {
                developerTapCount++

                if (developerTapCount >= 5) {
                    // Enable developer menu after 5 taps
                    userPreferences.showDeveloperMenu = true
                    Logger.d { "Developer menu enabled after $developerTapCount taps" }

                    // Optional: Show a confirmation message
                    _uiState.update {
                        it.copy(
                            showDeveloperMenu = true,
                            uiEvent = ShowError("Developer menu enabled!"),
                        )
                    }

                    // Reset counter
                    developerTapCount = 0
                } else {
                    Logger.d { "Developer tap $developerTapCount/5" }
                }
            }

            HelpUiAction.DeveloperMenu -> {
                _uiState.update { it.copy(uiEvent = HelpUiEvent.OpenDeveloperMenu) }
            }

            HelpUiAction.DownloadUpdateClicked -> {
                downloadUpdate()
            }

            HelpUiAction.CheckForUpdatedClicked -> {
                checkForUpdate()
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingForUpdate = true) }
            updateAppManager.checkForUpdate().let { data ->
                delay(1000)
                _uiState.update {
                    it.copy(
                        isUpdateAvailable = data.updateAvailable,
                        isUpdateSupported = data.error == null && data.canUpdate,
                        isCheckingForUpdate = false,
                    )
                }
            }
        }
    }

    private fun downloadUpdate() {
        viewModelScope.launch {
            updateAppManager.downloadUpdate()
        }
    }
}
