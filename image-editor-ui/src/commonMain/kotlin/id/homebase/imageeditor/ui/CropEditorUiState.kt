package id.homebase.imageeditor.ui

import androidx.compose.runtime.Immutable
import id.homebase.api.image.ImageResult
import id.homebase.imageeditor.core.AspectMode
import id.homebase.imageeditor.core.Size
import kotlin.uuid.Uuid

/**
 * One-shot screen events. Distinguishes "navigate away" from regular state.
 */
sealed interface CropEditorUiEvent {
    data class CropConfirmed(val requestId: Uuid, val result: ImageResult) : CropEditorUiEvent
    data object Cancelled : CropEditorUiEvent
}

/**
 * Slice of state the screen renders. The matrices live in [CropEditorViewModel]
 * as Compose `mutableStateOf` to drive gesture-rate updates without going
 * through the Flow.
 */
@Immutable
data class CropEditorUiState(
    val isLoading: Boolean = true,
    val naturalSize: Size? = null,
    val aspectMode: AspectMode = AspectMode.Free,
    /** True when thumb-drag preserves the current crop's aspect ratio. */
    val cropAspectLocked: Boolean = false,
    /** True when the caller pre-locked the aspect (e.g. a square avatar crop) — hides the chip row/lock toggle. */
    val aspectSwitcherHidden: Boolean = false,
    val freeRotationDegrees: Float = 0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessageKey: String? = null,
    val uiEvent: CropEditorUiEvent? = null,
)
