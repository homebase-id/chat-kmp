package id.homebase.imageeditor.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.toImageBitmap
import id.homebase.core.ui.navigation.Route
import id.homebase.imageeditor.core.Bounds
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.core.Size
import id.homebase.imageeditor.core.draw.BrushType
import id.homebase.imageeditor.core.draw.DrawingModel
import id.homebase.imageeditor.core.draw.Stroke
import id.homebase.imageeditor.core.io.CropPreprocessor
import id.homebase.imageeditor.core.io.DrawFinalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Backs [DrawScreen]. Owns a [DrawingModel] plus the matrices required to
 * project screen pixels onto bounds-space and back again.
 *
 * Coordinate chain (canvas pixel ← bounds):
 *   `viewportTransform · viewLocal`
 *
 * [bitmapProjection] additionally maps source pixels into bounds-space at
 * draw time. The pen pointer-handler asks for the inverse to translate
 * touches into bounds-space.
 *
 * No [id.homebase.imageeditor.core.EditorModel] here — draw doesn't
 * crop/rotate/flip; the underlying image is treated as already-flattened
 * bytes.
 */
class DrawEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val resultBus: DrawResultBus,
) : ViewModel() {

    // Arrives as a nav arg (main app, via Route.Draw) or a directly-seeded handle key
    // (the share editor mounts this screen without a NavHost). Raw key first, then route.
    val requestId: Uuid = (savedStateHandle.get<String>("requestId")
        ?: savedStateHandle.toRoute<Route.Draw>().requestId).let(Uuid::parse)

    /** Free the bus entry when the draw screen goes away — confirmed or aborted.
     *  postResult() closes the channel on the confirm path; this covers the back-out
     *  path so the caller's resultsFor(requestId) collector can't suspend forever. */
    override fun onCleared() {
        resultBus.cancel(requestId)
        super.onCleared()
    }

    private val _uiState = MutableStateFlow(DrawEditorUiState())
    val uiState: StateFlow<DrawEditorUiState> = _uiState.asStateFlow()

    var previewBitmap: ImageBitmap? by mutableStateOf(null)
        private set

    /**
     * Pre-blurred copy of [previewBitmap], populated asynchronously after
     * load. Used by [DrawStrokesOverlay] to render blur strokes — a stroke
     * with [BrushType.Blur] paints the corresponding region of this bitmap
     * through the stroke shape.
     */
    var blurredPreview: ImageBitmap? by mutableStateOf(null)
        private set

    /**
     * Snapshot of matrices + stroke list, bumped on every gesture frame.
     * Compose-tracked so the canvas recomposes when the user pans or extends
     * the in-flight stroke.
     */
    var snapshot: DrawSnapshot by mutableStateOf(DrawSnapshot.EMPTY)
        private set

    val drawingModel: DrawingModel = DrawingModel()

    private var originalBytes: ByteArray? = null
    private var natural: Size? = null
    private var visibleViewportPx: RectF = RectF()
    private val viewLocal: Matrix2D = Matrix2D()
    private val bitmapProjection: Matrix2D = Matrix2D()
    private val viewportTransform: Matrix2D = Matrix2D()

    private var loaded: Boolean = false

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val bytes = resultBus.takeSource(requestId)
            if (bytes == null) {
                Logger.w(tag = TAG) { "no source bytes for $requestId" }
                _uiState.update { it.copy(isLoading = false, errorMessageKey = "draw_error_load") }
                return@launch
            }
            originalBytes = bytes
            val prep = withContext(Dispatchers.Default) { CropPreprocessor.prepare(bytes) }
            if (prep == null) {
                _uiState.update { it.copy(isLoading = false, errorMessageKey = "draw_error_load") }
                return@launch
            }
            previewBitmap = prep.previewBytes.toImageBitmap()
            natural = prep.naturalSize

            // Kick off a blurred copy of the preview in the background; the
            // overlay falls back to a gray placeholder while it's loading.
            // Radius scales with image size so visual strength stays
            // comparable across phone- and tablet-sized previews — Signal
            // achieves the same effect by downscaling-then-blurring at a
            // constant radius.
            val blurRadius = previewBitmap?.let { bm ->
                val longest = maxOf(bm.width, bm.height)
                (longest / 30f).toInt().coerceIn(20, 80)
            } ?: 25
            launch {
                val blurredBytes = withContext(Dispatchers.Default) {
                    runCatching { ImageUtils.blurBytes(prep.previewBytes, radius = blurRadius) }.getOrNull()
                }
                blurredPreview = blurredBytes?.bytes?.toImageBitmap()
                updateSnapshot()
            }

            // Bitmap → bounds: setRectToRect(bitmapPixelRect, fullBounds, CENTER).
            previewBitmap?.let { bm ->
                bitmapProjection.setRectToRect(
                    src = RectF(0f, 0f, bm.width.toFloat(), bm.height.toFloat()),
                    dst = Bounds.fullBounds(),
                    scaleToFit = Matrix2D.ScaleToFit.CENTER,
                )
            }
            recomputeViewLocal()
            updateSnapshot()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    naturalSize = prep.naturalSize,
                    colorArgb = colorForPosition(it.colorPosition),
                )
            }
        }
    }

    fun setVisibleViewport(width: Float, height: Float) {
        visibleViewportPx = RectF(0f, 0f, width, height)
        recomputeViewLocal()
        updateSnapshot()
    }

    private fun recomputeViewLocal() {
        if (visibleViewportPx.isEmpty()) return
        // Bounds [-1000, 1000] → centered fit inside the visible viewport.
        viewLocal.setRectToRect(
            src = Bounds.fullBounds(),
            dst = visibleViewportPx,
            scaleToFit = Matrix2D.ScaleToFit.CENTER,
        )
    }

    private fun updateSnapshot() {
        // toList() to break the alias with DrawingModel's internal MutableList:
        // DrawSnapshot is @Immutable, so Compose uses data-class equality to
        // decide whether to skip recomposition. If we leak the live list,
        // every snapshot ends up referencing the same mutated collection;
        // post-undo equality returns true and the strokes overlay never
        // recomposes. Cloning here is O(n) in stroke count, which is cheap
        // even with hundreds of strokes — the per-frame work is dominated
        // by the canvas itself.
        snapshot = DrawSnapshot(
            viewLocal = Matrix2D(viewLocal),
            viewportTransform = Matrix2D(viewportTransform),
            bitmapProjection = Matrix2D(bitmapProjection),
            strokes = drawingModel.strokes.toList(),
            inFlightCommands = drawingModel.inFlightStroke?.previewPath() ?: emptyList(),
            inFlightBrush = drawingModel.inFlightStroke?.brush,
            inFlightColorArgb = drawingModel.inFlightStroke?.colorArgb ?: 0,
            inFlightThicknessBoundsUnits = drawingModel.inFlightStroke?.thicknessBoundsUnits ?: 0f,
            viewportSize = Size(visibleViewportPx.width().toInt(), visibleViewportPx.height().toInt()),
            bitmapPixelSize = previewBitmap?.let { Size(it.width, it.height) } ?: Size(0, 0),
            blurredImage = blurredPreview,
        )
    }

    // -------- UI actions --------

    fun onUiAction(action: DrawEditorUiAction) {
        when (action) {
            DrawEditorUiAction.BackClicked -> _uiState.update {
                it.copy(uiEvent = DrawEditorUiEvent.Cancelled)
            }
            DrawEditorUiAction.SaveClicked -> save()
            DrawEditorUiAction.UndoClicked -> {
                drawingModel.undo()
                updateSnapshot()
                refreshUndoRedo()
            }
            DrawEditorUiAction.RedoClicked -> {
                drawingModel.redo()
                updateSnapshot()
                refreshUndoRedo()
            }
            DrawEditorUiAction.ResetClicked -> {
                drawingModel.clearAll()
                viewportTransform.reset()
                updateSnapshot()
                refreshUndoRedo()
            }
            is DrawEditorUiAction.BrushSelected -> _uiState.update { it.copy(selectedBrush = action.brush) }
            is DrawEditorUiAction.ColorChanged -> {
                val argb = colorForPosition(action.position)
                _uiState.update { it.copy(colorPosition = action.position, colorArgb = argb) }
            }
            is DrawEditorUiAction.ThicknessSliderPositionChanged -> _uiState.update {
                val pos = action.position.coerceIn(0f, 1f)
                val fraction = BrushType.MIN_THICKNESS_FRACTION +
                    (BrushType.MAX_THICKNESS_FRACTION - BrushType.MIN_THICKNESS_FRACTION) * pos
                when (it.selectedBrush) {
                    BrushType.Pen -> it.copy(penThicknessFraction = fraction)
                    BrushType.Highlighter -> it.copy(highlighterThicknessFraction = fraction)
                    BrushType.Blur -> it.copy(blurThicknessFraction = fraction)
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun refreshUndoRedo() {
        _uiState.update { it.copy(canUndo = drawingModel.canUndo(), canRedo = drawingModel.canRedo()) }
    }

    // -------- Pointer input → bounds-space → DrawingModel --------

    /** Map a canvas-pixel point to bounds-space. Null if the chain is non-invertible. */
    fun screenToBounds(screenX: Float, screenY: Float): Pair<Float, Float>? {
        val chain = Matrix2D(viewportTransform).also { it.preConcat(viewLocal) }
        val inv = Matrix2D()
        if (!chain.invert(inv)) return null
        val out = inv.mapPoint(screenX, screenY)
        return out[0] to out[1]
    }

    fun onStrokeStart(screenX: Float, screenY: Float) {
        val (bx, by) = screenToBounds(screenX, screenY) ?: return
        val state = uiState.value
        val fraction = when (state.selectedBrush) {
            BrushType.Pen -> state.penThicknessFraction
            BrushType.Highlighter -> state.highlighterThicknessFraction
            BrushType.Blur -> state.blurThicknessFraction
        }
        drawingModel.beginStroke(
            brush = state.selectedBrush,
            colorArgb = state.colorArgb,
            thicknessBoundsUnits = BrushType.thicknessBoundsUnitsForFraction(fraction),
            x = bx,
            y = by,
        )
        updateSnapshot()
    }

    fun onStrokeExtend(screenX: Float, screenY: Float) {
        val (bx, by) = screenToBounds(screenX, screenY) ?: return
        drawingModel.extendStroke(bx, by)
        updateSnapshot()
    }

    fun onStrokeCommit() {
        drawingModel.commitStroke()
        updateSnapshot()
        refreshUndoRedo()
    }

    fun onStrokeCancel() {
        drawingModel.cancelStroke()
        updateSnapshot()
    }

    /** Pan in canvas pixels. */
    fun panViewport(dxPx: Float, dyPx: Float) {
        viewportTransform.postTranslate(dxPx, dyPx)
        updateSnapshot()
    }

    /** Pinch zoom around a screen-pixel centroid. */
    fun zoomViewport(scale: Float, centroidPx: Pair<Float, Float>) {
        viewportTransform.postScale(scale, scale, centroidPx.first, centroidPx.second)
        updateSnapshot()
    }

    // -------- Save --------

    private fun save() {
        val bytes = originalBytes ?: return
        val nat = natural ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    DrawFinalizer.finalize(
                        originalBytes = bytes,
                        naturalSize = nat,
                        model = drawingModel,
                        outputFormat = ImageFormat.JPEG,
                        quality = 90,
                    )
                }
                resultBus.postResult(requestId, result)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        uiEvent = DrawEditorUiEvent.DrawConfirmed(requestId, result),
                    )
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "finalize failed" }
                _uiState.update { it.copy(isSaving = false, errorMessageKey = "draw_error_save") }
            }
        }
    }

    // -------- Helpers --------

    private fun colorForPosition(position: Float): Int {
        // Mirrors HsvColorSlider's gradient. Position 0..1 across:
        //   black (0..0.05) → primary spectrum (0.05..0.95) → white (0.95..1.0).
        val p = position.coerceIn(0f, 1f)
        return when {
            p <= 0.05f -> argb(0xFF, lerp(0, 0, (p / 0.05f)), lerp(0, 0, (p / 0.05f)), lerp(0, 0, (p / 0.05f)))
            p >= 0.95f -> {
                val t = ((p - 0.95f) / 0.05f).coerceIn(0f, 1f)
                argb(0xFF, lerp(255, 255, t), lerp(255, 255, t), lerp(255, 255, t))
            }
            else -> {
                val hue = (p - 0.05f) / 0.9f * 360f
                hsvToArgb(hue, 1f, 1f)
            }
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t.coerceIn(0f, 1f)).toInt()
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val c = value * saturation
        val x = c * (1f - kotlin.math.abs(((h / 60f) % 2f) - 1f))
        val m = value - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val rr = ((r + m) * 255f).toInt().coerceIn(0, 255)
        val gg = ((g + m) * 255f).toInt().coerceIn(0, 255)
        val bb = ((b + m) * 255f).toInt().coerceIn(0, 255)
        return argb(0xFF, rr, gg, bb)
    }

    companion object {
        private const val TAG = "DrawEditorViewModel"
    }
}

