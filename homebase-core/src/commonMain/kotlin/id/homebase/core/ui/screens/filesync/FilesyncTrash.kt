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

    // The nodes that went with their folder's delete; a live folder passes it on only if it went too.
    private val wentWithParent: Set<Uuid> = run {
        val liveDeletedAt = mutableMapOf<Uuid, Long?>()
        // When the delete that took a node happened: its own removal, or a live node's folder's; null when it isn't deleted.
        fun deletedAt(node: FilesyncNode, seen: MutableSet<Uuid>): Long? {
            if (node.removed) return node.updatedMs
            if (node.nodeId in liveDeletedAt) return liveDeletedAt[node.nodeId]
            val parent = all.nodes[node.parentId]?.takeIf { seen.add(it.nodeId) }
            return parent?.let { deletedAt(it, seen) }?.takeIf { wentWith(node, it) }
                .also { liveDeletedAt[node.nodeId] = it }
        }
        all.nodes.values.filter { node ->
            val parent = all.nodes[node.parentId] ?: return@filter false
            deletedAt(parent, mutableSetOf(node.nodeId, parent.nodeId))?.let { wentWith(node, it) } == true
        }.mapTo(mutableSetOf()) { it.nodeId }
    }

    /** Each deleted node's item: itself when deleted on its own. A node not in here isn't deleted. */
    val itemOf: Map<Uuid, Uuid> = buildMap {
        for (node in all.nodes.values) {
            val top = chainOf(node).last()
            if (top.removed) put(node.nodeId, top.nodeId)
        }
    }

    /** The items deleted on their own, newest first. */
    fun items(): List<FilesyncTrashItem> {
        val sizes = itemOf.values.groupingBy { it }.eachCount()
        return itemOf.filter { (node, item) -> node == item }.keys.map { all.nodes.getValue(it) }
            .sortedWith(compareByDescending<FilesyncNode> { it.removedMs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { FilesyncTrashItem(it, sizes.getValue(it.nodeId) - 1) }
    }

    /** [node] and what was deleted with it, removed or still live, each after its folder. */
    fun group(node: FilesyncNode): List<FilesyncNode> {
        val result = mutableListOf(node)
        val seen = mutableSetOf(node.nodeId)
        var level = listOf(node)
        while (level.isNotEmpty()) {
            level = level.flatMap { children[it.nodeId].orEmpty() }.filter { it.nodeId in wentWithParent && seen.add(it.nodeId) }
            result += level
        }
        return result
    }

    /** [node] and the folders above it whose delete it went with, up to its item. */
    fun chainOf(node: FilesyncNode): List<FilesyncNode> {
        val path = listOf(node) + ancestorsOf(node)
        val end = path.indexOfFirst { it.nodeId !in wentWithParent }.takeIf { it >= 0 } ?: path.lastIndex
        return path.take(end + 1)
    }

    /** The known folders above [node], nearest first. */
    fun ancestorsOf(node: FilesyncNode): List<FilesyncNode> {
        val chain = mutableListOf<FilesyncNode>()
        val seen = mutableSetOf(node.nodeId)
        var parent = all.nodes[node.parentId]
        while (parent != null && seen.add(parent.nodeId)) {
            chain += parent
            parent = all.nodes[parent.parentId]
        }
        return chain
    }

    /** The items removed before [beforeMs], each with what was deleted with it: what the purge deletes for good. */
    fun removedBefore(beforeMs: Long): Set<Uuid> =
        itemOf.filterValues { all.nodes.getValue(it).updatedMs < beforeMs }.keys

    /**
     * What restoring [node] writes, in order: the removed folders above it alone, outermost first, up to the first live
     * folder that isn't deleted, then it and what was removed with it.
     */
    fun restorePlan(node: FilesyncNode): List<FilesyncNode> =
        ancestorsOf(node).takeWhile { it.removed || it.nodeId in itemOf }.filter { it.removed }.asReversed() +
            group(node).filter { it.removed }

    companion object {
        /** Whether [node], in a folder deleted at [folderDeletedMs], went with it: removed no earlier, or still live and written no later. */
        fun wentWith(node: FilesyncNode, folderDeletedMs: Long): Boolean =
            if (node.removed) node.updatedMs >= folderDeletedMs else node.updatedMs <= folderDeletedMs
    }
}
