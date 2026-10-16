package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

/** An item of Recently deleted, deleted on its own; [count] is how many below it were deleted with it. */
@Immutable
data class FilesyncTrashItem(val node: FilesyncNode, val count: Int)

/**
 * Recently deleted by the server's time (filesync node schema, "Recently deleted"). Every map holds the removed nodes
 * and the live ones below removed folders: a folder's delete removes the folder first, so what it takes along may
 * still be live, and counts as deleted with it when written no later than the folder's removal.
 */
internal object FilesyncTrash {

    /** The items deleted on their own, newest first. */
    fun items(nodes: Map<Uuid, FilesyncNode>): List<FilesyncTrashItem> {
        val counts = mutableMapOf<Uuid, Int>()
        val items = mutableListOf<FilesyncNode>()
        for (node in nodes.values) {
            val item = itemOf(node, nodes) ?: continue
            if (item.nodeId == node.nodeId) items += node else counts[item.nodeId] = (counts[item.nodeId] ?: 0) + 1
        }
        return items
            .sortedWith(compareByDescending<FilesyncNode> { it.removedMs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { FilesyncTrashItem(it, counts[it.nodeId] ?: 0) }
    }

    /** The item whose delete took [node]: itself when deleted on its own, null when it isn't deleted at all. */
    fun itemOf(node: FilesyncNode, nodes: Map<Uuid, FilesyncNode>): FilesyncNode? {
        val seen = mutableSetOf(node.nodeId)
        var current = node
        while (true) {
            val folder = removedFolderOf(current, nodes, seen)
            val folderRemovedMs = folder?.removedMs ?: return current.takeIf { it.removedMs != null }
            val removedMs = current.removedMs
            val withFolder = if (removedMs != null) removedMs >= folderRemovedMs else current.updatedMs <= folderRemovedMs
            if (!withFolder) return current.takeIf { removedMs != null }
            current = folder
        }
    }

    /** Every deleted node: the removed ones and the live ones a folder's delete took along. */
    fun deleted(nodes: Map<Uuid, FilesyncNode>): Map<Uuid, FilesyncNode> = nodes.filterValues { itemOf(it, nodes) != null }

    /** [item] and what was deleted with it, removed or still live, each after its folder. */
    fun group(item: FilesyncNode, nodes: Map<Uuid, FilesyncNode>): List<FilesyncNode> {
        val children = nodes.values.groupBy { it.parentId }
        val below = mutableListOf<FilesyncNode>()
        val seen = mutableSetOf(item.nodeId)
        var level = listOf(item)
        while (level.isNotEmpty()) {
            level = level.flatMap { children[it.nodeId].orEmpty() }.filter { seen.add(it.nodeId) }
            below += level
        }
        return listOf(item) + below.filter { itemOf(it, nodes)?.nodeId == item.nodeId }
    }

    /** The removed folders above [node], outermost first; a groupId cycle from a bad row ends where it repeats. */
    fun removedParents(node: FilesyncNode, nodes: Map<Uuid, FilesyncNode>): List<FilesyncNode> {
        val chain = mutableListOf<FilesyncNode>()
        val seen = mutableSetOf(node.nodeId)
        var parent = nodes[node.parentId]
        while (parent != null && seen.add(parent.nodeId)) {
            if (parent.removedMs != null) chain += parent
            parent = nodes[parent.parentId]
        }
        return chain.asReversed()
    }

    /** What restoring [item] writes, in order: the removed folders above it alone, then it and what was removed with it. */
    fun restorePlan(item: FilesyncNode, nodes: Map<Uuid, FilesyncNode>): List<FilesyncNode> =
        removedParents(item, nodes) + group(item, nodes).filter { it.removedMs != null }

    private fun removedFolderOf(node: FilesyncNode, nodes: Map<Uuid, FilesyncNode>, seen: MutableSet<Uuid>): FilesyncNode? {
        var parent = nodes[node.parentId]
        while (parent != null && seen.add(parent.nodeId)) {
            if (parent.removedMs != null) return parent
            parent = nodes[parent.parentId]
        }
        return null
    }
}
