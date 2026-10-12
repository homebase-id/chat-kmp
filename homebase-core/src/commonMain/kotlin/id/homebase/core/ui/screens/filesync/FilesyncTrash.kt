package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

/** A topmost item of Recently deleted; [count] is how many removed items below it come back with it. */
@Immutable
data class FilesyncTrashItem(val node: FilesyncNode, val count: Int)

/** Recently deleted the way the desktop reads it (filesync deleted.rs, tray pages.rs); every map holds removed nodes only. */
internal object FilesyncTrash {
    // A groupId cycle can't come from a client, but a bad row mustn't hang the screen.
    private const val MAX_DEPTH = 256

    /** The items whose parent isn't removed too, newest first. */
    fun topmost(removed: Map<Uuid, FilesyncNode>): List<FilesyncTrashItem> {
        val counts = mutableMapOf<Uuid, Int>()
        for (node in removed.values) {
            val top = removedParents(node, removed).firstOrNull() ?: continue
            if (top.removal?.root == node.removal?.root) counts[top.nodeId] = (counts[top.nodeId] ?: 0) + 1
        }
        return removed.values
            .filter { it.parentId !in removed }
            .sortedWith(compareByDescending<FilesyncNode> { it.removal?.atMs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { FilesyncTrashItem(it, counts[it.nodeId] ?: 0) }
    }

    /** [top] and the removed items below it that its delete took along, each after its folder. */
    fun group(top: FilesyncNode, removed: Map<Uuid, FilesyncNode>): List<FilesyncNode> {
        val children = removed.values.groupBy { it.parentId }
        val result = mutableListOf(top)
        val seen = mutableSetOf(top.nodeId)
        var level = listOf(top)
        while (level.isNotEmpty() && seen.size < removed.size + 1) {
            level = level.flatMap { children[it.nodeId].orEmpty() }
                .filter { it.removal?.root == top.removal?.root && seen.add(it.nodeId) }
            result += level
        }
        return result
    }

    /** The removed folders above [node], outermost first. */
    fun removedParents(node: FilesyncNode, removed: Map<Uuid, FilesyncNode>): List<FilesyncNode> {
        val chain = mutableListOf<FilesyncNode>()
        var parent = removed[node.parentId]
        while (parent != null && parent.nodeId != node.nodeId && chain.size < MAX_DEPTH && chain.none { it.nodeId == parent!!.nodeId }) {
            chain += parent
            parent = removed[parent.parentId]
        }
        return chain.asReversed()
    }

    /** What restoring [top] writes, in order: its removed folders first, so each item has a folder to go back to. */
    fun restorePlan(top: FilesyncNode, removed: Map<Uuid, FilesyncNode>): List<FilesyncNode> =
        removedParents(top, removed) + group(top, removed)

    /** Files before folders, as the desktop deletes them for good. */
    fun emptyOrder(nodes: Collection<FilesyncNode>): List<FilesyncNode> = nodes.sortedBy { it.isFolder }
}
