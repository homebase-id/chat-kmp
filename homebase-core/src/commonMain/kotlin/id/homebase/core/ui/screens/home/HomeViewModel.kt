package id.homebase.core.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.logging.LogFileExporter
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.util.PlatformInfo
import id.homebase.core.util.isDesktop
import id.homebase.core.util.isMobile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    platformInfo: PlatformInfo,
): ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState(appVersion = platformInfo.versionName))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()


    /** Single entry point for all UI actions. */
    fun onAction(action: HomeUiAction) {
        when (action) {
            is HomeUiAction.ExportLogClicked -> {
                exportLogFile()
            }
            is HomeUiAction.ClearLogClicked -> {
                clearLogFile()
            }
            is HomeUiAction.ExamplesClicked -> {
                sendEvent(HomeUiEvent.NavigateToExample)
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: HomeUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

    private fun exportLogFile() {
        viewModelScope.launch {
            try {

                // Get the log directory and most recent log file
                val logDir = LoggerConfig.logDirectory
                if (logDir != null) {
                    val logFile = LogFileExporter.getMostRecentLogFile(logDir)
                    if (logFile != null) {
                        when {
                            isMobile() -> {
                                sendEvent(HomeUiEvent.ShareFile(logFile))
                            }
                            isDesktop() -> {
                                sendEvent(HomeUiEvent.OpenFileBrowser(logFile))
                            }
                            else -> {
                                Logger.w(tag = TAG) { "Log export not supported" }
                            }
                        }
                    } else {
                        Logger.w(tag = TAG) { "No log files found to export" }
                    }
                } else {
                    Logger.w(tag = TAG) { "Log directory not initialized" }
                }
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to export log file" }

            }
        }
    }

    private fun clearLogFile() {
        viewModelScope.launch {
            try {
                LoggerConfig.purgeLogs()
                Logger.i(tag = TAG) { "Log cleared by user" }
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to clear log file" }
            }
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"
    }
}
