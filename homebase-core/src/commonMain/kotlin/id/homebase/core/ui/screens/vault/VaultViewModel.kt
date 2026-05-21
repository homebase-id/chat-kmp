@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.DriveSyncManager
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import id.homebase.core.config.vaultDefaultSections
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.sync.DriveRegistry
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import id.homebase.core.ui.screens.vault.model.VaultSectionContent
import id.homebase.core.util.resolveContentType
import id.homebase.core.vault.VaultPreferences
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.DriveState
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultViewModel"

class VaultViewModel(
    private val vaultPreferences: VaultPreferences,
    private val vaultPermissionViewModel: ExtendPermissionViewModel,
    private val vaultStream: VaultStream,
    private val vaultService: VaultService,
    private val vaultUploaderService: VaultUploaderService,
    private val eventBus: EventBus,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val driveRegistry: DriveRegistry,
    private val localAttachmentStore: LocalAttachmentContextStore,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveSyncManager: DriveSyncManager,
) : ViewModel() {

    private val _overlayState = MutableStateFlow<VaultOverlay?>(null)
    private val _syncingState = MutableStateFlow(false)
    private val _checkingPermissions = MutableStateFlow(false)

    val uiState: StateFlow<VaultUiState> = combine(
        combine(vaultStream.sections, vaultStream.entriesBySection, vaultStream.isLoaded) { s, e, l ->
            Triple(s, e, l)
        },
        _overlayState,
        _syncingState,
        _checkingPermissions,
    ) { (sections, entriesBySection, isLoaded), overlay, syncing, checkingPermissions ->
        val sectionModels = sections.mapIndexed { index, section ->
            section.copy(
                entries = entriesBySection[section.sectionId] ?: emptyList(),
                isFirst = index == 0,
                isLast = index == sections.size - 1,
            )
        }
        val refreshedOverlay = (overlay as? VaultOverlay.Gallery)?.let { gallery ->
            val freshFile = sectionModels.flatMap { it.entries }.find { it.uniqueId == gallery.file.uniqueId }
            if (freshFile != null) gallery.copy(file = freshFile) else gallery
        }
        VaultUiState(
            sections = sectionModels,
            isLoading = !isLoaded,
            isSyncing = syncing,
            isCheckingPermissions = checkingPermissions,
            fullScreenOverlay = refreshedOverlay ?: overlay,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUiState())

    private val _events = MutableSharedFlow<VaultUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()

    val vaultExtendPermissionViewModel: ExtendPermissionViewModel
        get() = vaultPermissionViewModel

    private val _isActivated = MutableStateFlow<Boolean?>(null)
    val isActivated: StateFlow<Boolean?> = _isActivated.asStateFlow()

    private suspend fun isVaultRegistered(): Boolean {
        val drives = driveRegistry.bootstrap()
        val found = drives.any { it.drive.alias == vaultLabeledDrive.drive.alias }
        Logger.i(tag = TAG) { "isVaultRegistered: $found (registry has ${drives.size} drive(s))" }
        return found
    }

    init {
        // React to VaultStream reset/load cycles (logout → login as different user).
        // When isLoaded flips false (reset) → null out activation so the UI shows
        // loading. When it flips true (loadAll done) → re-check DriveRegistry.
        viewModelScope.launch {
            vaultStream.isLoaded.collect { loaded ->
                if (!loaded) {
                    _isActivated.value = null
                } else if (_isActivated.value == null) {
                    val hasDrive = isVaultRegistered()
                    _isActivated.value = hasDrive
                    Logger.i(tag = TAG) { "isLoaded→true: isActivated=$hasDrive" }
                    if (hasDrive) {
                        try {
                            authConnectionCoordinator.mountDrive(vaultLabeledDrive)
                            driveSyncManager.syncDrive(vaultLabeledDrive.drive.alias)
                        } catch (e: Exception) {
                            Logger.w(e, TAG) { "mountDrive/sync failed (non-fatal)" }
                        }
                    }
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

        observeDriveSyncStatus()
        observeOutboxFailures()
    }

    private fun observeDriveSyncStatus() {
        viewModelScope.launch {
            driveSyncManager.driveStatuses
                .map { it[vaultLabeledDrive.drive.alias]?.state }
                .collect { state ->
                    if (state != null && _isActivated.value != true) {
                        _isActivated.update { true }
                        Logger.i(tag = TAG) { "observeDriveSyncStatus: vault drive appeared in driveStatuses — setting isActivated=true" }
                    }
                    _syncingState.update { state is DriveState.Synchronizing }
                }
        }
    }

    private suspend fun handleActivation() {
        _checkingPermissions.update { false }
        val alreadyRegistered = isVaultRegistered()
        if (!alreadyRegistered) {
            Logger.i(tag = TAG) { "activation: first-time setup — mounting drive + creating sections" }
            authConnectionCoordinator.mountDrive(vaultLabeledDrive)
            createDefaultSections()
        } else {
            Logger.i(tag = TAG) { "activation: vault already registered — mounting drive" }
            authConnectionCoordinator.mountDrive(vaultLabeledDrive)
        }
        _isActivated.update { true }
        _events.tryEmit(VaultUiEvent.Activated)
    }

    private suspend fun createDefaultSections() {
        vaultStream.loadAll()
        val existingIds = vaultStream.sections.value.map { it.sectionId }.toSet()
        Logger.i(tag = TAG) { "createDefaultSections: ${existingIds.size} existing section(s) in local DB" }
        vaultDefaultSections.forEachIndexed { index, (id, title) ->
            if (id !in existingIds) {
                Logger.i(tag = TAG) { "createDefaultSections: creating '$title' ($id)" }
                vaultService.createSection(id, VaultSectionContent(title, index))
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
            is VaultUiAction.SavePage,
            is VaultUiAction.ShareFile,
            is VaultUiAction.RenameFile,
            is VaultUiAction.DeleteFile,
            is VaultUiAction.CloseOverlay -> vaultPreferences.recordUserAction()
            else -> {}
        }

        when (action) {
            VaultUiAction.SetupClicked -> {
                _checkingPermissions.update { true }
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
            is VaultUiAction.SavePage -> handleSavePage(action)
            is VaultUiAction.EntryClicked -> handleEntryClicked(action)
            is VaultUiAction.ShareFile -> handleShareFile(action)
            is VaultUiAction.RenameFile -> handleRenameFile(action)
            is VaultUiAction.DeleteFile -> handleDeleteFile(action)
            VaultUiAction.CloseOverlay -> _overlayState.update { null }
            VaultUiAction.RefreshFiles -> { /* handled by VaultStream event observation */
            }
        }
    }

    // region Section handlers

    private fun handleAddSection(title: String) {
        viewModelScope.launch {
            val sections = vaultStream.sections.value
            val maxOrder = sections.maxOfOrNull { it.sortOrder } ?: -1
            val sectionId = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()
            val success = vaultService.createSection(
                sectionId,
                VaultSectionContent(title, maxOrder + 1),
                keyHeader,
            )
            if (success) {
                vaultStream.insertOptimisticSection(VaultSection(
                    sectionId = sectionId,
                    fileId = sectionId,
                    title = title,
                    sortOrder = maxOrder + 1,
                    entries = emptyList(),
                    keyHeader = keyHeader,
                ))
            } else {
                _events.tryEmit(VaultUiEvent.Error(VaultError.CreateSectionFailed))
            }
        }
    }

    private fun handleRenameSection(section: VaultSection, newTitle: String) {
        viewModelScope.launch {
            val keyHeader = section.keyHeader ?: return@launch
            val success = vaultService.updateSection(
                sectionUniqueId = section.sectionId,
                sectionContent = VaultSectionContent(newTitle, section.sortOrder),
                versionTag = section.versionTag,
                keyHeader = keyHeader,
            )
            if (success) {
                vaultStream.updateOptimisticSection(section.copy(title = newTitle))
            } else {
                _events.tryEmit(VaultUiEvent.Error(VaultError.RenameSectionFailed))
            }
        }
    }

    private fun handleDeleteSection(section: VaultSection) {
        vaultStream.removeSection(section.sectionId)
        viewModelScope.launch {
            val success = vaultService.deleteSection(section.sectionId, section.fileId)
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.DeleteSectionFailed))
        }
    }

    private fun handleMoveSectionUp(section: VaultSection) {
        val sections = vaultStream.sections.value
        val idx = sections.indexOfFirst { it.sectionId == section.sectionId }
        if (idx <= 0) return
        swapSectionOrder(sections[idx], sections[idx - 1])
    }

    private fun handleMoveSectionDown(section: VaultSection) {
        val sections = vaultStream.sections.value
        val idx = sections.indexOfFirst { it.sectionId == section.sectionId }
        if (idx < 0 || idx >= sections.size - 1) return
        swapSectionOrder(sections[idx], sections[idx + 1])
    }

    private fun swapSectionOrder(a: VaultSection, b: VaultSection) {
        val aKey = a.keyHeader ?: return
        val bKey = b.keyHeader ?: return

        vaultStream.updateOptimisticSection(a.copy(sortOrder = b.sortOrder))
        vaultStream.updateOptimisticSection(b.copy(sortOrder = a.sortOrder))
        vaultStream.resortSections()

        viewModelScope.launch {
            val aSuccess = vaultService.updateSection(
                a.sectionId,
                VaultSectionContent(a.title, b.sortOrder),
                a.versionTag,
                aKey,
            )
            val bSuccess = vaultService.updateSection(
                b.sectionId,
                VaultSectionContent(b.title, a.sortOrder),
                b.versionTag,
                bKey,
            )
            if (!aSuccess || !bSuccess) {
                vaultStream.updateOptimisticSection(a)
                vaultStream.updateOptimisticSection(b)
                vaultStream.resortSections()
            }
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

        val fileContentTypes = files.map { file ->
            resolveContentType(file.name, file.mimeType()?.toString())
        }

        val placeholderDescriptors = fileContentTypes.mapIndexed { index, contentType ->
            PayloadDescriptor(
                key = "vlt_pg_${index.toString().padStart(2, '0')}",
                contentType = contentType,
            )
        }

        files.forEachIndexed { index, file ->
            val payloadKey = "vlt_pg_${index.toString().padStart(2, '0')}"
            if (fileContentTypes[index] != "application/pdf") {
                localAttachmentStore.put(
                    pendingId,
                    payloadKey,
                    LocalAttachmentContext.Image(localFilePath = file.path, aspectRatio = null),
                )
            }
        }

        val pendingItem = VaultEntry(
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
            payloadDescriptors = placeholderDescriptors,
        )

        vaultStream.insertOptimisticEntry(pendingItem, action.sectionId)

        viewModelScope.launch {
            var fileData: List<Pair<String, String>> = emptyList()
            try {
                fileData = files.mapIndexed { index, file ->
                    val ext = file.name.substringAfterLast('.', "tmp")
                    val cacheDir = fileOperationsProvider.getCacheDirectory()
                    val tempPath = "$cacheDir/vault_upload_${Uuid.random()}.$ext"
                    file.copyTo(PlatformFile(tempPath))
                    tempPath to fileContentTypes[index]
                }

                if (firstContentType == "application/pdf") {
                    try {
                        val pdfResult = withContext(Dispatchers.Default) {
                            generatePdfThumbnailFromFile(fileData.first().first, 320)
                        }
                        val thumbBytes = pdfResult?.thumbnailBytes
                        if (thumbBytes != null) {
                            val thumbPath = fileOperationsProvider.writeBytesToTempFile(
                                thumbBytes, "pdf_card_", ".jpg",
                            )
                            localAttachmentStore.put(
                                pendingId,
                                VaultEntry.DEFAULT_PAYLOAD_KEY,
                                LocalAttachmentContext.Image(localFilePath = thumbPath, aspectRatio = null),
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(e, TAG) { "PDF card thumbnail generation failed" }
                    }
                }

                val uniqueId = vaultUploaderService.uploadFile(
                    entryName = firstName,
                    files = fileData,
                    scope = viewModelScope,
                    groupId = action.sectionId,
                    uniqueId = pendingId,
                )
                if (uniqueId == null) {
                    vaultStream.updateOptimisticEntry(
                        pendingItem.copy(uploadStatus = VaultUploadStatus.Failed("Upload failed"))
                    )
                    _events.tryEmit(VaultUiEvent.Error(VaultError.UploadFailed(firstName)))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                fileData.forEach { (path, _) ->
                    try { fileOperationsProvider.deleteTempFile(path) } catch (_: Exception) { }
                }
                throw e
            } catch (e: Exception) {
                fileData.forEach { (path, _) ->
                    try { fileOperationsProvider.deleteTempFile(path) } catch (_: Exception) { }
                }
                vaultStream.updateOptimisticEntry(
                    pendingItem.copy(uploadStatus = VaultUploadStatus.Failed("Upload failed"))
                )
                _events.tryEmit(VaultUiEvent.Error(VaultError.UploadFailed(firstName)))
            }
        }
    }

    private fun handleEntryClicked(action: VaultUiAction.EntryClicked) {
        val file = action.file
        val isUploading = file.uploadStatus is VaultUploadStatus.Preparing ||
            file.uploadStatus is VaultUploadStatus.Uploading
        if (file.isPending || isUploading) return

        _overlayState.update { VaultOverlay.Gallery(file) }
    }

    private fun handleShareFile(action: VaultUiAction.ShareFile) {
        val file = action.file
        if (file.isPending) return
        viewModelScope.launch {
            val firstKey = file.payloadDescriptors.firstOrNull()?.key ?: return@launch
            val tempPath = vaultUploaderService.downloadPayload(file, firstKey)
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
        val file = action.file
        vaultStream.updateOptimisticEntry(file.copy(fileName = action.newName))
        viewModelScope.launch {
            val success = vaultService.renameEntry(
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
        _overlayState.update { null }
        vaultStream.removeEntry(action.file.uniqueId)
        viewModelScope.launch {
            val success = vaultService.deleteEntry(action.file.uniqueId, action.file.fileId)
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.DeleteFileFailed(action.file.fileName)))
        }
    }

    private fun handleAppendPages(action: VaultUiAction.AppendPages) {
        val fileData = action.newFiles.map { file ->
            val ct = resolveContentType(file.name, file.mimeType()?.toString())
            file.path to ct
        }

        val existingMax = action.file.payloadDescriptors
            .mapNotNull { it.key.removePrefix("vlt_pg_").toIntOrNull() }
            .maxOrNull() ?: -1
        val placeholders = fileData.mapIndexed { i, (_, contentType) ->
            val key = "vlt_pg_${(existingMax + 1 + i).toString().padStart(2, '0')}"
            PayloadDescriptor(key = key, contentType = contentType)
        }

        fileData.forEachIndexed { i, (path, _) ->
            localAttachmentStore.put(
                action.file.uniqueId,
                placeholders[i].key,
                LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null),
            )
        }

        val optimisticEntry = action.file.copy(
            payloadDescriptors = action.file.payloadDescriptors + placeholders,
        )
        vaultStream.updateOptimisticEntry(optimisticEntry)

        viewModelScope.launch {
            val success = vaultUploaderService.appendPages(
                file = action.file,
                newFiles = fileData,
                scope = viewModelScope,
            )
            if (!success) {
                vaultStream.updateOptimisticEntry(action.file)
                _events.tryEmit(VaultUiEvent.Error(VaultError.AppendPagesFailed))
            }
        }
    }

    private fun handleDeletePage(action: VaultUiAction.DeletePage) {
        val isLastPage = action.file.payloadDescriptors.size <= 1
        if (isLastPage) {
            _overlayState.update { null }
            vaultStream.removeEntry(action.file.uniqueId)
        } else {
            vaultStream.markPayloadPendingDelete(action.file.uniqueId, action.payloadKey)
            val updated = action.file.copy(
                payloadDescriptors = action.file.payloadDescriptors.filter { it.key != action.payloadKey },
            )
            vaultStream.updateOptimisticEntry(updated)
        }
        viewModelScope.launch {
            val success = vaultUploaderService.deletePage(action.file, action.payloadKey)
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.DeletePageFailed))
        }
    }

    private fun handleUpdateNotes(action: VaultUiAction.UpdateNotes) {
        vaultStream.updateOptimisticEntry(action.file.copy(notes = action.notes))
        viewModelScope.launch {
            val success = vaultService.updateNotes(action.file, action.notes)
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.SaveNotesFailed))
        }
    }

    private fun handleUpdateLabel(action: VaultUiAction.UpdateLabel) {
        val normalizedLabel = action.label?.ifBlank { null }
        vaultStream.updateOptimisticEntry(action.file.copy(label = normalizedLabel))
        viewModelScope.launch {
            val success = vaultService.updateLabel(
                uniqueId = action.file.uniqueId,
                existingName = action.file.fileName,
                newLabel = normalizedLabel,
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
            val tempPath = vaultUploaderService.downloadPayload(action.file, action.payloadKey)
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

    private fun handleSavePage(action: VaultUiAction.SavePage) {
        viewModelScope.launch {
            val tempPath = vaultUploaderService.downloadPayload(action.file, action.payloadKey)
            if (tempPath != null) {
                _events.tryEmit(
                    VaultUiEvent.SaveFileReady(tempPath, action.file.fileName),
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
                .filter {
                    (it is BackendEvent.OutboxEvent.ItemFailed && it.driveId == vaultDriveId) ||
                        (it is BackendEvent.OutboxEvent.OutboxItemDropped && it.driveId == vaultDriveId)
                }
                .collect {
                    _events.tryEmit(VaultUiEvent.Error(VaultError.OutboxUploadFailed))
                }
        }
    }

    // endregion
}
