package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import id.homebase.core.privatedrive.PrivateDriveState
import id.homebase.core.util.isMobile
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.Uuid

/** One step of the breadcrumb trail; [name] is null for the top level, [removedMs] set when it's in Recently deleted. */
@Immutable
data class FilesyncCrumb(val nodeId: Uuid, val name: String?, val removedMs: Long? = null)

/** A download under way: of the node [nodeId], or of a ZIP when null, whose [totalBytes] are known once it's listed. */
@Immutable
data class FilesyncTransfer(val nodeId: Uuid?, val progress: Float = 0f, val totalBytes: Long? = null) {
    val isZip: Boolean get() = nodeId == null
}

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
data class FilesyncMovePicker(
    val nodes: List<FilesyncNode>,
    val path: List<FilesyncCrumb>,
    val folders: List<FilesyncNode> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
) {
    private val movingIds = nodes.mapTo(HashSet()) { it.nodeId }

    /** A folder being moved can't take itself: it isn't opened, and nothing moves below it. */
    fun isMoving(folderId: Uuid): Boolean = folderId in movingIds

    val canMoveHere: Boolean
        get() = !loading && !loadFailed && path.none { isMoving(it.nodeId) } && nodes.any { it.parentId != path.last().nodeId }
}

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
    val selection: FilesyncSelection = FilesyncSelection(),
    // Derived by withNodes() and arrange(); kept here so a recomposition or a keystroke doesn't redo them.
    val folderTags: List<FilesyncTag> = emptyList(),
    val visible: List<FilesyncNode> = emptyList(),
    val totals: FilesyncTotals = FilesyncTotals(),
    val privateDrive: PrivateDriveState = PrivateDriveState(),
    /** Asked about before they go to Recently deleted. */
    val confirmDelete: List<FilesyncNode>? = null,
    val movePicker: FilesyncMovePicker? = null,
    /** What Share asks a target for. */
    val shareChooser: List<FilesyncNode>? = null,
) {
    val selecting: Boolean get() = selection.ids.isNotEmpty()

    /** The selected nodes in the order shown. */
    val selected: List<FilesyncNode> by lazy(LazyThreadSafetyMode.NONE) {
        if (selecting) visible.filter { it.nodeId in selection.ids } else emptyList()
    }

    val selectedTotals: FilesyncTotals by lazy(LazyThreadSafetyMode.NONE) { selected.totals() }

    /** The deleted nodes among [selected], which Restore takes. */
    val selectedRemovedIds: Set<Uuid> by lazy(LazyThreadSafetyMode.NONE) { selected.removedIds() }

    /** The selection's nodes that aren't in Recently deleted. */
    val liveSelected: List<FilesyncNode> by lazy(LazyThreadSafetyMode.NONE) { selected.filter { it.removedMs == null } }

    /** What the selection offers, in the order shown: a ZIP of its live nodes, and Restore for its deleted ones. */
    val bulkActions: List<FilesyncBulkAction> by lazy(LazyThreadSafetyMode.NONE) {
        val live = liveSelected.isNotEmpty()
        listOfNotNull(
            FilesyncBulkAction.Download.takeIf { live },
            FilesyncBulkAction.Share.takeIf { live },
            FilesyncBulkAction.Move.takeIf { liveSelected.any { it.movable } },
            FilesyncBulkAction.Delete.takeIf { live && !inDeletedFolder },
            FilesyncBulkAction.Restore.takeIf { selectedRemovedIds.isNotEmpty() },
        )
    }

    val inDeletedFolder: Boolean get() = path.lastOrNull()?.removedMs != null

    /** One download at a time; a move, delete or restore can go alongside. */
    fun isEnabled(bulk: FilesyncBulkAction): Boolean =
        transfer == null || bulk in WRITES

    /** The selected node when it's the only one. */
    val selectedOne: FilesyncNode? get() = selected.singleOrNull()

    val visibleIds: List<Uuid> by lazy(LazyThreadSafetyMode.NONE) { visible.map { it.nodeId } }

    fun withNodes(nodes: List<FilesyncNode>): FilesyncUiState = copy(nodes = nodes, folderTags = nodes.distinctTags())

    fun arrange(): FilesyncUiState {
        // A tag no longer in the folder can't be clicked off in the sidebar.
        val tagFilter = tagFilter?.takeIf { name -> folderTags.any { it.name == name } }
        val visible = nodes.arranged(sort, query, tagFilter)
        return copy(
            visible = visible,
            totals = visible.totals(),
            tagFilter = tagFilter,
            selection = selection.keptIn(visible.map { it.nodeId }),
        )
    }
}

enum class FilesyncBulkAction { Download, Share, Move, Delete, Restore }

