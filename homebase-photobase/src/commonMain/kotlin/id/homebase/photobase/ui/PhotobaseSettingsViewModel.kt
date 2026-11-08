package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photobase.backup.BackupSettings
import id.homebase.photobase.backup.PhotoBackupController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PushIntervalError { Load, Save }

data class PhotobaseSettingsUiState(
    val pushIntervalMs: Long = BackupSettings.DEFAULT_PUSH_INTERVAL.inWholeMilliseconds,
    val error: PushIntervalError? = null,
)

sealed interface PhotobaseSettingsUiAction {
    data class SetPushInterval(val intervalMs: Long) : PhotobaseSettingsUiAction
    data object RetryLoad : PhotobaseSettingsUiAction
}

class PhotobaseSettingsViewModel(private val controller: PhotoBackupController) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotobaseSettingsUiState())
    val uiState: StateFlow<PhotobaseSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            controller.settings.collect { settings -> _uiState.update { it.copy(pushIntervalMs = settings.pushIntervalMs) } }
        }
        load()
    }

    fun onAction(action: PhotobaseSettingsUiAction) {
        when (action) {
            is PhotobaseSettingsUiAction.SetPushInterval -> attempt(PushIntervalError.Save) { controller.setPushInterval(action.intervalMs) }
            PhotobaseSettingsUiAction.RetryLoad -> load()
        }
    }

    private fun load() = attempt(PushIntervalError.Load) { controller.loadPushInterval() }

    private fun attempt(onFailure: PushIntervalError, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "push interval $onFailure failed" }
                _uiState.update { it.copy(error = onFailure) }
            }
        }
    }

    private companion object {
        const val TAG = "PhotobaseSettingsVM"
    }
}
