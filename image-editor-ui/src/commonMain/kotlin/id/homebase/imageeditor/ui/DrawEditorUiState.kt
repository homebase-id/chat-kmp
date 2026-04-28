package id.homebase.imageeditor.ui

import androidx.compose.runtime.Immutable
import id.homebase.api.image.ImageResult
import id.homebase.imageeditor.core.Size
import id.homebase.imageeditor.core.draw.BrushType
import kotlin.uuid.Uuid

/** One-shot screen events. */
sealed interface DrawEditorUiEvent {
    data class DrawConfirmed(val requestId: Uuid, val result: ImageResult) : DrawEditorUiEvent
    data object Cancelled : DrawEditorUiEvent
}

/**
 * Slice of state the screen renders. Stroke list and viewport transform live
 * in [DrawEditorViewModel] as Compose `mutableStateOf` to drive gesture-rate
 * recomposition without round-tripping the StateFlow.
 */
@Immutable
data class DrawEditorUiState(
    val isLoading: Boolean = true,
    val naturalSize: Size? = null,
    val selectedBrush: BrushType = BrushType.Pen,
    /** Hue position 0..1 along the HSV slider. */
    val colorPosition: Float = 0.5f,
    /** Currently picked color, derived from [colorPosition] (cached). */
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    /**
     * Pen stroke thickness as a fraction of canonical bounds width
     * (`BrushType.BOUNDS_WIDTH = 2000`). Stored absolutely (not as a
     * percentage of pen's own range) so the unified width slider can show
     * pen and marker on the same global scale — toggling between brushes
     * visibly moves the thumb because the two fractions differ.
     *
     * Default ≈ 0.005 → ~5 px on a typical Desktop canvas.
     */
    val penThicknessFraction: Float = 0.005f,
    /**
     * Marker stroke thickness as a bounds-width fraction. Default ≈ 0.013
     * → ~12 px on Desktop, distinctly thicker than the pen default.
     */
    val highlighterThicknessFraction: Float = 0.013f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessageKey: String? = null,
    val uiEvent: DrawEditorUiEvent? = null,
)
