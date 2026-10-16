package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import kotlin.uuid.Uuid

/** An item of Recently deleted, deleted on its own; [count] is how many below it were deleted with it. */
@Immutable
data class FilesyncTrashItem(val node: FilesyncNode, val count: Int)

/**
 * Recently deleted by the server's time (filesync node schema, "Recently deleted"). [all] holds the removed nodes and
 * the live ones below removed folders: a folder's delete removes the folder first, so what it takes along may still be
 * live, and counts as deleted with it when written no later than the folder's removal.
 */
internal class FilesyncTrash(val all: FilesyncNodes) {
    private val children = all.nodes.values.groupBy { it.parentId }

    /** Each deleted node's item: itself when deleted on its own. A node not in here isn't deleted. */
    val itemOf: Map<Uuid, Uuid> = buildMap {
        for (node in all.nodes.values) itemOf(node)?.let { put(node.nodeId, it.nodeId) }
    }

    /** The removed nodes and the live ones a folder's delete took along. */
    val deleted: FilesyncNodes = all.only(itemOf.keys)

    /** The items deleted on their own, newest first. */
    fun items(): List<FilesyncTrashItem> {
        val sizes = itemOf.values.groupingBy { it }.eachCount()
        return itemOf.filter { (node, item) -> node == item }.keys.map { all.nodes.getValue(it) }
            .sortedWith(compareByDescending<FilesyncNode> { it.removedMs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { FilesyncTrashItem(it, sizes.getValue(it.nodeId) - 1) }
    }

    /** [item] and what was deleted with it, removed or still live, each after its folder. */
    fun group(item: FilesyncNode): List<FilesyncNode> {
        val result = mutableListOf(item)
        val seen = mutableSetOf(item.nodeId)
        var level = listOf(item)
        while (level.isNotEmpty()) {
            level = level.flatMap { children[it.nodeId].orEmpty() }.filter { seen.add(it.nodeId) }
            result += level.filter { itemOf[it.nodeId] == item.nodeId }
        }
        return result
    }

    /** What restoring [item] writes, in order: the removed folders above it alone, outermost first, then it and what was removed with it. */
    fun restorePlan(item: FilesyncNode): List<FilesyncNode> =
        removedParents(item).asReversed() + group(item).filter { it.removed }

    private fun itemOf(node: FilesyncNode): FilesyncNode? {
        var current = node
        for (folder in removedParents(node)) {
            if (!wentWith(current, folder.updatedMs)) break
            current = folder
        }
        return current.takeIf { it.removed }
    }

    /** The removed folders above [node], nearest first; a groupId cycle from a bad row ends where it repeats. */
    private fun removedParents(node: FilesyncNode): List<FilesyncNode> {
        val chain = mutableListOf<FilesyncNode>()
        val seen = mutableSetOf(node.nodeId)
        var parent = all.nodes[node.parentId]
        while (parent != null && seen.add(parent.nodeId)) {
            if (parent.removed) chain += parent
            parent = all.nodes[parent.parentId]
        }
        return chain
    }

    companion object {
        /** Whether [node], below a folder removed at [folderRemovedMs], went with it: removed no earlier, or still live and written no later. */
        fun wentWith(node: FilesyncNode, folderRemovedMs: Long): Boolean =
            if (node.removed) node.updatedMs >= folderRemovedMs else node.updatedMs <= folderRemovedMs
    }
}
