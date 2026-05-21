@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.ui.screens.vault.VaultService
import id.homebase.core.ui.screens.vault.VaultStream
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.model.VaultEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultNoteEditorVM"

data class VaultNoteEditorUiState(
    val title: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val entryId: Uuid? = null,
    val titleError: Boolean = false,
) {
    val isCreateMode: Boolean get() = entryId == null
    val canSave: Boolean get() = title.isNotBlank() && !isSaving
}

sealed interface VaultNoteEditorEvent {
    data object SaveSuccess : VaultNoteEditorEvent
    data object SaveFailed : VaultNoteEditorEvent
    data object LoadFailed : VaultNoteEditorEvent
}

class VaultNoteEditorViewModel(
    private val sectionId: Uuid,
    private val editEntryId: Uuid?,
    private val vaultStream: VaultStream,
    private val vaultService: VaultService,
    private val vaultUploaderService: VaultUploaderService,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultNoteEditorUiState(entryId = editEntryId))
    val uiState: StateFlow<VaultNoteEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VaultNoteEditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VaultNoteEditorEvent> = _events.asSharedFlow()

    private var loadedMarkdown: String = ""
    private var editEntry: VaultEntry? = null

    init {
        if (editEntryId != null) loadExistingNote()
    }

    private fun loadExistingNote() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val entry = findEntry(editEntryId!!) ?: run {
                    _events.tryEmit(VaultNoteEditorEvent.LoadFailed)
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                editEntry = entry
                val payloadKey = entry.payloadDescriptors.firstOrNull()?.key ?: "vlt_pg_00"
                val tempPath = vaultUploaderService.downloadPayload(entry, payloadKey)
                if (tempPath == null) {
                    _events.tryEmit(VaultNoteEditorEvent.LoadFailed)
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val bytes = fileOperationsProvider.readFileBytes(tempPath)
                fileOperationsProvider.deleteTempFile(tempPath)
                loadedMarkdown = bytes.decodeToString()
                val displayTitle = entry.fileName.removeSuffix(".md")
                _uiState.update {
                    it.copy(title = displayTitle, isLoading = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Failed to load note" }
                _events.tryEmit(VaultNoteEditorEvent.LoadFailed)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun getLoadedMarkdown(): String = loadedMarkdown

    fun onTitleChanged(title: String) {
        _uiState.update { it.copy(title = title, titleError = false) }
    }

    fun onSave(markdown: String) {
        val currentState = _uiState.value
        if (currentState.title.isBlank()) {
            _uiState.update { it.copy(titleError = true) }
            return
        }
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val fileName = "${currentState.title.trim()}.md"
            val tempPath = fileOperationsProvider.writeBytesToTempFile(
                markdown.encodeToByteArray(), "vault_note_", ".md"
            )
            try {
                if (currentState.isCreateMode) {
                    createNote(fileName, tempPath)
                } else {
                    updateNote(fileName, tempPath)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                fileOperationsProvider.deleteTempFile(tempPath)
                throw e
            } catch (e: Exception) {
                fileOperationsProvider.deleteTempFile(tempPath)
                Logger.w(e, TAG) { "Failed to save note" }
                _uiState.update { it.copy(isSaving = false) }
                _events.tryEmit(VaultNoteEditorEvent.SaveFailed)
            }
        }
    }

    private suspend fun createNote(fileName: String, tempPath: String) {
        val uniqueId = vaultUploaderService.uploadFile(
            entryName = fileName,
            files = listOf(tempPath to "text/markdown"),
            scope = viewModelScope,
            groupId = sectionId,
        )
        _uiState.update { it.copy(isSaving = false) }
        if (uniqueId != null) {
            _events.tryEmit(VaultNoteEditorEvent.SaveSuccess)
        } else {
            _events.tryEmit(VaultNoteEditorEvent.SaveFailed)
        }
    }

    private suspend fun updateNote(fileName: String, tempPath: String) {
        val entry = editEntry ?: return
        // Delete old entry, then re-upload with new content under the same section (groupId).
        // There is no updatePayload API, so edit = delete + re-create with a new uniqueId.
        vaultService.deleteEntry(entry.uniqueId, entry.fileId)
        val uniqueId = vaultUploaderService.uploadFile(
            entryName = fileName,
            files = listOf(tempPath to "text/markdown"),
            scope = viewModelScope,
            groupId = sectionId,
        )
        _uiState.update { it.copy(isSaving = false) }
        if (uniqueId != null) {
            _events.tryEmit(VaultNoteEditorEvent.SaveSuccess)
        } else {
            _events.tryEmit(VaultNoteEditorEvent.SaveFailed)
        }
    }

    private fun findEntry(entryId: Uuid): VaultEntry? {
        return vaultStream.entriesBySection.value.values
            .flatten()
            .find { it.uniqueId == entryId }
    }
}
