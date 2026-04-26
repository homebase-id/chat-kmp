package id.homebase.imageeditor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.core.ui.navigation.Route
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.toImageBitmap
import id.homebase.imageeditor.core.AspectMode
import id.homebase.imageeditor.core.EditorModel
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.core.Size
import id.homebase.imageeditor.core.io.CropFinalizer
import id.homebase.imageeditor.core.io.CropPreprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Backs [CropScreen]. Owns the [EditorModel], the in-memory source bytes, and
 * the gesture-rate matrix snapshot used by the canvas/overlay composables.
 */
class CropEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val resultBus: CropResultBus,
) : ViewModel() {

    /** Read once from the navigation route — keys both the source and the result. */
    val requestId: Uuid = Uuid.parse(savedStateHandle.toRoute<Route.Crop>().requestId)

    private val _uiState = MutableStateFlow(CropEditorUiState())
    val uiState: StateFlow<CropEditorUiState> = _uiState.asStateFlow()

    /** Compose-tracked snapshot of the matrices that drive painting. Bumped on every gesture frame. */
    var matrixSnapshot: MatrixSnapshot by mutableStateOf(MatrixSnapshot.EMPTY)
        private set

    /** Decoded preview bitmap. Compose-tracked so the canvas updates when prepare() resolves. */
    var previewBitmap: ImageBitmap? by mutableStateOf(null)
        private set

    private var originalBytes: ByteArray? = null
    var model: EditorModel = EditorModel.create()
        private set

    private var visibleViewportPx: RectF = RectF()

    private var loaded: Boolean = false

    /**
     * Run the preprocessor and configure the [EditorModel]. Idempotent; safe
     * to call from a [LaunchedEffect].
     */
    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val bytes = resultBus.takeSource(requestId)
            if (bytes == null) {
                Logger.w(tag = TAG) { "no source bytes for $requestId" }
                _uiState.update { it.copy(isLoading = false, errorMessageKey = "crop_error_load") }
                return@launch
            }
            originalBytes = bytes

            val prep = withContext(Dispatchers.Default) {
                CropPreprocessor.prepare(bytes)
            }
            if (prep == null) {
                _uiState.update { it.copy(isLoading = false, errorMessageKey = "crop_error_load") }
                return@launch
            }
            previewBitmap = prep.previewBytes.toImageBitmap()
            model.onImageReady(prep.naturalSize)
            if (!visibleViewportPx.isEmpty()) {
                model.setVisibleViewPort(visibleViewportPx)
            }
            snapshotMatrices()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    naturalSize = prep.naturalSize,
                    canUndo = model.canUndo(),
                    canRedo = model.canRedo(),
                )
            }
        }
    }

    /**
     * The screen tells us how big the visible viewport is in pixels (after
     * Modifier.onSizeChanged on the canvas). The model uses this to keep the
     * crop centered.
     */
    fun setVisibleViewport(width: Float, height: Float) {
        visibleViewportPx = RectF(0f, 0f, width, height)
        if (uiState.value.naturalSize != null) {
            model.setVisibleViewPort(visibleViewportPx)
            snapshotMatrices()
        }
    }

    /** After every gesture frame the gesture handler calls this. */
    fun snapshotMatrices() {
        matrixSnapshot = MatrixSnapshot.capture(model)
    }

    fun onUiAction(action: CropEditorUiAction) {
        when (action) {
            CropEditorUiAction.BackClicked -> _uiState.update {
                it.copy(uiEvent = CropEditorUiEvent.Cancelled)
            }
            CropEditorUiAction.SaveClicked -> save()
            CropEditorUiAction.UndoClicked -> {
                model.undo()
                snapshotMatrices()
                refreshUndoRedo()
            }
            CropEditorUiAction.RedoClicked -> {
                model.redo()
                snapshotMatrices()
                refreshUndoRedo()
            }
            CropEditorUiAction.ResetClicked -> {
                model.reset()
                snapshotMatrices()
                _uiState.update {
                    it.copy(
                        aspectMode = AspectMode.Free,
                        freeRotationDegrees = 0f,
                        canUndo = false,
                        canRedo = false,
                    )
                }
            }
            CropEditorUiAction.Rotate90ClockwiseClicked -> {
                model.rotate90Clockwise()
                snapshotMatrices()
                refreshUndoRedo()
            }
            is CropEditorUiAction.AspectChanged -> {
                model.pushUndoPoint()
                model.setFixedRatio(action.aspect.ratio)
                snapshotMatrices()
                _uiState.update { it.copy(aspectMode = action.aspect) }
                refreshUndoRedo()
            }
            is CropEditorUiAction.FreeRotationChanged -> {
                model.setMainImageEditorMatrixRotation(action.degrees)
                snapshotMatrices()
                _uiState.update { it.copy(freeRotationDegrees = action.degrees) }
            }
            CropEditorUiAction.FreeRotationReleased -> {
                // Commit the editor matrix into local so undo captures it.
                model.hierarchy.mainImage()?.commitEditorMatrix()
                model.pushUndoPoint()
                snapshotMatrices()
                refreshUndoRedo()
            }
        }
    }

    private fun refreshUndoRedo() {
        _uiState.update {
            it.copy(
                canUndo = model.canUndo(),
                canRedo = model.canRedo(),
            )
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun save() {
        val bytes = originalBytes ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    CropFinalizer.finalize(
                        originalBytes = bytes,
                        model = model,
                        outputFormat = ImageFormat.JPEG,
                        quality = 90,
                    )
                }
                resultBus.postResult(requestId, result)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        uiEvent = CropEditorUiEvent.CropConfirmed(requestId, result),
                    )
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "finalize failed" }
                _uiState.update {
                    it.copy(isSaving = false, errorMessageKey = "crop_error_save")
                }
            }
        }
    }

    /**
     * Pan the main image directly. Called from the gesture handler.
     *
     * @param dxPx pan in canvas pixels
     */
    fun panMainImage(dxPx: Float, dyPx: Float) {
        // Convert canvas-pixel pan to view-space, then to mainImage-pixel space.
        val main = model.hierarchy.mainImage() ?: return
        // canvas → view-space: divide by view.localMatrix scale (uniform)
        val viewLocal = model.hierarchy.view.localMatrix
        val viewInv = Matrix2D()
        if (!viewLocal.invert(viewInv)) return
        val out = viewInv.mapPoint(dxPx, dyPx)
        val zero = viewInv.mapPoint(0f, 0f)
        val viewDx = out[0] - zero[0]
        val viewDy = out[1] - zero[1]

        // view-space → mainImage-pixel space requires inverting flipRotate * mainImage.local
        val parentChain = Matrix2D(model.hierarchy.flipRotate.localMatrix)
        parentChain.preConcat(main.localMatrix)
        val parentInv = Matrix2D()
        if (!parentChain.invert(parentInv)) return
        val outP = parentInv.mapPoint(viewDx, viewDy)
        val zeroP = parentInv.mapPoint(0f, 0f)
        val pxDx = outP[0] - zeroP[0]
        val pxDy = outP[1] - zeroP[1]

        main.editorMatrix.postTranslate(pxDx, pxDy)
        snapshotMatrices()
    }

    /**
     * Pinch-zoom the main image around the centroid (in canvas pixels).
     */
    fun zoomMainImage(scale: Float, centroidPx: Pair<Float, Float>) {
        val main = model.hierarchy.mainImage() ?: return
        // Centroid in view-space
        val viewInv = Matrix2D()
        if (!model.hierarchy.view.localMatrix.invert(viewInv)) return
        val centroidView = viewInv.mapPoint(centroidPx.first, centroidPx.second)

        // Centroid in mainImage-pixel space
        val parentChain = Matrix2D(model.hierarchy.flipRotate.localMatrix)
        parentChain.preConcat(main.localMatrix)
        val parentInv = Matrix2D()
        if (!parentChain.invert(parentInv)) return
        val centroidPx2 = parentInv.mapPoint(centroidView[0], centroidView[1])

        main.editorMatrix.postScale(scale, scale, centroidPx2[0], centroidPx2[1])
        snapshotMatrices()
    }

    /** Called when the user releases the pan/pinch gesture on the main image. */
    fun commitMainImageGesture() {
        val main = model.hierarchy.mainImage() ?: return
        if (main.editorMatrix.isIdentity()) return
        main.commitEditorMatrix()
        model.postEdit(allowScaleToRepairCrop = false)
        model.pushUndoPoint()
        snapshotMatrices()
        refreshUndoRedo()
    }

    /**
     * Begin a corner-thumb drag on the user crop. Returns a session the
     * gesture handler will feed [onThumbDragMove] until release.
     *
     * The "inverse" matrix maps canvas-pixel coords to cropEditorElement-
     * pixel-space (= [Bounds.FULL_BOUNDS] coords).
     */
    fun startThumbDrag(
        controlPoint: id.homebase.imageeditor.core.ControlPoint,
        screenPoint: id.homebase.imageeditor.core.PointF,
    ): id.homebase.imageeditor.core.session.EditSession? {
        // canvas → cropEditorElement-pixel-space
        //   = inv(view.local * flipRotate.local * imageCrop.local * cropEditorElement.local)
        val chain = Matrix2D(model.hierarchy.view.localMatrix)
        chain.preConcat(model.hierarchy.flipRotate.localMatrix)
        chain.preConcat(model.hierarchy.imageCrop.localMatrix)
        chain.preConcat(model.hierarchy.cropEditorElement.localMatrix)
        val inverse = Matrix2D()
        if (!chain.invert(inverse)) return null
        return model.startCropThumbDrag(
            screenToCrop = inverse,
            thumbContainerRelativeMatrix = Matrix2D(),
            controlPoint = controlPoint,
            screenPoint = screenPoint,
        )
    }

    /** Forward a pointer move to an active thumb-drag session. */
    fun onThumbDragMove(
        session: id.homebase.imageeditor.core.session.EditSession,
        screenPoint: id.homebase.imageeditor.core.PointF,
    ) {
        session.movePoint(0, screenPoint)
        snapshotMatrices()
    }

    /** Called when the user releases a thumb drag. */
    fun commitThumbGesture() {
        val cropEl = model.hierarchy.cropEditorElement
        if (cropEl.editorMatrix.isIdentity()) return
        cropEl.commitEditorMatrix()
        model.postEdit(allowScaleToRepairCrop = true)
        model.pushUndoPoint()
        snapshotMatrices()
        refreshUndoRedo()
    }

    companion object {
        private const val TAG = "CropEditorViewModel"
    }
}

