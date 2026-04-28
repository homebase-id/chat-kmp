package id.homebase.imageeditor.core

import kotlin.uuid.Uuid

/**
 * Snapshot of every element's [localMatrix] in a tree, plus a few flags. We
 * snapshot only the persisted state, so an in-flight gesture is excluded.
 *
 * Equivalent to Signal's `ElementStack`/`UndoRedoStacks` (AGPL-3.0) compressed
 * to a per-element matrix map. The crop subset of the editor doesn't need
 * tree-shape persistence — the tree shape is fixed at creation.
 */
internal class TreeSnapshot(private val matrices: Map<Uuid, FloatArray>) {

    fun applyTo(root: EditorElement) {
        root.forAllInTree { e ->
            matrices[e.id]?.let { e.localMatrix.setValues(it) }
        }
    }

    fun matches(root: EditorElement): Boolean {
        var match = true
        root.forAllInTree { e ->
            if (!match) return@forAllInTree
            val stored = matrices[e.id] ?: run { match = false; return@forAllInTree }
            for (i in 0 until 9) if (stored[i] != e.localMatrix.values[i]) {
                match = false
                return@forAllInTree
            }
        }
        return match
    }

    companion object {
        fun capture(root: EditorElement): TreeSnapshot {
            val map = mutableMapOf<Uuid, FloatArray>()
            root.forAllInTree { e -> map[e.id] = e.localMatrix.values.copyOf() }
            return TreeSnapshot(map)
        }
    }
}

/** Bounded LIFO stack of [TreeSnapshot]s. */
internal class SnapshotStack(private val capacity: Int) {
    private val stack: ArrayDeque<TreeSnapshot> = ArrayDeque()

    val isEmpty: Boolean get() = stack.isEmpty()
    val size: Int get() = stack.size

    /** Push if the snapshot differs from the current top; drop oldest on overflow. */
    fun tryPush(snapshot: TreeSnapshot, currentRoot: EditorElement) {
        val top = stack.lastOrNull()
        if (top != null && top.matches(currentRoot)) return
        stack.addLast(snapshot)
        while (stack.size > capacity) stack.removeFirst()
    }

    fun pop(): TreeSnapshot? = if (stack.isEmpty()) null else stack.removeLast()

    fun clear() { stack.clear() }
}

/**
 * The pair of (undo, redo) snapshot stacks used by the editor.
 */
internal class UndoRedoStacks(capacity: Int = 50) {
    val undo: SnapshotStack = SnapshotStack(capacity)
    val redo: SnapshotStack = SnapshotStack(capacity)

    fun pushState(root: EditorElement) {
        undo.tryPush(TreeSnapshot.capture(root), root)
        redo.clear()
    }

    fun canUndo(): Boolean = !undo.isEmpty
    fun canRedo(): Boolean = !redo.isEmpty

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