/** Where Share sends: one file as itself, anything more as one ZIP. Another app only on phones, where there are any. */
enum class FilesyncShareTarget { Download, Chat, WebDrop, OtherApp;

    companion object {
        val offered: List<FilesyncShareTarget> get() = if (isMobile()) entries else entries - OtherApp
    }
}

private val WRITES = setOf(FilesyncBulkAction.Move, FilesyncBulkAction.Delete, FilesyncBulkAction.Restore)

sealed interface FilesyncUiAction {
    data object SetupClicked : FilesyncUiAction
    data object Paused : FilesyncUiAction
    data object Resumed : FilesyncUiAction
    data object SetupLeft : FilesyncUiAction
    data object RetryCheck : FilesyncUiAction
    data object Refresh : FilesyncUiAction
    data object NavigateUp : FilesyncUiAction
    data class NavigateToCrumb(val index: Int) : FilesyncUiAction
    data class NodeClicked(val node: FilesyncNode) : FilesyncUiAction
    data class SaveClicked(val node: FilesyncNode) : FilesyncUiAction
    data class AskShare(val nodes: List<FilesyncNode>) : FilesyncUiAction
    data class ShareTo(val target: FilesyncShareTarget) : FilesyncUiAction
    data object DismissShare : FilesyncUiAction
    data object CancelTransfer : FilesyncUiAction
    data class SaveHandedOff(val path: String) : FilesyncUiAction
    data class FilesPicked(val files: List<PlatformFile>) : FilesyncUiAction
    data class CreateFolder(val name: String) : FilesyncUiAction
    data class RetryUpload(val id: Uuid) : FilesyncUiAction
    data class DismissUpload(val id: Uuid) : FilesyncUiAction
    data object ToggleShowDeleted : FilesyncUiAction
    data class Restore(val nodeIds: Set<Uuid>) : FilesyncUiAction
    data class SortBy(val key: FilesyncSortKey) : FilesyncUiAction
    /** The tag already filtered on clears the filter. */
    data class FilterTag(val name: String) : FilesyncUiAction
    data class Select(val nodeId: Uuid, val mode: FilesyncSelectMode = FilesyncSelectMode.Only) : FilesyncUiAction
    data object SelectAll : FilesyncUiAction
    data object ClearSelection : FilesyncUiAction
    /** Shift+arrow when [extend]. */
    data class MoveSelection(val by: Int, val extend: Boolean = false) : FilesyncUiAction
    /** [bulk] on the selection: its live nodes as one ZIP, or its deleted ones restored. */
    data class Bulk(val bulk: FilesyncBulkAction) : FilesyncUiAction
    data class AskDelete(val nodes: List<FilesyncNode>) : FilesyncUiAction
    data object ConfirmDelete : FilesyncUiAction
    data object DismissDelete : FilesyncUiAction
    data class AskMove(val nodes: List<FilesyncNode>) : FilesyncUiAction
    data class MovePickerOpen(val folder: FilesyncNode) : FilesyncUiAction
    data class MovePickerToCrumb(val index: Int) : FilesyncUiAction
    data object ConfirmMove : FilesyncUiAction
    data object DismissMove : FilesyncUiAction
    data object MakePrivate : FilesyncUiAction
    data object SignInAgain : FilesyncUiAction
}

enum class FilesyncError { MoveFailed, DownloadFailed, Damaged, UploadFailed, TooLarge, StorageFull, FolderFailed, RestoreFailed, DeleteFailed, SignInAgainFailed }

/** What a finished restore or delete tells the user; [ChangedElsewhere] when some items were skipped. */
enum class FilesyncNotice { Restored, RestoredToRecovered, DeletedForever, ChangedElsewhere, TryAgainSoon }

sealed interface FilesyncUiEvent {
    data class OpenFile(val path: String) : FilesyncUiEvent
    data class SaveFile(val path: String, val name: String) : FilesyncUiEvent
    data class ShareFile(val path: String) : FilesyncUiEvent
    data class SendToChat(val path: String, val name: String) : FilesyncUiEvent
    data object OpenWebDrop : FilesyncUiEvent
    data class OpenSignIn(val url: String) : FilesyncUiEvent
    data class OpenOwnerConsole(val url: String) : FilesyncUiEvent
    data class Error(val error: FilesyncError) : FilesyncUiEvent
    data class Notice(val notice: FilesyncNotice) : FilesyncUiEvent
    /** [name] failed its hash check, so the ZIP holding it was thrown away. */
    data class ArchiveDamaged(val name: String) : FilesyncUiEvent
    /** Gone to Recently deleted, and back with Undo. */
    data class Removed(val nodeIds: Set<Uuid>) : FilesyncUiEvent
    data class Moved(val folderName: String?) : FilesyncUiEvent
}
