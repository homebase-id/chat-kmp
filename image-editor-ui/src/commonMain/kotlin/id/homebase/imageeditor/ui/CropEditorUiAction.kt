package id.homebase.imageeditor.ui

import id.homebase.imageeditor.core.AspectMode

/**
 * One-shot user actions sent to [CropEditorViewModel].
 */
sealed interface CropEditorUiAction {
    data object BackClicked : CropEditorUiAction
    data object SaveClicked : CropEditorUiAction
    data object UndoClicked : CropEditorUiAction
    data object RedoClicked : CropEditorUiAction
    data object ResetClicked : CropEditorUiAction
    data object Rotate90ClockwiseClicked : CropEditorUiAction
    data class AspectChanged(val aspect: AspectMode) : CropEditorUiAction

    /**
     * Free-rotation dial committed a new value (in degrees, [-45, 45]).
     */
    data class FreeRotationChanged(val degrees: Float) : CropEditorUiAction

    /**
     * The user actually let go of the dial — push the rotation onto the undo
     * stack and reflow.
     */
    data object FreeRotationReleased : CropEditorUiAction
}
