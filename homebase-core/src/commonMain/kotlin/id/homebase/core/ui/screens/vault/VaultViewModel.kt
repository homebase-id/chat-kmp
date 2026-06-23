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
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import id.homebase.core.config.vaultDefaultSections
import id.homebase.core.config.vaultLabeledDrive
import id.homebase.core.sync.DriveRegistry
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.ui.screens.vault.model.VaultSection
import id.homebase.core.ui.screens.vault.model.VaultSectionContent
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.util.resolveContentType
import id.homebase.core.vault.VaultPreferences
import id.homebase.imageeditor.ui.CropResultBus
import id.homebase.imageeditor.ui.DrawResultBus
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.DriveState
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.PlatformFile
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
    private val optionalDriveActivation: OptionalDriveActivation,
    private val driveRegistry: DriveRegistry,
    private val localAttachmentStore: LocalAttachmentContextStore,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveSyncManager: DriveSyncManager,
    private val cropResultBus: CropResultBus,
    private val drawResultBus: DrawResultBus,
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
        buildUiState(sections, entriesBySection, isLoaded, overlay, syncing, checkingPermissions)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Seed the initial value from VaultStream's CURRENT cached snapshot. VaultStream is a
        // singleton that keeps its loaded sections across screen re-entry, but a freshly created
        // VaultViewModel's stateIn would otherwise emit the empty + loading default VaultUiState()
        // (isLoading = true, no sections) for the frame before the combine's first emission —
        // flashing the full-screen spinner (VaultContent shows it on
        // `(isLoading || isSyncing) && sections.isEmpty()`) on every Vault entry even though the
        // data is already cached. Seeding from cache renders the cached sections immediately and
        // spins only on a genuine cold load (stream not loaded yet → isLoaded = false, empty).
        buildUiState(
            sections = vaultStream.sections.value,
            entriesBySection = vaultStream.entriesBySection.value,
            isLoaded = vaultStream.isLoaded.value,
            overlay = _overlayState.value,
            syncing = _syncingState.value,
            checkingPermissions = _checkingPermissions.value,
        ),
    )

    /**
     * Builds the [VaultUiState] from the stream + overlay inputs. Shared by the [uiState] combine
     * and the cache-seeded `stateIn` initial value above so the two can never drift.
     */
    private fun buildUiState(
        sections: List<VaultSection>,
        entriesBySection: Map<Uuid, List<VaultEntry>>,
        isLoaded: Boolean,
        overlay: VaultOverlay?,
        syncing: Boolean,
        checkingPermissions: Boolean,
    ): VaultUiState {
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
        return VaultUiState(
            sections = sectionModels,
            isLoading = !isLoaded,
            isSyncing = syncing,
            isCheckingPermissions = checkingPermissions,
            fullScreenOverlay = refreshedOverlay ?: overlay,
        )
    }

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
                    // Intentionally NO eager mount here. A registered vault is mounted by
                    // AuthConnectionCoordinator's login-time pre-mount loop (it's in the
                    // DriveRegistry) before the WebSocket connects, and observeDriveSyncStatus()
                    // confirms activation once the drive appears in driveStatuses. Mounting it
                    // again from here raced that pre-mount and triggered a redundant WS reconnect
                    // → second syncAll → the post-login "0 records" sync-screen regression.
                    // First-time activation still mounts via handleActivation().
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
            optionalDriveActivation.activate(vaultLabeledDrive)
            createDefaultSections()
        } else {
            Logger.i(tag = TAG) { "activation: vault already registered — mounting drive" }
            optionalDriveActivation.activate(vaultLabeledDrive)
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
            is VaultUiAction.EditPickedImageThenAdd,
            is VaultUiAction.EditExistingPage,
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
            is VaultUiAction.EditPickedImageThenAdd -> handleEditPickedImageThenAdd(action)
            is VaultUiAction.EditExistingPage -> handleEditExistingPage(action)
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
                    LocalAttachmentContext.Image(localFilePath = file.pathCompat, aspectRatio = null),
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
            pendingFileUri = files.first().pathCompat,
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
                    file.copyToPath(tempPath)
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

        if (file.isNote) {
            val sectionId = file.groupId ?: return
            _events.tryEmit(VaultUiEvent.OpenNoteEditor(sectionId, file.uniqueId))
        } else {
            _overlayState.update { VaultOverlay.Gallery(file) }
        }
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
            file.pathCompat to ct
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
            // Copy each picked file into the sandbox NOW for the upload read. By upload
            // time the iOS document picker's security scope is gone, so reading the raw
            // "File Provider Storage" path there throws ("Unable to read file"). The raw
            // paths above are fine for the instant optimistic preview because the picker
            // scope is still live at pick time. Mirrors handleAddFiles (and chat's
            // materializeForUpload fix).
            val uploadData = action.newFiles.mapIndexed { index, file ->
                val ext = file.name.substringAfterLast('.', "tmp")
                val tempPath = "${fileOperationsProvider.getCacheDirectory()}/vault_upload_${Uuid.random()}.$ext"
                file.copyToPath(tempPath)
                tempPath to fileData[index].second
            }
            val success = vaultUploaderService.appendPages(
                file = action.file,
                newFiles = uploadData,
                scope = viewModelScope,
            )
            if (!success) {
                uploadData.forEach { (path, _) ->
                    try { fileOperationsProvider.deleteTempFile(path) } catch (_: Exception) { }
                }
                vaultStream.updateOptimisticEntry(action.file)
                _events.tryEmit(VaultUiEvent.Error(VaultError.AppendPagesFailed))
            }
        }
    }

    // region Image editor (crop/draw) — reuses image-editor-ui's generic seam

    /**
     * Routes a freshly-picked single image through the crop/draw editor, then
     * feeds the edited JPEG back into the EXISTING add or append path (so
     * HEIC→JPEG, thumbnail gen, encryption + outbox are all preserved). The
     * editor itself is optional — VaultScreen only dispatches this when the
     * user explicitly chose Crop/Draw; "Add as-is" goes straight to
     * AddEntryToSection/AppendPages.
     */
    private fun handleEditPickedImageThenAdd(action: VaultUiAction.EditPickedImageThenAdd) {
        launchEditor(
            tool = action.tool,
            readSource = { fileOperationsProvider.readFileBytes(action.file.pathCompat) },
        ) { editedBytes ->
            val tempPath = fileOperationsProvider.writeBytesToTempFile(editedBytes, "vault_edit_", ".jpg")
            val edited = platformFileFromPath(tempPath)
            val appendTo = action.appendTo
            if (appendTo != null) {
                onAction(VaultUiAction.AppendPages(appendTo, listOf(edited)))
            } else if (action.sectionId != null) {
                onAction(VaultUiAction.AddEntryToSection(action.sectionId, listOf(edited)))
            }
        }
    }

    /**
     * Re-edits an already-stored image page. Downloads the page payload,
     * routes it through the editor, then replaces that payload IN PLACE
     * (same payload key) rather than appending a new page — so the page count
     * and order stay stable and the thumbnail simply refreshes.
     */
    private fun handleEditExistingPage(action: VaultUiAction.EditExistingPage) {
        launchEditor(
            tool = action.tool,
            readSource = {
                val path = vaultUploaderService.downloadPayload(action.file, action.payloadKey)
                    ?: throw IllegalStateException("Failed to download page payload")
                fileOperationsProvider.readFileBytes(path)
            },
        ) { editedBytes ->
            val tempPath = fileOperationsProvider.writeBytesToTempFile(editedBytes, "vault_edit_", ".jpg")
            // Refresh the gallery thumbnail immediately from the edited local file
            // while the outbox replace propagates.
            localAttachmentStore.put(
                action.file.uniqueId,
                action.payloadKey,
                LocalAttachmentContext.Image(localFilePath = tempPath, aspectRatio = null),
            )
            val success = vaultUploaderService.replacePagePayload(
                file = action.file,
                payloadKey = action.payloadKey,
                newFilePath = tempPath,
                contentType = "image/jpeg",
                scope = viewModelScope,
            )
            if (!success) _events.tryEmit(VaultUiEvent.Error(VaultError.EditPageFailed))
        }
    }

    private fun launchEditor(
        tool: VaultEditorTool,
        readSource: suspend () -> ByteArray,
        onResult: suspend (ByteArray) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val bytes = readSource()
                val requestId = Uuid.random()
                when (tool) {
                    VaultEditorTool.Crop -> {
                        cropResultBus.postSource(requestId, bytes)
                        viewModelScope.launch {
                            cropResultBus.resultsFor(requestId).collect { onResult(it.bytes) }
                        }
                        _events.tryEmit(VaultUiEvent.NavigateToCropper(requestId))
                    }
                    VaultEditorTool.Draw -> {
                        drawResultBus.postSource(requestId, bytes)
                        viewModelScope.launch {
                            drawResultBus.resultsFor(requestId).collect { onResult(it.bytes) }
                        }
                        _events.tryEmit(VaultUiEvent.NavigateToDrawer(requestId))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to open vault image editor: ${e.message}" }
                _events.tryEmit(VaultUiEvent.Error(VaultError.OpenEditorFailed))
            }
        }
    }

    // endregion

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
