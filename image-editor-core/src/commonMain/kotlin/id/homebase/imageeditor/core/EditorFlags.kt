package id.homebase.imageeditor.core

/**
 * Per-element flag bag that controls editing semantics. The crop pipeline only
 * cares about `aspectLocked`, `rotateLocked`, `editable`, and `visible` — the
 * rest are kept for parity with Signal-Android's editor and can be used if we
 * later add overlays.
 */
class EditorFlags {
    var visible: Boolean = true
    var childrenVisible: Boolean = true
    var selectable: Boolean = true
    var editable: Boolean = true
    var aspectLocked: Boolean = false
    var rotateLocked: Boolean = false

    fun copy(): EditorFlags {
        val c = EditorFlags()
        c.visible = visible
        c.childrenVisible = childrenVisible
        c.selectable = selectable
        c.editable = editable
        c.aspectLocked = aspectLocked
        c.rotateLocked = rotateLocked
        return c
    }

    fun set(other: EditorFlags) {
        visible = other.visible
        childrenVisible = other.childrenVisible
        selectable = other.selectable
        editable = other.editable
        aspectLocked = other.aspectLocked
        rotateLocked = other.rotateLocked
    }
}
