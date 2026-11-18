package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import id.homebase.core.filesync.FilesyncProtocol
import kotlin.uuid.Uuid

/** An item of Recently deleted, deleted on its own; [count] is how many removed nodes are below it, whenever each was removed. */
@Immutable
data class FilesyncTrashItem(val node: FilesyncNode, val count: Int)

/**
 * Recently deleted by the server's time (filesync node schema, "Recently deleted"). [all] holds the removed nodes and
 * the live ones below removed folders: a folder's delete removes the folder first, so what it takes along may still be
 * live, and counts as deleted with it when written no later than the folder's removal.
 */
internal class FilesyncTrash(val all: FilesyncNodes) {
    private val children = all.nodes.values.groupBy { it.parentId }

    // The nodes that went with their folder's delete; a live folder passes a delete on only if it went too.
    private val wentWithParent: Set<Uuid> = run {
        val memo = mutableMapOf<Uuid, Long?>()
        fun at(node: FilesyncNode, seen: MutableSet<Uuid>): Long? {
            if (node.nodeId in memo) return memo[node.nodeId]
            val parent = all.nodes[node.parentId]?.takeIf { seen.add(it.nodeId) }
            val folderAt = parent?.let { it.removedMs ?: at(it, seen) }
            return folderAt?.takeIf { wentWith(node, it) }.also { memo[node.nodeId] = it }
        }
        all.nodes.keys.filterTo(mutableSetOf()) { id -> at(all.nodes.getValue(id), mutableSetOf(id)) != null }
    }

    /** Each deleted node's item: itself when deleted on its own. A node not in here isn't deleted. */
    val itemOf: Map<Uuid, Uuid> = run {
        val memo = mutableMapOf<Uuid, Uuid?>()
        fun item(node: FilesyncNode, seen: MutableSet<Uuid>): Uuid? {
            if (node.nodeId in memo) return memo[node.nodeId]
            val parent = all.nodes[node.parentId]?.takeIf { node.nodeId in wentWithParent && seen.add(it.nodeId) }
            return (parent?.let { item(it, seen) } ?: node.nodeId.takeIf { node.removed }).also { memo[node.nodeId] = it }
        }
        buildMap { for (node in all.nodes.values) item(node, mutableSetOf(node.nodeId))?.let { put(node.nodeId, it) } }
    }

    /** The items deleted on their own, newest first. */
    fun items(): List<FilesyncTrashItem> =
        itemOf.filter { (node, item) -> node == item }.keys.map { all.nodes.getValue(it) }
            .sortedWith(compareByDescending<FilesyncNode> { it.removedMs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { FilesyncTrashItem(it, inside(setOf(it.nodeId)).size - 1) }

    /** [items] and every removed node below them through removed folders, whenever each was removed: what emptying them takes. */
    fun inside(items: Set<Uuid>): Set<Uuid> =
        below(items.mapNotNull { id -> all.nodes[id]?.takeIf { it.removed } }) { it.removed }.mapTo(mutableSetOf()) { it.nodeId }

    /** [node] and what was deleted with it, removed or still live, each after its folder. */
    fun group(node: FilesyncNode): List<FilesyncNode> = below(listOf(node)) { it.nodeId in wentWithParent }

    /** [starts] and the nodes below them that [follow] takes, level by level, each once (a parent cycle ends). */
    private fun below(starts: List<FilesyncNode>, follow: (FilesyncNode) -> Boolean): List<FilesyncNode> {
        val result = starts.toMutableList()
        val seen = starts.mapTo(mutableSetOf()) { it.nodeId }
        var level = starts
        while (level.isNotEmpty()) {
            level = level.flatMap { children[it.nodeId].orEmpty() }.filter { follow(it) && seen.add(it.nodeId) }
            result += level
        }
        return result
    }

    /** [node] and the folders above it whose delete it went with, up to its item or a folder in [stopAt] (one a restore moves). */
    fun chainOf(node: FilesyncNode, stopAt: Set<Uuid> = emptySet()): List<FilesyncNode> {
        val chain = mutableListOf(node)
        val seen = mutableSetOf(node.nodeId)
        while (chain.last().nodeId in wentWithParent && chain.last().nodeId !in stopAt) {
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

    /** The items the purge deletes for good: each whose every removed node was removed before [beforeMs], each by its own removal. */
    fun removedBefore(beforeMs: Long): Set<Uuid> {
        val waiting = itemOf.filterKeys { id -> all.nodes.getValue(id).removedMs?.let { it >= beforeMs } == true }.values
        return itemOf.values.toSet() - waiting.toSet()
    }

    /** [nodes] in the passes a delete for good takes: the files, then the folders from the deepest. */
    fun deleteOrder(nodes: Collection<FilesyncNode>): List<List<FilesyncNode>> {
        val (folders, files) = nodes.partition { it.isFolder }
        return listOf(files) +
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

    /**
     * Where restoring [tops] breaks the loops of removed folders above them, each in the next (two writers' moves left one),
     * as filesync's plan_restore_from does. A loop's fixed folders come back at the top; a loop without one comes back
     * through one folder, the item where it is one of the loop, else the loop's folder the walk up from it met first.
     */
    fun loopBreaks(tops: List<FilesyncNode>, plans: List<List<FilesyncNode>>): FilesyncLoopBreaks {
        val atTop = mutableSetOf<Uuid>()
        val toExit = mutableSetOf<Uuid>()
        val planned = mutableSetOf<Uuid>()
        for ((top, plan) in tops.zip(plans)) {
            if (top.nodeId !in planned) {
                val up = ancestorsOf(top)
                val walked = up.takeWhile { it.removed && it.nodeId !in planned }
                // ancestorsOf ends where a parent repeats: then the walk went round a loop back to [again] (the top itself too).
                val again = (walked.lastOrNull() ?: top).parentId.takeIf { walked.size == up.size }
                val round = if (again == top.nodeId) listOf(top) + walked else walked.dropWhile { it.nodeId != again }
                val fixed = round.filter { !it.movable || it.nodeId == FilesyncProtocol.DesktopsNodeId }
                if (round.isNotEmpty() && fixed.isEmpty()) toExit += round.first().nodeId
                fixed.mapTo(atTop) { it.nodeId }
            }
            plan.mapTo(planned) { it.nodeId }
        }
        return FilesyncLoopBreaks(atTop, toExit)
    }

    companion object {
        /** Whether [node], in a folder deleted at [folderDeletedMs], went with it: removed no earlier, or still live and written no later. */
        fun wentWith(node: FilesyncNode, folderDeletedMs: Long): Boolean =
            node.versionMs.let { at -> if (node.removed) at == null || at >= folderDeletedMs else at != null && at <= folderDeletedMs }
    }
}

/** [atTop]: fixed folders of a loop, back at the top. [toExit]: each loop's one folder, back in Recovered files or else at the top. */
internal class FilesyncLoopBreaks(val atTop: Set<Uuid>, val toExit: Set<Uuid>)
