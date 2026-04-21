@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.vault.ViewMode
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultViewModel"

class VaultViewModel(
    private val vaultPreferences: VaultPreferences,
    private val vaultPermissionViewModel: ExtendPermissionViewModel,
    private val vaultRepository: VaultRepository,
    private val eventBus: EventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VaultUiState(viewMode = vaultPreferences.viewMode.value)
    )
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VaultUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()

    val vaultExtendPermissionViewModel: ExtendPermissionViewModel
        get() = vaultPermissionViewModel

    // Maps outbox uniqueId → pending UI item tempId
    private val uploadTracker = mutableMapOf<Uuid, Uuid>()

    init {
        viewModelScope.launch {
            vaultPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!vaultPreferences.activated.value) {
                        vaultPreferences.setActivated(true)
                        _uiState.update { it.copy(isCheckingPermissions = false) }
                        _events.tryEmit(VaultUiEvent.Activated)
                    }
                }
        }

        observeDriveSync()
        observeOutboxEvents()
        loadFiles()
    }

    fun onAction(action: VaultUiAction) {
        when (action) {
            VaultUiAction.SetupClicked -> {
                _uiState.update { it.copy(isCheckingPermissions = true) }
                vaultPermissionViewModel.recheckPermissions()
            }

            VaultUiAction.DismissOnboardingClicked -> {
                viewModelScope.launch {
                    vaultPreferences.setIconVisible(false)
                    _events.tryEmit(VaultUiEvent.CloseOnboarding)
                }
            }

            is VaultUiAction.FileSelected -> handleFileSelected(action)
            is VaultUiAction.FileClicked -> handleFileClicked(action)
            is VaultUiAction.DeleteFile -> handleDeleteFile(action)
            is VaultUiAction.RenameFile -> handleRenameFile(action)
            is VaultUiAction.ShareFile -> handleShareFile(action)
            VaultUiAction.ToggleViewMode -> handleToggleViewMode()
            VaultUiAction.CloseOverlay -> _uiState.update { it.copy(fullScreenOverlay = null) }
            VaultUiAction.RefreshFiles -> loadFiles()
        }
    }

    private fun observeDriveSync() {
        val vaultDriveId = vaultLabeledDrive.drive.alias

        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.DriveEvent.BatchReceived && it.driveId == vaultDriveId }
                .collect { event ->
                    event as BackendEvent.DriveEvent.BatchReceived
                    processIncrementalBatch(event.batchData)
                }
        }
    }

    private fun processIncrementalBatch(files: List<HomebaseFile>) {
        val incoming = files.mapNotNull { it.toVaultFileItem() }
        if (incoming.isEmpty()) return

        _uiState.update { state ->
            val existingById = state.files.associateBy { it.fileId }.toMutableMap()
            for (item in incoming) {
                existingById[item.fileId] = item
            }
            state.copy(files = existingById.values.sortedByDescending { it.createdAt })
        }
    }

    private fun observeOutboxEvents() {
        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.OutboxEvent.ItemProgress }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemProgress
                    val tempId = uploadTracker[event.uniqueId] ?: return@collect
                    updateUploadStatus(tempId, VaultUploadStatus.Uploading(event.progress / 100f))
                }
        }

        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.OutboxEvent.ItemCompleted }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemCompleted
                    val tempId = uploadTracker.remove(event.uniqueId) ?: return@collect
                    updateUploadStatus(tempId, VaultUploadStatus.Completed)
                    delay(500)
                    removePendingItem(tempId)
                }
        }

        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.OutboxEvent.ItemFailed }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemFailed
                    val tempId = uploadTracker[event.uniqueId] ?: return@collect
                    updateUploadStatus(tempId, VaultUploadStatus.Failed("Upload failed"))
                }
        }

        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.OutboxEvent.OutboxItemDropped }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.OutboxItemDropped
                    val tempId = uploadTracker.remove(event.uniqueId) ?: return@collect
                    updateUploadStatus(tempId, VaultUploadStatus.Failed("Upload failed after ${event.attempts} attempts"))
                    _events.tryEmit(VaultUiEvent.Error("Upload permanently failed"))
                }
        }
    }

    private fun handleFileSelected(action: VaultUiAction.FileSelected) {
        val file = action.file
        val tempId = Uuid.random()
        val fileName = file.name
        val filePath = file.toString()
        val contentType = file.mimeType()?.toString() ?: guessContentType(fileName)

        val pendingItem = VaultFileItem(
            fileId = tempId,
            driveId = vaultLabeledDrive.drive.alias,
            fileName = fileName,
            contentType = contentType,
            sizeBytes = 0L,
            createdAt = Clock.System.now().toEpochMilliseconds(),
            previewThumbnail = null,
            payloadKey = "",
            keyHeader = KeyHeader.newRandom16(),
            isEncrypted = true,
            versionTag = null,
            uploadStatus = VaultUploadStatus.Preparing,
            pendingFileUri = filePath,
        )

        _uiState.update { state ->
            state.copy(files = listOf(pendingItem) + state.files)
        }

        viewModelScope.launch {
            val uniqueId = vaultRepository.uploadFile(
                fileName = fileName,
                contentType = contentType,
                filePath = filePath,
                scope = viewModelScope,
            )

            if (uniqueId != null) {
                uploadTracker[uniqueId] = tempId
                updateUploadStatus(tempId, VaultUploadStatus.Uploading(0f))
            } else {
                updateUploadStatus(tempId, VaultUploadStatus.Failed("Failed to prepare upload"))
                _events.tryEmit(VaultUiEvent.Error("Failed to upload $fileName"))
            }
        }
    }

    private fun handleFileClicked(action: VaultUiAction.FileClicked) {
        val file = action.file
        if (file.isPending) return
        _uiState.update { it.copy(fullScreenOverlay = VaultOverlay.Preview(file)) }
    }

    private fun handleDeleteFile(action: VaultUiAction.DeleteFile) {
        val file = action.file

        _uiState.update { state ->
            val updatedFiles = state.files.filter { it.fileId != file.fileId }
            val updatedOverlay = when (val overlay = state.fullScreenOverlay) {
                is VaultOverlay.Preview -> if (overlay.file.fileId == file.fileId) null else overlay
                null -> null
            }
            state.copy(files = updatedFiles, fullScreenOverlay = updatedOverlay)
        }

        viewModelScope.launch {
            val success = vaultRepository.deleteFile(file.fileId)
            if (!success) {
                _events.tryEmit(VaultUiEvent.Error("Failed to delete ${file.fileName}"))
            }
        }
    }

    private fun handleRenameFile(action: VaultUiAction.RenameFile) {
        val file = action.file
        val newName = action.newName

        _uiState.update { state ->
            state.copy(
                files = state.files.map {
                    if (it.fileId == file.fileId) it.copy(fileName = newName) else it
                }
            )
        }

        viewModelScope.launch {
            val success = vaultRepository.renameFile(
                fileId = file.fileId,
                newName = newName,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
            )
            if (!success) {
                _events.tryEmit(VaultUiEvent.Error("Failed to rename ${file.fileName}"))
            }
        }
    }

    @Suppress("UnusedParameter")
    private fun handleShareFile(action: VaultUiAction.ShareFile) {
        _events.tryEmit(VaultUiEvent.Error("Sharing is not yet supported"))
    }

    private fun handleToggleViewMode() {
        val newMode = when (_uiState.value.viewMode) {
            ViewMode.List -> ViewMode.Grid
            ViewMode.Grid -> ViewMode.List
        }
        _uiState.update { it.copy(viewMode = newMode) }
        viewModelScope.launch {
            vaultPreferences.setViewMode(newMode)
        }
    }

    // region Helpers

    private fun loadFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val files = vaultRepository.loadFiles()
                _uiState.update { state ->
                    val pendingItems = state.files.filter { it.isPending }
                    state.copy(
                        files = pendingItems + files,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to load vault files" }
                _uiState.update { it.copy(isLoading = false) }
                _events.tryEmit(VaultUiEvent.Error("Failed to load files"))
            }
        }
    }

    private fun updateUploadStatus(tempId: Uuid, status: VaultUploadStatus) {
        _uiState.update { state ->
            state.copy(
                files = state.files.map { item ->
                    if (item.fileId == tempId) item.copy(uploadStatus = status) else item
                }
            )
        }
    }

    private fun removePendingItem(tempId: Uuid) {
        _uiState.update { state ->
            state.copy(files = state.files.filter { it.fileId != tempId })
        }
    }

    // endregion

}
