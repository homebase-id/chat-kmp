package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.photobase.backup.BackupSettings
import id.homebase.photobase.backup.BackupStatus
import id.homebase.photobase.backup.DeviceAlbum
import id.homebase.photobase.backup.PhotoBackupController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class BackupSettingsUiState(
    val isSupported: Boolean = false,
    val settings: BackupSettings = BackupSettings(),
    val status: BackupStatus = BackupStatus(),
    val albums: List<DeviceAlbum> = emptyList(),
)

sealed interface BackupSettingsUiAction {
    data class SetEnabled(val enabled: Boolean) : BackupSettingsUiAction
    data class SetWifiOnly(val wifiOnly: Boolean) : BackupSettingsUiAction
    data class SetVideosOnCellular(val allowed: Boolean) : BackupSettingsUiAction
    data class SetChargingOnly(val chargingOnly: Boolean) : BackupSettingsUiAction
    data class SetOnlyNewFromNow(val onlyNew: Boolean) : BackupSettingsUiAction
    data class SetAlbums(val albumIds: Set<String>) : BackupSettingsUiAction
    data class SetPushInterval(val intervalMs: Long) : BackupSettingsUiAction
    data object LoadAlbums : BackupSettingsUiAction
    data object BackUpNow : BackupSettingsUiAction
    data object RetryFailures : BackupSettingsUiAction
    data object PermissionChanged : BackupSettingsUiAction
}

class BackupSettingsViewModel(
    private val controller: PhotoBackupController,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupSettingsUiState(isSupported = controller.isSupported))
    val uiState: StateFlow<BackupSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            controller.settings.collect { settings -> _uiState.update { it.copy(settings = settings) } }
        }
        controller.status?.let { status ->
            viewModelScope.launch {
                status.collect { value -> _uiState.update { it.copy(status = value) } }
            }
        }
        controller.refreshStatus()
    }

    fun onAction(action: BackupSettingsUiAction) {
        when (action) {
            is BackupSettingsUiAction.SetEnabled -> controller.update { it.copy(enabled = action.enabled) }
            is BackupSettingsUiAction.SetWifiOnly -> controller.update { it.copy(wifiOnly = action.wifiOnly) }
            is BackupSettingsUiAction.SetVideosOnCellular -> controller.update { it.copy(videosOnCellular = action.allowed) }
            is BackupSettingsUiAction.SetChargingOnly -> controller.update { it.copy(chargingOnly = action.chargingOnly) }
            is BackupSettingsUiAction.SetOnlyNewFromNow -> controller.update {
                it.copy(onlyNewFromMs = if (action.onlyNew) clock.now().toEpochMilliseconds() else null)
            }
            is BackupSettingsUiAction.SetAlbums -> controller.update { it.copy(albumIds = action.albumIds) }
            is BackupSettingsUiAction.SetPushInterval -> controller.update { it.copy(pushIntervalMs = action.intervalMs) }
            BackupSettingsUiAction.LoadAlbums -> viewModelScope.launch {
                val albums = controller.albums()
                _uiState.update { it.copy(albums = albums) }
            }
            BackupSettingsUiAction.BackUpNow -> controller.backUpNow()
            BackupSettingsUiAction.RetryFailures -> controller.retryFailures()
            BackupSettingsUiAction.PermissionChanged -> controller.onAppForeground()
        }
    }
}
