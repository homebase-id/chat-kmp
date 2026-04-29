package id.homebase.imageeditor.ui

import id.homebase.imageeditor.core.draw.BrushType

/** One-shot user actions sent to [DrawEditorViewModel]. */
sealed interface DrawEditorUiAction {
    data object BackClicked : DrawEditorUiAction
    data object SaveClicked : DrawEditorUiAction
    data object UndoClicked : DrawEditorUiAction
    data object RedoClicked : DrawEditorUiAction
    data object ResetClicked : DrawEditorUiAction
    data class BrushSelected(val brush: BrushType) : DrawEditorUiAction

    /** Slider position 0..1; ViewModel maps to ARGB. */
    data class ColorChanged(val position: Float) : DrawEditorUiAction

    /**
     * Slider position (0..1) on the *unified* thickness scale spanning all
     * brushes' ranges. The ViewModel maps this to a bounds fraction,
     * clamps to the active brush's `[min, max]`, and stores the result for
     * that brush.
     */
    data class ThicknessSliderPositionChanged(val position: Float) : DrawEditorUiAction
}
