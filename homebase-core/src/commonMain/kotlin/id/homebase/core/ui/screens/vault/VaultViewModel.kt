@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.DriveSyncManager
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.vaultDefaultSections
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.sync.DriveRegistry
import id.homebase.core.ui.screens.vault.model.VaultSectionUiModel
import id.homebase.core.ui.screens.vault.model.toSectionUiModel
import id.homebase.core.util.resolveContentType
import id.homebase.core.vault.VaultPreferences
import id.homebase.api.client.KeyHeader
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import id.homebase.api.sync.DriveState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val driveRegistry: DriveRegistry,
    private val localAttachmentStore: LocalAttachmentContextStore,
    private val driveSyncManager: DriveSyncManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VaultUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()

    val vaultExtendPermissionViewModel: ExtendPermissionViewModel
        get() = vaultPermissionViewModel

    // Maps outbox uniqueId -> sectionId for grouping.
    // Thread-confined to Dispatchers.Main via viewModelScope.
    private val uploadTracker = mutableMapOf<Uuid, Uuid>()

    private val _isActivated = MutableStateFlow<Boolean?>(null)
    val isActivated: StateFlow<Boolean?> = _isActivated.asStateFlow()

    private suspend fun isVaultRegistered(): Boolean {
        val drives = driveRegistry.bootstrap()
        val found = drives.any { it.drive.alias == vaultLabeledDrive.drive.alias }
        Logger.i(tag = TAG) { "isVaultRegistered: $found (registry has ${drives.size} drive(s))" }
        return found
    }

    init {
        viewModelScope.launch {
            val hasDrive = isVaultRegistered()
            _isActivated.value = hasDrive
            Logger.i(tag = TAG) { "init: isActivated=$hasDrive" }
            if (hasDrive) {
                try {
                    authConnectionCoordinator.mountDrive(vaultLabeledDrive)
                    Logger.i(tag = TAG) { "init: mountDrive succeeded" }
                } catch (e: Exception) {
                    Logger.w(e, TAG) { "mountDrive on init failed (non-fatal)" }
                }
            }
        }

        // When user grants permissions via the extend-permission dialog (browser callback),
        // automatically proceed with vault activation — no need to tap Setup again.
        viewModelScope.launch {
            vaultPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (_isActivated.value != true) {
                        handleActivation()
                    }
                }
        }

        observeVaultData()
        observeDriveSyncStatus()
        observeOutboxFailures()
    }

    private fun observeDriveSyncStatus() {
        viewModelScope.launch {
            driveSyncManager.driveStatuses
                .map { it[vaultLabeledDrive.drive.alias]?.state }
                .collect { state ->
                    if (state != null && _isActivated.value != true) {
                        _isActivated.value = true
                        Logger.i(tag = TAG) { "observeDriveSyncStatus: vault drive appeared in driveStatuses — setting isActivated=true" }
                    }
                    val isSyncing = state is DriveState.Synchronizing
                    _uiState.update { it.copy(isSyncing = isSyncing) }
                }
        }
    }

    private suspend fun handleActivation() {
        _uiState.update { it.copy(isCheckingPermissions = false) }
        val alreadyRegistered = isVaultRegistered()
        if (!alreadyRegistered) {
            Logger.i(tag = TAG) { "activation: first-time setup — mounting drive + creating sections" }
            authConnectionCoordinator.mountDrive(vaultLabeledDrive)
            createDefaultSections()
        } else {
            Logger.i(tag = TAG) { "activation: vault already registered — mounting drive" }
            authConnectionCoordinator.mountDrive(vaultLabeledDrive)
        }
        _isActivated.value = true
        _events.tryEmit(VaultUiEvent.Activated)
    }

    private suspend fun createDefaultSections() {
        val existingIds = vaultRepository.loadAllVaultData().sections
            .mapNotNull { (file, _) -> file.fileMetadata.appData.uniqueId }
            .toSet()
        Logger.i(tag = TAG) { "createDefaultSections: ${existingIds.size} existing section(s) in local DB" }
        vaultDefaultSections.forEachIndexed { index, (id, title) ->
            if (id !in existingIds) {
                Logger.i(tag = TAG) { "createDefaultSections: creating '$title' ($id)" }
                vaultRepository.createSection(id, VaultSectionContent(title, index))
            } else {
                Logger.d(tag = TAG) { "createDefaultSections: '$title' ($id) already exists — skipping" }
            }
        }
    }

    fun onAction(action: VaultUiAction) {
        when (action) {
            is VaultUiAction.EntryClicked,
            is VaultUiAction.AddSection,
            is VaultUiAction.RenameSection,
            is VaultUiAction.DeleteSection,
            is VaultUiAction.MoveSectionUp,
            is VaultUiAction.MoveSectionDown,
            is VaultUiAction.AddEntryToSection,
            is VaultUiAction.AppendPages,
            is VaultUiAction.DeletePage,
            is VaultUiAction.UpdateNotes,
            is VaultUiAction.UpdateLabel,
            is VaultUiAction.SharePage,
            is VaultUiAction.ShareFile,
            is VaultUiAction.RenameFile,
            is VaultUiAction.DeleteFile,
            is VaultUiAction.CloseOverlay -> vaultPreferences.recordUserAction()
            else -> {}
        }

        when (action) {
            VaultUiAction.SetupClicked -> {
                _uiState.update { it.copy(isCheckingPermissions = true) }
                if (vaultPermissionViewModel.permissionsGranted.value) {
                    viewModelScope.launch { handleActivation() }
                } else {
                    vaultPermissionViewModel.recheckPermissions()
                }
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
            is VaultUiAction.UpdateLabel -> handleUpdateLabel(action)
            is VaultUiAction.SharePage -> handleSharePage(action)
            is VaultUiAction.EntryClicked -> handleEntryClicked(action)
            is VaultUiAction.ShareFile -> handleShareFile(action)
            is VaultUiAction.RenameFile -> handleRenameFile(action)
            is VaultUiAction.DeleteFile -> handleDeleteFile(action)
            VaultUiAction.CloseOverlay -> _uiState.update { it.copy(fullScreenOverlay = null) }
            VaultUiAction.RefreshFiles -> { /* handled by observeVaultData Flow */
            }
        }
    }

    // region Data observation

    private var hasSyncedOnce = false

    private fun observeVaultData() {
        viewModelScope.launch {
            vaultRepository.observeVaultData().collect { data ->
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
                val fileCount = data.filesBySection.values.sumOf { it.size }
                Logger.i(tag = TAG) { "observeVaultData: ${sectionModels.size} section(s), $fileCount file(s)" }

                if (sectionModels.isEmpty() && !hasSyncedOnce) {
                    hasSyncedOnce = true
                    Logger.i(tag = TAG) { "observeVaultData: empty — triggering syncDrive" }
                    try {
                        driveSyncManager.syncDrive(vaultLabeledDrive.drive.alias)
                    } catch (e: Exception) {
                        Logger.d(tag = TAG) { "syncDrive on empty load: ${e.message}" }
                    }
                }
                _uiState.update { current ->
                    val refreshedOverlay = (current.fullScreenOverlay as? VaultOverlay.Gallery)?.let { gallery ->
                        val freshFile = sectionModels
                            .flatMap { it.entries }
                            .find { it.uniqueId == gallery.file.uniqueId }
                        if (freshFile != null) gallery.copy(file = freshFile) else gallery
                    }
                    current.copy(
                        sections = sectionModels,
                        isLoading = false,
                        fullScreenOverlay = refreshedOverlay ?: current.fullScreenOverlay,
                    )
                }
            }
        }
    }


    private fun handleAddSection(title: String) {
        viewModelScope.launch {
            val sections = _uiState.value.sections
            val maxOrder = sections.maxOfOrNull { it.sortOrder } ?: -1
            val sectionId = Uuid.random()
            val success = vaultRepository.createSection(
                sectionId,
                VaultSectionContent(title, maxOrder + 1),
            )
            if (success) {
                _uiState.update { state ->
                    val updated = state.sections + VaultSectionUiModel(
                        sectionId = sectionId,
                        fileId = sectionId,
                        title = title,
                        sortOrder = maxOrder + 1,
                        entries = emptyList(),
                        isFirst = sections.isEmpty(),
                        isLast = true,
                    )
                    state.copy(sections = updated)
                }
            } else {
                _events.tryEmit(VaultUiEvent.Error(VaultError.CreateSectionFailed))
            }
        }
    }

    private fun handleRenameSection(section: VaultSectionUiModel, newTitle: String) {
        viewModelScope.launch {
            val keyHeader = section.keyHeader ?: return@launch
            val success = vaultRepository.updateSection(
                sectionUniqueId = section.sectionId,
                sectionContent = VaultSectionContent(newTitle, section.sortOrder),
                versionTag = section.versionTag,
                keyHeader = keyHeader,
            )
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.RenameSectionFailed))
        }
    }

    private fun handleDeleteSection(section: VaultSectionUiModel) {
        viewModelScope.launch {
            val success = vaultRepository.deleteSection(section.sectionId, section.fileId)
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.DeleteSectionFailed))
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
                a.sectionId,
                VaultSectionContent(a.title, b.sortOrder),
                a.versionTag,
                aKey,
            )
            vaultRepository.updateSection(
                b.sectionId,
                VaultSectionContent(b.title, a.sortOrder),
                b.versionTag,
                bKey,
            )
        }
    }

    // endregion

    // region Entry handlers

    private fun handleAddEntryToSection(action: VaultUiAction.AddEntryToSection) {
        val files = action.files
        if (files.isEmpty()) return

        val firstName = files.first().name
        val firstContentType = resolveContentType(firstName, files.first().mimeType()?.toString())
        val pendingId = Uuid.random()

        val pendingItem = VaultFileItem(
            fileId = pendingId,
            uniqueId = pendingId,
            driveId = Uuid.NIL,
            fileName = firstName,
            contentType = firstContentType,
            sizeBytes = 0L,
            createdAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            previewThumbnail = null,
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
                val ct = resolveContentType(file.name, file.mimeType()?.toString())
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
                fileData.forEachIndexed { index, (path, _) ->
                    val payloadKey = "vlt_pg_${index.toString().padStart(2, '0')}"
                    localAttachmentStore.put(
                        uniqueId,
                        payloadKey,
                        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null),
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
                _events.tryEmit(VaultUiEvent.Error(VaultError.UploadFailed(firstName)))
            }
        }
    }

    private fun handleEntryClicked(action: VaultUiAction.EntryClicked) {
        val file = action.file
        if (file.isPending || file.uploadStatus != null) return
        _uiState.update { it.copy(fullScreenOverlay = VaultOverlay.Gallery(file)) }
    }

    private fun handleShareFile(action: VaultUiAction.ShareFile) {
        val file = action.file
        if (file.isPending) return
        viewModelScope.launch {
            val firstKey = file.payloadDescriptors.firstOrNull()?.key ?: return@launch
            val tempPath = vaultRepository.downloadPayload(file, firstKey)
            if (tempPath != null) {
                _events.tryEmit(
                    VaultUiEvent.ShareFileReady(tempPath, file.fileName, file.contentType),
                )
            } else {
                _events.tryEmit(VaultUiEvent.Error(VaultError.DownloadFailed))
            }
        }
    }

    private fun handleRenameFile(action: VaultUiAction.RenameFile) {
        viewModelScope.launch {
            val file = action.file
            val success = vaultRepository.renameFile(
                uniqueId = file.uniqueId,
                newName = action.newName,
                existingLabel = file.label,
                existingNotes = file.notes,
                groupId = file.groupId,
                versionTag = file.versionTag,
                keyHeader = file.keyHeader,
            )
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.RenameFileFailed(file.fileName)))
        }
    }

    private fun handleDeleteFile(action: VaultUiAction.DeleteFile) {
        viewModelScope.launch {
            val success = vaultRepository.deleteFile(action.file.uniqueId, action.file.fileId)
            if (success) {
                _uiState.update { it.copy(fullScreenOverlay = null) }
            } else {
                _events.tryEmit(VaultUiEvent.Error(VaultError.DeleteFileFailed(action.file.fileName)))
            }
        }
    }

    private fun handleAppendPages(action: VaultUiAction.AppendPages) {
        viewModelScope.launch {
            val fileData = action.newFiles.map { file ->
                val ct = resolveContentType(file.name, file.mimeType()?.toString())
                file.path to ct
            }
            val success = vaultRepository.appendPages(
                file = action.file,
                newFiles = fileData,
                scope = viewModelScope,
            )
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.AppendPagesFailed))
        }
    }

    private fun handleDeletePage(action: VaultUiAction.DeletePage) {
        val isLastPage = action.file.payloadDescriptors.size <= 1
        viewModelScope.launch {
            val success = vaultRepository.deletePage(action.file, action.payloadKey)
            if (success) {
                if (isLastPage) _uiState.update { it.copy(fullScreenOverlay = null) }
            } else {
                _events.tryEmit(VaultUiEvent.Error(VaultError.DeletePageFailed))
            }
        }
    }

    private fun handleUpdateNotes(action: VaultUiAction.UpdateNotes) {
        viewModelScope.launch {
            val success = vaultRepository.updateNotes(action.file, action.notes)
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.SaveNotesFailed))
        }
    }

    private fun handleUpdateLabel(action: VaultUiAction.UpdateLabel) {
        viewModelScope.launch {
            val success = vaultRepository.updateLabel(
                uniqueId = action.file.uniqueId,
                existingName = action.file.fileName,
                newLabel = action.label?.ifBlank { null },
                existingNotes = action.file.notes,
                groupId = action.file.groupId,
                versionTag = action.file.versionTag,
                keyHeader = action.file.keyHeader,
            )
            if (!success) {
                _events.tryEmit(VaultUiEvent.Error(VaultError.UpdateLabelFailed(action.file.fileName)))
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
                _events.tryEmit(VaultUiEvent.Error(VaultError.DownloadPageFailed))
            }
        }
    }

    // endregion

    // region Outbox failure observer

    private fun observeOutboxFailures() {
        val vaultDriveId = vaultLabeledDrive.drive.alias
        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.OutboxEvent.ItemCompleted && it.driveId == vaultDriveId }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemCompleted
                    uploadTracker.remove(event.uniqueId)
                }
        }
        viewModelScope.launch {
            eventBus.events
                .filter {
                    (it is BackendEvent.OutboxEvent.ItemFailed && it.driveId == vaultDriveId) ||
                            (it is BackendEvent.OutboxEvent.OutboxItemDropped && it.driveId == vaultDriveId)
                }
                .collect { event ->
                    val uniqueId = when (event) {
                        is BackendEvent.OutboxEvent.ItemFailed -> event.uniqueId
                        is BackendEvent.OutboxEvent.OutboxItemDropped -> event.uniqueId
                        else -> return@collect
                    }
                    if (uploadTracker.remove(uniqueId) != null) {
                        _events.tryEmit(VaultUiEvent.Error(VaultError.OutboxUploadFailed))
                    }
                }
        }
    }

    // endregion
}