/** Snapshot consumed by the painting composables. */
@Immutable
data class DrawSnapshot(
    val viewLocal: Matrix2D,
    val viewportTransform: Matrix2D,
    val bitmapProjection: Matrix2D,
    val strokes: List<Stroke>,
    val inFlightCommands: List<id.homebase.api.image.draw.PathCommand>,
    val inFlightBrush: BrushType?,
    val inFlightColorArgb: Int,
    val inFlightThicknessBoundsUnits: Float,
    val viewportSize: Size,
    /**
     * Width × height of [DrawEditorViewModel.previewBitmap] in pixels.
     * Used by the strokes overlay to clip drawing to the image rect (so
     * strokes don't paint into the black letterbox).
     */
    val bitmapPixelSize: Size,
    /**
     * Pre-blurred copy of the source preview. Populated asynchronously by
     * the ViewModel; null until ready. The strokes overlay falls back to a
     * gray placeholder for blur strokes while it's null.
     */
    val blurredImage: ImageBitmap?,
) {
    companion object {
        val EMPTY: DrawSnapshot = DrawSnapshot(
            viewLocal = Matrix2D(),
            viewportTransform = Matrix2D(),
            bitmapProjection = Matrix2D(),
            strokes = emptyList(),
            inFlightCommands = emptyList(),
            inFlightBrush = null,
            inFlightColorArgb = 0,
            inFlightThicknessBoundsUnits = 0f,
            viewportSize = Size(0, 0),
            bitmapPixelSize = Size(0, 0),
            blurredImage = null,
        )
    }
}
