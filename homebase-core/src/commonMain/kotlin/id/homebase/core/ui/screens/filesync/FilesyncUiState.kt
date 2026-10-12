package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.Uuid

/** One step of the breadcrumb trail; [name] is null for the top level. */
@Immutable
data class FilesyncCrumb(val nodeId: Uuid, val name: String?)

@Immutable
data class FilesyncTransfer(val nodeId: Uuid, val progress: Float = 0f)

/** [ownedCopy]: [path] is the app's own copy, deleted once the upload is done with it. */
@Immutable
data class FilesyncPickedFile(val path: String, val name: String, val modifiedMs: Long?, val ownedCopy: Boolean)

/** [name] is the free name chosen on the first attempt and kept for retries; [progress] is null until bytes move. */
@Immutable
data class FilesyncUpload(
    val id: Uuid,
    val parentId: Uuid,
    val file: FilesyncPickedFile,
    val name: String? = null,
    val progress: Float? = null,
    val failed: Boolean = false,
)

@Immutable
data class FilesyncUiState(
    val installed: Boolean? = null,
    val checkFailed: Boolean = false,
    val setupInitiated: Boolean = false,
    val path: List<FilesyncCrumb> = emptyList(),
    val nodes: List<FilesyncNode> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val transfer: FilesyncTransfer? = null,
)

sealed interface FilesyncUiAction {
    data object SetupClicked : FilesyncUiAction
    data object DismissOnboardingClicked : FilesyncUiAction
    data object RetryCheck : FilesyncUiAction
    data object Refresh : FilesyncUiAction
    data object NavigateUp : FilesyncUiAction
    data class NavigateToCrumb(val index: Int) : FilesyncUiAction
    data class NodeClicked(val node: FilesyncNode) : FilesyncUiAction
    data class SaveClicked(val node: FilesyncNode) : FilesyncUiAction
    data object CancelTransfer : FilesyncUiAction
    data class SaveHandedOff(val path: String) : FilesyncUiAction
    data class FilesPicked(val files: List<PlatformFile>) : FilesyncUiAction
    data class CreateFolder(val name: String) : FilesyncUiAction
    data class RetryUpload(val id: Uuid) : FilesyncUiAction
    data class DismissUpload(val id: Uuid) : FilesyncUiAction
}

enum class FilesyncError { DownloadFailed, Damaged, UploadFailed, TooLarge, FolderFailed }

sealed interface FilesyncUiEvent {
    data class OpenFile(val path: String) : FilesyncUiEvent
    data class SaveFile(val path: String, val name: String) : FilesyncUiEvent
    data class Error(val error: FilesyncError) : FilesyncUiEvent
}
