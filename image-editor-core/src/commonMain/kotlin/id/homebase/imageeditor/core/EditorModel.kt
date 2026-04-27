package id.homebase.imageeditor.core

import id.homebase.imageeditor.core.session.EditSession
import id.homebase.imageeditor.core.session.ElementDragEditSession
import id.homebase.imageeditor.core.session.ThumbDragEditSession
import kotlin.math.max
import kotlin.math.min

/**
 * Top-level facade orchestrating the editor element tree, undo/redo, and
 * gesture entry points.
 *
 * Translated and trimmed from `EditorModel.java` in Signal-Android (AGPL-3.0).
 * The render/draw pipeline, rotation animations, drawing/sticker/text/face-blur
 * features were intentionally not ported — the cropper UI lives in
 * `image-editor-ui` and renders directly via Compose.
 */
class EditorModel internal constructor(
    val hierarchy: EditorElementHierarchy,
) {
    private val undoRedoStacks: UndoRedoStacks = UndoRedoStacks(50)
    private val cropUndoRedoStacks: UndoRedoStacks = UndoRedoStacks(50)
    private val inBoundsMemory: InBoundsMemory = InBoundsMemory()

    private var size: Size = Size(1024, 1024)
    private val visibleViewPort: RectF = RectF()

    /**
     * Shrink factor applied to the visible viewport when fitting the crop
     * frame. Signal uses 0.8 so the corner thumbs sit inside the screen
     * edges (away from system edge-swipe gesture zones on Android).
     */
    var viewportScaleIn: Float = 0.8f

    /** Optional per-mode constraint; null means free aspect. */
    private var fixedRatio: Float? = null

    /**
     * Call once after the image bytes have been decoded so we know the
     * natural size and can initialize the imageCrop matrix that maps
     * Bounds.FULL_BOUNDS onto the image's aspect.
     */
    fun onImageReady(naturalSize: Size) {
        require(naturalSize.width > 0 && naturalSize.height > 0)
        size = naturalSize

        // Add the main image element to the tree if it isn't already there.
        val mainImage = hierarchy.mainImage()
            ?: EditorElement().also { hierarchy.imageRoot.addElement(it) }

        // Signal-Android's load-bearing invariant: every matrix in the editor
        // tree (localMatrix / editorMatrix on every node) operates in
        // canonical bounds-space [-1000, 1000]^2. The natural-pixel-to-bounds
        // scaling is performed at draw time by the renderer's
        // imageProjectionMatrix, NOT by mainImage.localMatrix.
        // See `EditorModel.java:824-825` and `UriGlideRenderer.java:128, 246, 251-258`.
        mainImage.localMatrix.reset()
        mainImage.editorMatrix.reset()

        // imageCrop.localMatrix == Signal's cropMatrix(bitmap):
        //   preScale(1,        h/w) when w >= h  (landscape)
        //   preScale(w/h, 1)        when h >  w  (portrait)
        // Applied to FULL_BOUNDS, the result is the imageAspectRect. This is
        // an aspect-ratio matrix in bounds-space (does not encode any
        // natural-pixel info).
        hierarchy.imageCrop.localMatrix.reset()
        val w = naturalSize.width.toFloat()
        val h = naturalSize.height.toFloat()
        if (w >= h) {
            hierarchy.imageCrop.localMatrix.postScale(1f, h / w)
        } else {
            hierarchy.imageCrop.localMatrix.postScale(w / h, 1f)
        }

        // Initial user-crop covers the full imageCrop area.
        hierarchy.cropEditorElement.localMatrix.reset()

        applyFixedRatio()

        // Seed inBoundsMemory so postEdit's restore-on-bad-crop path falls
        // back to a correct initial state instead of unset matrices.
        inBoundsMemory.push(mainImage, hierarchy.cropEditorElement)

        if (!visibleViewPort.isEmpty()) {
            hierarchy.updateViewToCrop(visibleViewPort, viewportScaleIn)
        }

        undoRedoStacks.clear()
        cropUndoRedoStacks.clear()
    }

    /**
     * Matrix that maps natural-pixel coordinates of the source image to
     * view-space (canonical bounds, the same coordinate system as
     * [getCropRect]).
     *
     * The chain that ends up applied to natural pixels at draw time is
     * `flipRotate.local * mainImage.local * mainImage.editor * imageProjection`.
     * `mainImage.local` is identity (Signal's invariant), so the load-bearing
     * step is `imageProjection`, the natural-to-bounds setRectToRect that
     * lives in the renderer (here we recompute it from [size]).
     */
    fun naturalToViewMatrix(): Matrix2D {
        val m = Matrix2D(hierarchy.flipRotate.localMatrix)
        hierarchy.mainImage()?.let { main ->
            m.preConcat(main.localMatrix)
            m.preConcat(main.editorMatrix)
        }
        m.preConcat(imageProjectionMatrix(size))
        return m
    }

    /**
     * `setRectToRect(naturalRect, FULL_BOUNDS, CENTER)` — what
     * Signal's `UriGlideRenderer.imageProjectionMatrix` is. Maps natural
     * pixels to canonical bounds, preserving aspect.
     */
    fun imageProjectionMatrix(naturalSize: Size = size): Matrix2D = Matrix2D().apply {
        setRectToRect(
            src = RectF(0f, 0f, naturalSize.width.toFloat(), naturalSize.height.toFloat()),
            dst = Bounds.fullBounds(),
            scaleToFit = Matrix2D.ScaleToFit.CENTER,
        )
    }

    /** Natural-pixel size used by the model. */
    val naturalSize: Size get() = size

    /** Tell the model how big the visible viewport is in screen pixels. */
    fun setVisibleViewPort(rect: RectF) {
        visibleViewPort.set(rect)
        hierarchy.updateViewToCrop(visibleViewPort, viewportScaleIn)
    }

    fun getCropRect(): RectF = hierarchy.getCropRect()

    fun getCropFinalMatrix(): Matrix2D = hierarchy.getCropFinalMatrix()

    /** Output size in natural pixels of the current crop. */
    fun getOutputSize(): Size {
        val s = hierarchy.getOutputSize(size)
        val w = max(1, s.width.toInt())
        val h = max(1, s.height.toInt())
        return Size(w, h)
    }

    // -------- Aspect lock --------

    fun setCropAspectLock(locked: Boolean) {
        hierarchy.cropEditorElement.flags.aspectLocked = locked
    }

    fun setFixedRatio(ratio: Float?) {
        fixedRatio = ratio
        applyFixedRatio()
    }

    private fun applyFixedRatio() {
        val r = fixedRatio
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val m = hierarchy.cropEditorElement.localMatrix
        if (r == null) {
            // free — leave whatever the user has, or reset to full image
            // If the existing matrix is identity, leave it; otherwise leave it.
            return
        }
        val imageRatio = w / h
        m.reset()
        if (imageRatio > r) {
            m.postScale(r / imageRatio, 1f)
        } else {
            m.postScale(1f, imageRatio / r)
        }
    }

    // -------- 90° snap rotate / flip --------

    fun rotate90Clockwise() {
        flipRotate(degrees = 90f, scaleX = 1, scaleY = 1)
    }

    fun rotate90Anticlockwise() {
        flipRotate(degrees = -90f, scaleX = 1, scaleY = 1)
    }

    fun flipHorizontal() {
        flipRotate(degrees = 0f, scaleX = -1, scaleY = 1)
    }

    fun flipVertical() {
        flipRotate(degrees = 0f, scaleX = 1, scaleY = -1)
    }

    private fun flipRotate(degrees: Float, scaleX: Int, scaleY: Int) {
        pushUndoPoint()
        hierarchy.flipRotate(degrees, scaleX, scaleY, visibleViewPort, viewportScaleIn)
    }

    // -------- Free rotation around parent's origin --------

    /**
     * Rotates the main image about its parent's origin (as opposed to the
     * image's own origin), then auto-shrinks if the rotation would expose
     * empty space inside the crop. This is what powers the rotation dial.
     */
    fun setMainImageEditorMatrixRotation(angleDegrees: Float, minScaleDown: Float = 0.5f) {
        val main = hierarchy.mainImage() ?: return
        setEditorMatrixToRotationAboutParentOrigin(main, angleDegrees)
        scaleMainImageEditorMatrixToFitInsideCropBounds(main, minScaleDown, 2f)
    }

    private fun setEditorMatrixToRotationAboutParentOrigin(element: EditorElement, degrees: Float) {
        val local = element.localMatrix
        val editor = element.editorMatrix
        if (!local.invert(editor)) {
            editor.reset()
            return
        }
        editor.preRotate(degrees)
        editor.preConcat(local)
    }

    private fun scaleMainImageEditorMatrixToFitInsideCropBounds(
        mainImage: EditorElement,
        minScaleDown: Float,
        maxScaleUp: Float,
    ) {
        val localBackup = Matrix2D(mainImage.localMatrix)
        val editorBackup = Matrix2D(mainImage.editorMatrix)

        mainImage.commitEditorMatrix()
        val combinedLocal = Matrix2D(mainImage.localMatrix)
        val newLocal = Bisect.bisectToTest(
            mainImage,
            minScaleDown,
            maxScaleUp,
            { cropIsWithinMainImageBounds() },
            { matrix, scale -> matrix.preScale(scale, scale) },
        )

        if (newLocal != null) {
            val invertLocal = Matrix2D()
            if (combinedLocal.invert(invertLocal)) {
                invertLocal.preConcat(newLocal)
                editorBackup.preConcat(invertLocal)
            }
        }
        mainImage.localMatrix.set(localBackup)
        mainImage.editorMatrix.set(editorBackup)
    }

    /** True iff the current crop rect is fully contained in the main image. */
    fun cropIsWithinMainImageBounds(): Boolean =
        Bounds.boundsRemainInBounds(hierarchy.imageMatrixRelativeToCrop())

    // -------- Undo/redo --------

    fun pushUndoPoint() {
        undoRedoStacks.pushState(hierarchy.root)
    }

    fun canUndo(): Boolean = undoRedoStacks.canUndo()
    fun canRedo(): Boolean = undoRedoStacks.canRedo()

    fun undo() {
        if (!undoRedoStacks.canUndo()) return
        val current = TreeSnapshot.capture(hierarchy.root)
        val popped = undoRedoStacks.undo.pop() ?: return
        undoRedoStacks.redo.tryPush(current, hierarchy.root)
        popped.applyTo(hierarchy.root)
        hierarchy.updateViewToCrop(visibleViewPort, viewportScaleIn)
        inBoundsMemory.push(hierarchy.mainImage(), hierarchy.cropEditorElement)
    }

    fun redo() {
        if (!undoRedoStacks.canRedo()) return
        val current = TreeSnapshot.capture(hierarchy.root)
        val popped = undoRedoStacks.redo.pop() ?: return
        undoRedoStacks.undo.tryPush(current, hierarchy.root)
        popped.applyTo(hierarchy.root)
        hierarchy.updateViewToCrop(visibleViewPort, viewportScaleIn)
        inBoundsMemory.push(hierarchy.mainImage(), hierarchy.cropEditorElement)
    }

    // -------- Post-edit reflow --------

    /**
     * Call after a gesture commits. Restores the last in-bounds state if the
     * gesture left things in an unacceptable position; otherwise records the
     * new in-bounds snapshot.
     */
    fun postEdit(allowScaleToRepairCrop: Boolean) {
        val mainImage = hierarchy.mainImage() ?: return
        val cropEl = hierarchy.cropEditorElement

        if (!currentCropIsAcceptable()) {
            if (allowScaleToRepairCrop) {
                if (!tryToScaleToFit(cropEl, 0.9f)) {
                    tryToScaleToFit(mainImage, 2f)
                }
            }
            if (!currentCropIsAcceptable()) {
                inBoundsMemory.restore(mainImage, cropEl)
            } else {
                inBoundsMemory.push(mainImage, cropEl)
            }
        } else {
            inBoundsMemory.push(mainImage, cropEl)
        }

        hierarchy.updateViewToCrop(visibleViewPort, viewportScaleIn)
    }

    private fun tryToScaleToFit(element: EditorElement, atMost: Float): Boolean {
        val newLocal = Bisect.bisectToTest(
            element,
            outOfBoundsValue = 1f,
            atMost = atMost,
            predicate = { cropIsWithinMainImageBounds() },
            modifyElement = { matrix, scale -> matrix.preScale(scale, scale) },
        )
        if (newLocal != null) {
            element.localMatrix.set(newLocal)
            return true
        }
        return false
    }

    private fun currentCropIsAcceptable(): Boolean {
        val outputSize = hierarchy.getOutputSize(size)
        val outputPixelCount = outputSize.width.toLong() * outputSize.height.toLong()
        val minimumPixelCount = min(size.pixelCount, MINIMUM_CROP_PIXEL_COUNT.toLong())
        if (outputPixelCount < minimumPixelCount) return false

        val w = max(outputSize.width, outputSize.height)
        val h = min(outputSize.width, outputSize.height)
        val sourceMaxRatio = MINIMUM_RATIO_LONG.toFloat() / MINIMUM_RATIO_SHORT.toFloat()
        val sourceShort = min(size.width, size.height).toFloat()
        val sourceLong = max(size.width, size.height).toFloat()
        val effectiveMax = min(sourceMaxRatio, sourceLong / sourceShort)
        if (h > 0f && w / h > effectiveMax) return false

        return cropIsWithinMainImageBounds()
    }

    // -------- Gesture entry points --------

    /**
     * Begin a single-pointer drag on the main image (pan).
     *
     * @param screenToImageRoot inverse of the screen→imageRoot-space matrix.
     */
    fun startMainImageDrag(screenToImageRoot: Matrix2D, screenPoint: PointF): EditSession? {
        val main = hierarchy.mainImage() ?: return null
        return ElementDragEditSession.startDrag(main, screenToImageRoot, screenPoint)
    }

    /**
     * Begin a thumb drag on the user crop rect.
     *
     * @param screenToCrop inverse of the screen→cropEditorElement-space matrix
     *                     (i.e. screen→imageCrop-space, since the thumb lives
     *                     inside cropEditorElement).
     * @param thumbContainerRelativeMatrix the matrix that maps a thumb's
     *                                     control-point coordinates onto the
     *                                     selected element's coordinates.
     */
    fun startCropThumbDrag(
        screenToCrop: Matrix2D,
        thumbContainerRelativeMatrix: Matrix2D,
        controlPoint: ControlPoint,
        screenPoint: PointF,
    ): EditSession? {
        val cropEl = hierarchy.cropEditorElement
        return ThumbDragEditSession.startDrag(
            cropEl,
            screenToCrop,
            thumbContainerRelativeMatrix,
            controlPoint,
            screenPoint,
        )
    }

    /**
     * Reset the model. Discards the image, undo/redo, free rotation, etc.
     */
    fun reset() {
        hierarchy.flipRotate.localMatrix.reset()
        hierarchy.cropEditorElement.localMatrix.reset()
        hierarchy.cropEditorElement.editorMatrix.reset()
        hierarchy.mainImage()?.let {
            it.localMatrix.reset()
            it.editorMatrix.reset()
        }
        applyFixedRatio()
        undoRedoStacks.clear()
        cropUndoRedoStacks.clear()
        if (!visibleViewPort.isEmpty()) {
            hierarchy.updateViewToCrop(visibleViewPort, viewportScaleIn)
        }
    }

    companion object {
        private const val MINIMUM_CROP_PIXEL_COUNT: Int = 100
        private const val MINIMUM_RATIO_LONG: Int = 15
        private const val MINIMUM_RATIO_SHORT: Int = 1

        fun create(): EditorModel = EditorModel(EditorElementHierarchy.create())
    }
}
