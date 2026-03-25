package id.homebase.core.ui.screens.help

import androidx.lifecycle.ViewModel
import id.homebase.core.logging.LogFileExporter
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.util.PlatformInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HelpViewModel(
    platformInfo: PlatformInfo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HelpUiState(appVersion = platformInfo.versionName))
    val uiState: StateFlow<HelpUiState> = _uiState.asStateFlow()

    fun onAction(action: HelpUiAction) {
        when (action) {
            HelpUiAction.SupportCenterClicked -> {
                _uiState.update { it.copy(uiEvent = HelpUiEvent.OpenUrl("https://id.homebase.id/links")) }
            }

            HelpUiAction.ContactUsClicked -> {
                _uiState.update { it.copy(uiEvent = HelpUiEvent.OpenUrl("https://id.homebase.id/links")) }
            }

            HelpUiAction.SubmitDebugLogClicked -> {
                val logDir = LoggerConfig.logDirectory
                if (logDir != null) {
                    val logFile = LogFileExporter.getMostRecentLogFile(logDir)
                    if (logFile != null) {
                        _uiState.update { it.copy(uiEvent = HelpUiEvent.ShareFile(logFile)) }
                    } else {
                        _uiState.update { it.copy(uiEvent = HelpUiEvent.ShowError("No log files found")) }
                    }
                } else {
                    _uiState.update { it.copy(uiEvent = HelpUiEvent.ShowError("Logging not initialized")) }
                }
            }

            HelpUiAction.TermsPrivacyClicked -> {
                _uiState.update { it.copy(uiEvent = HelpUiEvent.OpenUrl("https://homebase.id/terms-and-conditions")) }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}
