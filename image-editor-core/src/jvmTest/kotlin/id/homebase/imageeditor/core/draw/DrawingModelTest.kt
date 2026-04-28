package id.homebase.imageeditor.core.draw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawingModelTest {

    private fun drawStroke(model: DrawingModel) {
        model.beginStroke(BrushType.Pen, 0xFFFFFFFF.toInt(), 4f, 0f, 0f)
        model.extendStroke(10f, 10f)
        model.extendStroke(20f, 5f)
        model.commitStroke()
    }

    @Test fun freshModelHasNoStrokesNoUndo() {
        val m = DrawingModel()
        assertTrue(m.strokes.isEmpty())
        assertFalse(m.canUndo())
        assertFalse(m.canRedo())
    }

    @Test fun commitAddsStroke() {
        val m = DrawingModel()
        drawStroke(m)
        assertEquals(1, m.strokes.size)
        assertTrue(m.canUndo())
        assertFalse(m.canRedo())
    }

    /**
     * Regression: same off-by-one undo bug we fixed in the cropper. After
     * committing two strokes, the FIRST undo must remove the second stroke
     * (not be a no-op), and the SECOND undo must remove the first stroke
     * back to empty.
     */
    @Test fun undoIsNotOffByOne() {
        val m = DrawingModel()
        drawStroke(m)
        drawStroke(m)
        assertEquals(2, m.strokes.size)

        m.undo()
        assertEquals(1, m.strokes.size, "first undo should remove the second stroke")

        m.undo()
        assertEquals(0, m.strokes.size, "second undo should remove the first stroke")

        assertFalse(m.canUndo())
        assertTrue(m.canRedo())
    }

    @Test fun redoRestoresUndoneStroke() {
        val m = DrawingModel()
        drawStroke(m)
        drawStroke(m)
        m.undo()
        m.undo()
        m.redo()
        assertEquals(1, m.strokes.size)
        m.redo()
        assertEquals(2, m.strokes.size)
    }

    @Test fun cancelDropsInFlightWithoutTouchingUndoStack() {
        val m = DrawingModel()
        drawStroke(m) // sets up an undo entry
        m.beginStroke(BrushType.Pen, 0xFFFFFFFF.toInt(), 4f, 0f, 0f)
        m.cancelStroke()

        assertNull(m.inFlightStroke)
        assertEquals(1, m.strokes.size)
        // Cancelling pops the speculative undo push made by beginStroke,
        // so canUndo() reflects only the prior committed stroke.
        assertTrue(m.canUndo())
    }

    @Test fun clearAllIsUndoable() {
        val m = DrawingModel()
        drawStroke(m)
        drawStroke(m)
        m.clearAll()
        assertEquals(0, m.strokes.size)
        m.undo()
        assertEquals(2, m.strokes.size)
    }
}
