package id.homebase.imageeditor.core

import kotlin.uuid.Uuid

/**
 * Tree node carrying a persisted [localMatrix] and an in-flight [editorMatrix].
 *
 * The two-matrix split is load-bearing: gestures write to [editorMatrix] and
 * commit it via [commitEditorMatrix] when the gesture ends. Undo snapshots the
 * tree of [localMatrix] only, so a gesture in flight can be discarded without
 * polluting the history.
 *
 * Translated from `EditorElement.java` in Signal-Android (AGPL-3.0). The
 * renderer/animation/Parcelable subsystems were dropped — Compose handles
 * drawing and animation in [`image-editor-ui`], and undo persistence is a
 * snapshot of matrices not bytes.
 */
class EditorElement {
    val id: Uuid = Uuid.random()
    val flags: EditorFlags = EditorFlags()
    val localMatrix: Matrix2D = Matrix2D()
    val editorMatrix: Matrix2D = Matrix2D()

    private val mutableChildren: MutableList<EditorElement> = mutableListOf()
    val children: List<EditorElement> get() = mutableChildren

    fun addElement(element: EditorElement) {
        mutableChildren.add(element)
    }

    fun deleteAllChildren() {
        mutableChildren.clear()
    }

    val childCount: Int get() = mutableChildren.size

    fun getChild(index: Int): EditorElement = mutableChildren[index]

    /** Recursively visits every node in the subtree, including this node. */
    fun forAllInTree(block: (EditorElement) -> Unit) {
        block(this)
        for (c in mutableChildren) c.forAllInTree(block)
    }

    /** First descendant (including self) whose [id] matches. */
    fun findElementWithId(target: Uuid): EditorElement? {
        if (id == target) return this
        for (c in mutableChildren) {
            val match = c.findElementWithId(target)
            if (match != null) return match
        }
        return null
    }

    /** Parent of [element] within this subtree, or null if not present. */
    fun parentOf(element: EditorElement): EditorElement? {
        for (c in mutableChildren) {
            if (c === element) return this
            val deeper = c.parentOf(element)
            if (deeper != null) return deeper
        }
        return null
    }

    /** Composes the editor delta into [localMatrix] and resets the editor. */
    fun commitEditorMatrix() {
        if (flags.editable) {
            localMatrix.preConcat(editorMatrix)
            editorMatrix.reset()
        } else {
            editorMatrix.reset()
        }
    }

    fun rollbackEditorMatrix() {
        editorMatrix.reset()
    }

    fun localRotationAngle(): Float = MatrixUtils.getRotationAngle(localMatrix)
    fun localScaleX(): Float = MatrixUtils.getScaleX(localMatrix)
}