/**
 * Snapshot of the matrices that drive painting. Captured once per gesture
 * frame so Compose can recompose with stable values.
 */
@androidx.compose.runtime.Immutable
data class MatrixSnapshot(
    val viewLocal: Matrix2D,
    val flipRotate: Matrix2D,
    val mainImageLocal: Matrix2D,
    val mainImageEditor: Matrix2D,
    val cropFrameMatrix: Matrix2D,
    val cropRect: RectF,
    val viewportSize: Size,
) {
    companion object {
        val EMPTY: MatrixSnapshot = MatrixSnapshot(
            viewLocal = Matrix2D(),
            flipRotate = Matrix2D(),
            mainImageLocal = Matrix2D(),
            mainImageEditor = Matrix2D(),
            cropFrameMatrix = Matrix2D(),
            cropRect = RectF(),
            viewportSize = Size(0, 0),
        )

        fun capture(model: EditorModel): MatrixSnapshot {
            val main = model.hierarchy.mainImage()
            return MatrixSnapshot(
                viewLocal = Matrix2D(model.hierarchy.view.localMatrix),
                flipRotate = Matrix2D(model.hierarchy.flipRotate.localMatrix),
                mainImageLocal = main?.let { Matrix2D(it.localMatrix) } ?: Matrix2D(),
                mainImageEditor = main?.let { Matrix2D(it.editorMatrix) } ?: Matrix2D(),
                cropFrameMatrix = model.getCropFinalMatrix(),
                cropRect = model.getCropRect(),
                viewportSize = model.naturalSize,
            )
        }
    }
}
