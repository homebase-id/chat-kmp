@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.vault.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.stripMarkdownForPreview
import id.homebase.core.ui.screens.vault.VaultService
import id.homebase.core.ui.screens.vault.VaultStream
import id.homebase.core.ui.screens.vault.VaultUploaderService
import id.homebase.core.ui.screens.vault.model.VaultEntry
import id.homebase.core.util.CONTENT_TYPE_MARKDOWN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VaultNoteEditorVM"

data class VaultNoteEditorUiState(
    val title: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val isEditing: Boolean = false,
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
    data object DeleteSuccess : VaultNoteEditorEvent
    data object DeleteFailed : VaultNoteEditorEvent
    data class ShareReady(
        val filePath: String,
        val fileName: String,
        val contentType: String,
    ) : VaultNoteEditorEvent
}

class VaultNoteEditorViewModel(
    private val sectionId: Uuid,
    private val editEntryId: Uuid?,
    private val vaultStream: VaultStream,
    private val vaultService: VaultService,
    private val vaultUploaderService: VaultUploaderService,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VaultNoteEditorUiState(
            entryId = editEntryId,
            isEditing = editEntryId == null,
        )
    )
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
                val tempPath = withContext(Dispatchers.Default) {
                    vaultUploaderService.downloadPayload(entry, payloadKey)
                }
                if (tempPath == null) {
                    _events.tryEmit(VaultNoteEditorEvent.LoadFailed)
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                val markdown: String
                try {
                    val bytes = withContext(Dispatchers.Default) {
                        fileOperationsProvider.readFileBytes(tempPath)
                    }
                    markdown = bytes.decodeToString()
                } finally {
                    try { fileOperationsProvider.deleteTempFile(tempPath) } catch (_: Exception) {}
                }
                val displayTitle = entry.noteDisplayTitle ?: entry.fileName
                _uiState.update {
                    it.copy(title = displayTitle, isLoading = false, loadedMarkdown = markdown)
                }
            } catch (e: CancellationException) {
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

    fun onToggleEdit() {
        _uiState.update { it.copy(isEditing = !it.isEditing) }
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
            val preview = markdown.stripMarkdownForPreview()

            val success = if (currentState.isCreateMode) {
                createNote(fileName, markdown, preview)
            } else {
                updateNote(fileName, markdown, preview)
            }

            _uiState.update {
                it.copy(
                    isSaving = false,
                    isEditing = false,
                    loadedMarkdown = if (success) markdown else it.loadedMarkdown,
                )
            }
            _events.tryEmit(
                if (success) VaultNoteEditorEvent.SaveSuccess
                else VaultNoteEditorEvent.SaveFailed,
            )
        }
    }

    private suspend fun createNote(fileName: String, markdown: String, preview: String?): Boolean {
        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            markdown.encodeToByteArray(), "vault_note_", ".md"
        )
        return try {
            vaultUploaderService.uploadFile(
                entryName = fileName,
                files = listOf(tempPath to CONTENT_TYPE_MARKDOWN),
                scope = viewModelScope,
                groupId = sectionId,
                notePreview = preview,
            ) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "Failed to create note" }
            false
        } finally {
            try { fileOperationsProvider.deleteTempFile(tempPath) } catch (_: Exception) {}
        }
    }

    private suspend fun updateNote(fileName: String, markdown: String, preview: String?): Boolean {
        val entry = editEntry ?: return false
        return try {
            vaultUploaderService.updateNotePayload(
                file = entry,
                newName = fileName,
                markdown = markdown,
                notePreview = preview,
                scope = viewModelScope,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "Failed to update note" }
            false
        }
    }

    fun onDelete() {
        val current = editEntry ?: return
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            try {
                vaultStream.removeEntry(current.uniqueId)
                val success = vaultService.deleteEntry(current.uniqueId, current.fileId)
                if (success) {
                    _events.tryEmit(VaultNoteEditorEvent.DeleteSuccess)
                } else {
                    _uiState.update { it.copy(isDeleting = false) }
                    _events.tryEmit(VaultNoteEditorEvent.DeleteFailed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Failed to delete note" }
                _uiState.update { it.copy(isDeleting = false) }
                _events.tryEmit(VaultNoteEditorEvent.DeleteFailed)
            }
        }
    }

    fun onShare() {
        val markdown = _uiState.value.loadedMarkdown ?: return
        val current = editEntry ?: return
        viewModelScope.launch {
            try {
                val tempPath = fileOperationsProvider.writeBytesToTempFile(
                    markdown.encodeToByteArray(), "share_", ".md"
                )
                _events.tryEmit(
                    VaultNoteEditorEvent.ShareReady(tempPath, current.fileName, CONTENT_TYPE_MARKDOWN)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Failed to prepare note for sharing" }
            }
        }
    }

    private fun findEntry(entryId: Uuid): VaultEntry? {
        return vaultStream.entriesBySection.value[sectionId]
            ?.find { it.uniqueId == entryId }
    }
}
