package id.homebase.core.ui.screens.openedfile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.HomebaseProtocol
import id.homebase.chat.widget.preview.FilePreviewKind
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.ui.screens.filesync.FilesyncCrumb
import id.homebase.core.ui.screens.filesync.FilesyncError
import id.homebase.core.ui.screens.filesync.FilesyncNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "OpenedFileViewModel"

interface OpenedFileGateway {
    val signedIn: StateFlow<Boolean>
    val errors: Flow<FilesyncError>
    suspend fun filesyncReady(): Boolean
    suspend fun rememberedFolder(): List<FilesyncCrumb>?
    suspend fun remember(path: List<FilesyncCrumb>)
    suspend fun listFolders(parent: Uuid): List<FilesyncNode>

    /** Queues the file; the returned job ends once the queue has its own copy. */
    fun enqueue(folderId: Uuid, path: String): Job
    fun discard(path: String)
}

@Immutable
data class SavePicker(
    val path: List<FilesyncCrumb>,
    val folders: List<FilesyncNode> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
)

@Immutable
data class OpenedFileUiState(
    val request: OpenedFileRequest,
    val kind: FilePreviewKind?,
    val tooLarge: Boolean,
    val signedIn: Boolean = true,
    val picker: SavePicker? = null,
    val saving: Boolean = false,
)

sealed interface OpenedFileAction {
    data object SaveClicked : OpenedFileAction
    data class PickerOpen(val folder: FilesyncNode) : OpenedFileAction
    data class PickerToCrumb(val index: Int) : OpenedFileAction
    data object PickerDismiss : OpenedFileAction
    data object PickerConfirm : OpenedFileAction
}

sealed interface OpenedFileEvent {
    data object OpenSignIn : OpenedFileEvent
    data object OpenFilesyncSetup : OpenedFileEvent

    /** [folderName] is null for the top of the drive. */
    data class Saved(val folderName: String?, val path: List<FilesyncCrumb>) : OpenedFileEvent
    data class Error(val error: FilesyncError) : OpenedFileEvent
}

class OpenedFileViewModel(
    val request: OpenedFileRequest,
    private val gateway: OpenedFileGateway,
) : ViewModel() {

    private val root = FilesyncCrumb(FilesyncProtocol.RootNodeId, null)

    private val _uiState = MutableStateFlow(
        OpenedFileUiState(
            request = request,
            kind = openedFilePreviewKind(request.mimeType, request.name, request.sizeBytes),
            tooLarge = request.sizeBytes > HomebaseProtocol.MaxPlaintextPayloadBytes,
            signedIn = gateway.signedIn.value,
        )
    )
    val uiState: StateFlow<OpenedFileUiState> = _uiState.asStateFlow()

    private val _events = Channel<OpenedFileEvent>(Channel.BUFFERED)
    val events: Flow<OpenedFileEvent> = _events.receiveAsFlow()

    private var keepStaged = false
    private var listJob: Job? = null

    init {
        viewModelScope.launch { gateway.signedIn.collect { signedIn -> _uiState.update { it.copy(signedIn = signedIn) } } }
        viewModelScope.launch { gateway.errors.collect { _events.send(OpenedFileEvent.Error(it)) } }
    }

    fun onAction(action: OpenedFileAction) {
        when (action) {
            OpenedFileAction.SaveClicked -> save()
            is OpenedFileAction.PickerOpen -> _uiState.value.picker?.let { showFolder(it.path + FilesyncCrumb(action.folder.nodeId, action.folder.name)) }
            is OpenedFileAction.PickerToCrumb -> _uiState.value.picker?.let { showFolder(it.path.take(action.index + 1)) }
            OpenedFileAction.PickerDismiss -> {
                listJob?.cancel()
                _uiState.update { it.copy(picker = null) }
            }
            OpenedFileAction.PickerConfirm -> confirm()
        }
    }

    private fun save() {
        val state = _uiState.value
        if (state.tooLarge || state.saving) return
        if (!state.signedIn) {
            // Sign-in replaces the whole back stack; the inbox brings this screen back, so the copy has to outlive it.
            keepStaged = true
            _events.trySend(OpenedFileEvent.OpenSignIn)
            return
        }
        viewModelScope.launch {
            if (!gateway.filesyncReady()) {
                _events.send(OpenedFileEvent.OpenFilesyncSetup)
            } else {
                showFolder(gateway.rememberedFolder()?.takeIf { it.isNotEmpty() } ?: listOf(root), fallbackToRoot = true)
            }
        }
    }

    private fun showFolder(path: List<FilesyncCrumb>, fallbackToRoot: Boolean = false) {
        _uiState.update { it.copy(picker = SavePicker(path)) }
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val shown = try {
                SavePicker(path, gateway.listFolders(path.last().nodeId).sortedBy { it.name.lowercase() }, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e, TAG) { "listing a folder to save into failed" }
                if (fallbackToRoot && path.size > 1) return@launch showFolder(listOf(root))
                SavePicker(path, loading = false, loadFailed = true)
            }
            _uiState.update { if (it.picker?.path == path) it.copy(picker = shown) else it }
        }
    }

    private fun confirm() {
        val state = _uiState.value
        val picker = state.picker?.takeIf { !it.loading && !it.loadFailed } ?: return
        if (state.saving) return
        listJob?.cancel()
        _uiState.update { it.copy(picker = null, saving = true) }
        val target = picker.path.last()
        viewModelScope.launch {
            gateway.remember(picker.path)
            gateway.enqueue(target.nodeId, request.path).join()
            _uiState.update { it.copy(saving = false) }
            _events.send(OpenedFileEvent.Saved(target.name, picker.path))
        }
    }

    /** The screen has left the back stack: the staged copy goes, unless the queue is still copying it or sign-in will bring the screen back. */
    internal fun release() {
        if (keepStaged || _uiState.value.saving) return
        gateway.discard(request.path)
    }

    override fun onCleared() = release()
}
