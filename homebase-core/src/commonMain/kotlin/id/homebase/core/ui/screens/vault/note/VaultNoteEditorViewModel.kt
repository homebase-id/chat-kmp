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
import id.homebase.api.util.stripMarkdownForPreview
import id.homebase.core.util.CONTENT_TYPE_MARKDOWN
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
    val loadedMarkdown: String? = null,
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

    private var editEntry: VaultEntry? = null

    init {
        if (editEntryId != null) loadExistingNote(editEntryId)
    }

    private fun loadExistingNote(entryId: Uuid) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val entry = findEntry(entryId) ?: run {
                    _events.tryEmit(VaultNoteEditorEvent.LoadFailed)
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                editEntry = entry
                val payloadKey = entry.payloadDescriptors.firstOrNull()?.key
                    ?: VaultEntry.DEFAULT_PAYLOAD_KEY
                val tempPath = vaultUploaderService.downloadPayload(entry, payloadKey)
                if (tempPath == null) {
                    _events.tryEmit(VaultNoteEditorEvent.LoadFailed)
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val markdown: String
                try {
                    val bytes = fileOperationsProvider.readFileBytes(tempPath)
                    markdown = bytes.decodeToString()
                } finally {
                    try { fileOperationsProvider.deleteTempFile(tempPath) } catch (_: Exception) {}
                }
                val displayTitle = entry.noteDisplayTitle ?: entry.fileName
                _uiState.update {
                    it.copy(title = displayTitle, isLoading = false, loadedMarkdown = markdown)
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
                val newId = uploadNote(fileName, tempPath, markdown)
                if (newId != null && !currentState.isCreateMode) {
                    val entry = editEntry
                    if (entry != null) {
                        try {
                            vaultService.deleteEntry(entry.uniqueId, entry.fileId)
                        } catch (e: Exception) {
                            Logger.w(e, TAG) { "Old note entry cleanup failed" }
                        }
                    }
                }
                _uiState.update { it.copy(isSaving = false) }
                _events.tryEmit(
                    if (newId != null) VaultNoteEditorEvent.SaveSuccess
                    else VaultNoteEditorEvent.SaveFailed,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Failed to save note" }
                _uiState.update { it.copy(isSaving = false) }
                _events.tryEmit(VaultNoteEditorEvent.SaveFailed)
            } finally {
                try { fileOperationsProvider.deleteTempFile(tempPath) } catch (_: Exception) {}
            }
        }
    }

    private suspend fun uploadNote(fileName: String, tempPath: String, markdown: String): Uuid? {
        val preview = markdown.stripMarkdownForPreview()
        return vaultUploaderService.uploadFile(
            entryName = fileName,
            files = listOf(tempPath to CONTENT_TYPE_MARKDOWN),
            scope = viewModelScope,
            groupId = sectionId,
            notePreview = preview,
        )
    }

    private fun findEntry(entryId: Uuid): VaultEntry? {
        return vaultStream.entriesBySection.value[sectionId]
            ?.find { it.uniqueId == entryId }
    }
}
