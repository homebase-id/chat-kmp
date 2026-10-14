package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.Uuid

/** One step of the breadcrumb trail; [name] is null for the top level, [removed] when it's in Recently deleted. */
@Immutable
data class FilesyncCrumb(val nodeId: Uuid, val name: String?, val removed: Boolean = false)

@Immutable
data class FilesyncTransfer(val nodeId: Uuid, val progress: Float = 0f)

internal fun FilesyncTransfer?.progressOf(nodeId: Uuid): Float? = this?.takeIf { it.nodeId == nodeId }?.progress

/** [ownedCopy]: [path] is the app's own copy, deleted once the upload is done with it. */
@Immutable
data class FilesyncPickedFile(val path: String, val name: String, val modifiedMs: Long?, val ownedCopy: Boolean)

/** [id] is also the node's uniqueId and [name] the free name chosen on the first attempt; both are kept for retries. [progress] is null until bytes move. */
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
    val showDeleted: Boolean = false,
    val sort: FilesyncSort = FilesyncSort(),
    val query: String = "",
    val tagFilter: String? = null,
    val selectedId: Uuid? = null,
    // Derived by withNodes() and arrange(); kept here so a recomposition or a keystroke doesn't redo them.
    val folderTags: List<FilesyncTag> = emptyList(),
    val visible: List<FilesyncNode> = emptyList(),
    val totals: FilesyncTotals = FilesyncTotals(),
) {
    val selected: FilesyncNode? by lazy(LazyThreadSafetyMode.NONE) { selectedId?.let { id -> visible.firstOrNull { it.nodeId == id } } }

    fun withNodes(nodes: List<FilesyncNode>): FilesyncUiState = copy(nodes = nodes, folderTags = nodes.distinctTags())

    fun arrange(): FilesyncUiState {
        // A tag no longer in the folder can't be clicked off in the sidebar.
        val tagFilter = tagFilter?.takeIf { name -> folderTags.any { it.name == name } }
        val visible = nodes.arranged(sort, query, tagFilter)
        return copy(
            visible = visible,
            totals = visible.totals(),
            tagFilter = tagFilter,
            selectedId = selectedId?.takeIf { id -> visible.any { it.nodeId == id } },
        )
    }
}

sealed interface FilesyncUiAction {
    data object SetupClicked : FilesyncUiAction
    data object RetryCheck : FilesyncUiAction
    data object Refresh : FilesyncUiAction
    data object NavigateUp : FilesyncUiAction
    data class NavigateToCrumb(val index: Int) : FilesyncUiAction
    data class NodeClicked(val node: FilesyncNode) : FilesyncUiAction
    data class SaveClicked(val node: FilesyncNode) : FilesyncUiAction
    data class ShareClicked(val node: FilesyncNode) : FilesyncUiAction
    data class WebDropClicked(val node: FilesyncNode) : FilesyncUiAction
    data object CancelTransfer : FilesyncUiAction
    data class SaveHandedOff(val path: String) : FilesyncUiAction
    data class FilesPicked(val files: List<PlatformFile>) : FilesyncUiAction
    data class CreateFolder(val name: String) : FilesyncUiAction
    data class RetryUpload(val id: Uuid) : FilesyncUiAction
    data class DismissUpload(val id: Uuid) : FilesyncUiAction
    data object ToggleShowDeleted : FilesyncUiAction
    data class Restore(val nodeId: Uuid) : FilesyncUiAction
    data class SortBy(val key: FilesyncSortKey) : FilesyncUiAction
    /** The tag already filtered on clears the filter. */
    data class FilterTag(val name: String) : FilesyncUiAction
    data class Select(val nodeId: Uuid?) : FilesyncUiAction
    data class MoveSelection(val by: Int) : FilesyncUiAction
}

enum class FilesyncError { DownloadFailed, Damaged, UploadFailed, TooLarge, FolderFailed, RestoreFailed, DeleteFailed }

/** What a finished restore or delete tells the user; [ChangedElsewhere] when some items were skipped. */
enum class FilesyncNotice { Restored, DeletedForever, ChangedElsewhere }

sealed interface FilesyncUiEvent {
    data class OpenFile(val path: String) : FilesyncUiEvent
    data class SaveFile(val path: String, val name: String) : FilesyncUiEvent
    data class ShareFile(val path: String) : FilesyncUiEvent
    data object OpenWebDrop : FilesyncUiEvent
    data class Error(val error: FilesyncError) : FilesyncUiEvent
    data class Notice(val notice: FilesyncNotice) : FilesyncUiEvent
}
