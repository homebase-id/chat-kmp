@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.ui.screens.vault.model.VaultSectionUiModel
import id.homebase.core.ui.screens.vault.model.toSectionUiModel
import id.homebase.core.vault.VaultPreferences
import id.homebase.api.client.KeyHeader
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultViewModel"

class VaultViewModel(
    private val vaultPreferences: VaultPreferences,
    private val vaultPermissionViewModel: ExtendPermissionViewModel,
    private val vaultRepository: VaultRepository,
    private val eventBus: EventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VaultUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()

    val vaultExtendPermissionViewModel: ExtendPermissionViewModel
        get() = vaultPermissionViewModel

    // Maps outbox uniqueId -> sectionId for grouping
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
        loadSections()
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

            is VaultUiAction.AddSection -> handleAddSection(action.title)
            is VaultUiAction.RenameSection -> handleRenameSection(action.section, action.newTitle)
            is VaultUiAction.DeleteSection -> handleDeleteSection(action.section)
            is VaultUiAction.MoveSectionUp -> handleMoveSectionUp(action.section)
            is VaultUiAction.MoveSectionDown -> handleMoveSectionDown(action.section)
            is VaultUiAction.AddEntryToSection -> handleAddEntryToSection(action)
            is VaultUiAction.AppendPages -> handleAppendPages(action)
            is VaultUiAction.DeletePage -> handleDeletePage(action)
            is VaultUiAction.UpdateNotes -> handleUpdateNotes(action)
            is VaultUiAction.SharePage -> handleSharePage(action)
            is VaultUiAction.EntryClicked -> handleEntryClicked(action)
            is VaultUiAction.ShareFile -> handleShareFile(action)
            is VaultUiAction.RenameFile -> handleRenameFile(action)
            is VaultUiAction.DeleteFile -> handleDeleteFile(action)
            VaultUiAction.CloseOverlay -> _uiState.update { it.copy(fullScreenOverlay = null) }
            VaultUiAction.RefreshFiles -> loadSections()
        }
    }

    // region Section loading

    private fun loadSections() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val data = vaultRepository.loadAllVaultData()
                val sortedSections = data.sections.sortedBy { it.second.sortOrder }
                val sectionModels = sortedSections.mapIndexed { index, pair ->
                    val (file, _) = pair
                    val sectionId = file.fileMetadata.appData.uniqueId ?: file.fileId
                    val entries = data.filesBySection[sectionId] ?: emptyList()
                    pair.toSectionUiModel(
                        entries = entries,
                        isFirst = index == 0,
                        isLast = index == sortedSections.size - 1,
                    )
                }
                _uiState.update { it.copy(sections = sectionModels, isLoading = false) }
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to load vault sections" }
                _uiState.update { it.copy(isLoading = false) }
                _events.tryEmit(VaultUiEvent.Error("Failed to load vault"))
            }
        }
    }

    // endregion

    // region Section handlers

    private fun handleAddSection(title: String) {
        viewModelScope.launch {
            val maxOrder = _uiState.value.sections.maxOfOrNull { it.sortOrder } ?: -1
            val success = vaultRepository.createSection(
                Uuid.random(),
                VaultSectionContent(title, maxOrder + 1),
            )
            if (success) loadSections() else _events.tryEmit(VaultUiEvent.Error("Failed to create section"))
        }
    }

    private fun handleRenameSection(section: VaultSectionUiModel, newTitle: String) {
        viewModelScope.launch {
            val keyHeader = section.keyHeader ?: return@launch
            val success = vaultRepository.updateSection(
                sectionFileId = section.fileId,
                sectionContent = VaultSectionContent(newTitle, section.sortOrder),
                versionTag = section.versionTag,
                keyHeader = keyHeader,
            )
            if (success) loadSections() else _events.tryEmit(VaultUiEvent.Error("Failed to rename section"))
        }
    }

    private fun handleDeleteSection(section: VaultSectionUiModel) {
        viewModelScope.launch {
            val success = vaultRepository.deleteSection(section.sectionId, section.fileId)
            if (success) loadSections() else _events.tryEmit(VaultUiEvent.Error("Failed to delete section"))
        }
    }

    private fun handleMoveSectionUp(section: VaultSectionUiModel) {
        val sections = _uiState.value.sections
        val idx = sections.indexOfFirst { it.sectionId == section.sectionId }
        if (idx <= 0) return
        swapSectionOrder(sections[idx], sections[idx - 1])
    }

    private fun handleMoveSectionDown(section: VaultSectionUiModel) {
        val sections = _uiState.value.sections
        val idx = sections.indexOfFirst { it.sectionId == section.sectionId }
        if (idx < 0 || idx >= sections.size - 1) return
        swapSectionOrder(sections[idx], sections[idx + 1])
    }

    private fun swapSectionOrder(a: VaultSectionUiModel, b: VaultSectionUiModel) {
        viewModelScope.launch {
            val aKey = a.keyHeader ?: return@launch
            val bKey = b.keyHeader ?: return@launch
            vaultRepository.updateSection(
                a.fileId,
                VaultSectionContent(a.title, b.sortOrder),
                a.versionTag,
                aKey,
            )
            vaultRepository.updateSection(
                b.fileId,
                VaultSectionContent(b.title, a.sortOrder),
                b.versionTag,
                bKey,
            )
            loadSections()
        }
    }

    // endregion

    // region Entry handlers

    private fun handleAddEntryToSection(action: VaultUiAction.AddEntryToSection) {
        val files = action.files
        if (files.isEmpty()) return

        val firstName = files.first().name
        val firstContentType = files.first().mimeType()?.toString() ?: guessContentType(firstName)
        val pendingId = Uuid.random()

        val pendingItem = VaultFileItem(
            fileId = pendingId,
            driveId = Uuid.NIL,
            fileName = firstName,
            contentType = firstContentType,
            sizeBytes = 0L,
            createdAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            previewThumbnail = null,
            payloadKey = "",
            keyHeader = KeyHeader.empty(),
            isEncrypted = false,
            versionTag = null,
            uploadStatus = VaultUploadStatus.Preparing,
            pendingFileUri = files.first().path,
            groupId = action.sectionId,
        )

        _uiState.update { state ->
            state.copy(
                sections = state.sections.map { section ->
                    if (section.sectionId == action.sectionId) {
                        section.copy(entries = section.entries + pendingItem)
                    } else section
                },
            )
        }

        viewModelScope.launch {
            val fileData = files.map { file ->
                val ct = file.mimeType()?.toString() ?: guessContentType(file.name)
                file.path to ct
            }
            val uniqueId = vaultRepository.uploadFile(
                entryName = firstName,
                files = fileData,
                scope = viewModelScope,
                groupId = action.sectionId,
            )
            if (uniqueId != null) {
                uploadTracker[uniqueId] = action.sectionId
                _uiState.update { state ->
                    state.copy(
                        sections = state.sections.map { section ->
                            if (section.sectionId == action.sectionId) {
                                section.copy(
                                    entries = section.entries.map { entry ->
                                        if (entry.fileId == pendingId) {
                                            entry.copy(uploadStatus = VaultUploadStatus.Uploading(0f))
                                        } else entry
                                    },
                                )
                            } else section
                        },
                    )
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        sections = state.sections.map { section ->
                            if (section.sectionId == action.sectionId) {
                                section.copy(
                                    entries = section.entries.map { entry ->
                                        if (entry.fileId == pendingId) {
                                            entry.copy(uploadStatus = VaultUploadStatus.Failed("Upload failed"))
                                        } else entry
                                    },
                                )
                            } else section
                        },
                    )
                }
                _events.tryEmit(VaultUiEvent.Error("Failed to upload $firstName"))
            }
        }
    }

    private fun handleEntryClicked(action: VaultUiAction.EntryClicked) {
        val file = action.file
        if (file.isPending) return
        _uiState.update { it.copy(fullScreenOverlay = VaultOverlay.Gallery(file)) }
    }

    private fun handleShareFile(action: VaultUiAction.ShareFile) {
        val file = action.file
        if (file.isPending) return
        viewModelScope.launch {
            val tempPath = vaultRepository.downloadPayload(file, file.payloadKey)
            if (tempPath != null) {
                _events.tryEmit(
                    VaultUiEvent.ShareFileReady(tempPath, file.fileName, file.contentType),
                )
            } else {
                _events.tryEmit(VaultUiEvent.Error("Failed to download file for sharing"))
            }
        }
    }

    private fun handleRenameFile(action: VaultUiAction.RenameFile) {
        viewModelScope.launch {
            val file = action.file
            val success = vaultRepository.renameFile(
                fileId = file.fileId,
                newName = action.newName,
                existingNotes = file.notes,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
            )
            if (success) {
                loadSections()
            } else {
                _events.tryEmit(VaultUiEvent.Error("Failed to rename ${file.fileName}"))
            }
        }
    }

    private fun handleDeleteFile(action: VaultUiAction.DeleteFile) {
        viewModelScope.launch {
            val success = vaultRepository.deleteFile(action.file.fileId)
            if (success) {
                _uiState.update { it.copy(fullScreenOverlay = null) }
                loadSections()
            } else {
                _events.tryEmit(VaultUiEvent.Error("Failed to delete ${action.file.fileName}"))
            }
        }
    }

    private fun handleAppendPages(action: VaultUiAction.AppendPages) {
        viewModelScope.launch {
            val fileData = action.newFiles.map { file ->
                val ct = file.mimeType()?.toString() ?: guessContentType(file.name)
                file.path to ct
            }
            val success = vaultRepository.appendPages(
                file = action.file,
                newFiles = fileData,
                scope = viewModelScope,
            )
            if (success) {
                loadSections()
            } else {
                _events.tryEmit(VaultUiEvent.Error("Failed to add pages"))
            }
        }
    }

    private fun handleDeletePage(action: VaultUiAction.DeletePage) {
        val isLastPage = action.file.payloadDescriptors.size <= 1
        viewModelScope.launch {
            val success = vaultRepository.deletePage(action.file, action.payloadKey)
            if (success) {
                if (isLastPage) {
                    _uiState.update { it.copy(fullScreenOverlay = null) }
                }
                loadSections()
            } else {
                _events.tryEmit(VaultUiEvent.Error("Failed to delete page"))
            }
        }
    }

    private fun handleUpdateNotes(action: VaultUiAction.UpdateNotes) {
        viewModelScope.launch {
            val success = vaultRepository.updateNotes(action.file, action.notes)
            if (success) {
                loadSections()
            } else {
                _events.tryEmit(VaultUiEvent.Error("Failed to save notes"))
            }
        }
    }

    private fun handleSharePage(action: VaultUiAction.SharePage) {
        viewModelScope.launch {
            val tempPath = vaultRepository.downloadPayload(action.file, action.payloadKey)
            if (tempPath != null) {
                val descriptor = action.file.payloadDescriptors.find { it.key == action.payloadKey }
                val contentType = descriptor?.contentType ?: action.file.contentType
                _events.tryEmit(
                    VaultUiEvent.ShareFileReady(tempPath, action.file.fileName, contentType),
                )
            } else {
                _events.tryEmit(VaultUiEvent.Error("Failed to download page for sharing"))
            }
        }
    }

    // endregion

    // region Sync observers

    private fun observeDriveSync() {
        val vaultDriveId = vaultLabeledDrive.drive.alias
        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.DriveEvent.BatchReceived && it.driveId == vaultDriveId }
                .collect { loadSections() }
        }
    }

    private fun observeOutboxEvents() {
        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.OutboxEvent.ItemCompleted }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemCompleted
                    if (uploadTracker.remove(event.uniqueId) != null) {
                        delay(500)
                        loadSections()
                    }
                }
        }
        viewModelScope.launch {
            eventBus.events
                .filter {
                    it is BackendEvent.OutboxEvent.ItemFailed ||
                            it is BackendEvent.OutboxEvent.OutboxItemDropped
                }
                .collect { event ->
                    val uniqueId = when (event) {
                        is BackendEvent.OutboxEvent.ItemFailed -> event.uniqueId
                        is BackendEvent.OutboxEvent.OutboxItemDropped -> event.uniqueId
                        else -> return@collect
                    }
                    if (uploadTracker.remove(uniqueId) != null) {
                        _events.tryEmit(VaultUiEvent.Error("Upload failed"))
                        loadSections()
                    }
                }
        }
    }

    // endregion
}
