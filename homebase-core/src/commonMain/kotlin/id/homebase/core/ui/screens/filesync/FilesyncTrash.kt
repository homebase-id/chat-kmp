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

    // When the delete each node went with happened; a live folder passes a delete on only if it went too.
    private val wentWithParentAt: Map<Uuid, Long> = run {
        val memo = mutableMapOf<Uuid, Long?>()
        fun at(node: FilesyncNode, seen: MutableSet<Uuid>): Long? {
            if (node.nodeId in memo) return memo[node.nodeId]
            val parent = all.nodes[node.parentId]?.takeIf { seen.add(it.nodeId) }
            val folderAt = parent?.let { if (it.removed) it.updatedMs else at(it, seen) }
            return folderAt?.takeIf { wentWith(node, it) }.also { memo[node.nodeId] = it }
        }
        buildMap { for (node in all.nodes.values) at(node, mutableSetOf(node.nodeId))?.let { put(node.nodeId, it) } }
    }

    /** Each deleted node's item: itself when deleted on its own. A node not in here isn't deleted. */
    val itemOf: Map<Uuid, Uuid> = run {
        val memo = mutableMapOf<Uuid, Uuid?>()
        fun item(node: FilesyncNode, seen: MutableSet<Uuid>): Uuid? {
            if (node.nodeId in memo) return memo[node.nodeId]
            val parent = all.nodes[node.parentId]?.takeIf { node.nodeId in wentWithParentAt && seen.add(it.nodeId) }
            return (parent?.let { item(it, seen) } ?: node.nodeId.takeIf { node.removed }).also { memo[node.nodeId] = it }
        }
        buildMap { for (node in all.nodes.values) item(node, mutableSetOf(node.nodeId))?.let { put(node.nodeId, it) } }
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
            level = level.flatMap { children[it.nodeId].orEmpty() }.filter { it.nodeId in wentWithParentAt && seen.add(it.nodeId) }
            result += level
        }
        return result
    }

    /** [node] and the folders above it whose delete it went with, up to its item. */
    fun chainOf(node: FilesyncNode): List<FilesyncNode> {
        val chain = mutableListOf(node)
        val seen = mutableSetOf(node.nodeId)
        while (chain.last().nodeId in wentWithParentAt) {
            val parent = all.nodes.getValue(chain.last().parentId)
            if (!seen.add(parent.nodeId)) break
            chain += parent
        }
        return chain
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

    /** The items removed before [beforeMs]: what the purge deletes for good. */
    fun removedBefore(beforeMs: Long): Set<Uuid> =
        itemOf.values.filterTo(mutableSetOf()) { item -> all.nodes.getValue(item).removedMs?.let { it < beforeMs } == true }

    /** When [node] was deleted: its own removal, or the removal of the folder whose delete it went with. */
    fun removalMs(node: FilesyncNode): Long? = node.removedMs ?: wentWithParentAt[node.nodeId]

    /** [nodes] in the passes a delete for good takes: the files, then the folders from the deepest. */
    fun deleteOrder(nodes: Collection<FilesyncNode>): List<List<FilesyncNode>> {
        val (folders, files) = nodes.partition { it.isFolder }
        return listOf(files).filter { it.isNotEmpty() } +
            folders.groupBy { ancestorsOf(it).size }.entries.sortedByDescending { it.key }.map { it.value }
    }

    /** [items] and what was deleted with each. */
    fun deletedWith(items: Set<Uuid>): Set<Uuid> = itemOf.filterValues { it in items }.keys

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
