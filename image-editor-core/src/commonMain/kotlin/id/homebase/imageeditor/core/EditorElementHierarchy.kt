package id.homebase.imageeditor.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Strict tree the editor operates on:
 *
 * ```
 * root              — temporary editor zooms (start/finish crop animations)
 * └─ view           — persisted view-during-crop adjustments
 *    └─ flipRotate  — persisted snap-rotate (90/180/270) and flip
 *       ├─ imageRoot
 *       │  └─ mainImage    — the actual image; localMatrix sets initial fit, editorMatrix tracks pinch/pan/free-rotate
 *       └─ overlay         — always square
 *          └─ imageCrop    — crop frame matched to image aspect
 *             └─ cropEditorElement — user crop rect; thumb drags write to editorMatrix
 * ```
 *
 * Translated from `EditorElementHierarchy.java` in Signal-Android (AGPL-3.0).
 * The renderer subsystem (CropAreaRenderer, FillRenderer, OvalGuideRenderer,
 * trash, fade, thumb children) is intentionally not ported — Compose draws
 * the overlay in `image-editor-ui`.
 */
class EditorElementHierarchy private constructor(val root: EditorElement) {

    val view: EditorElement = root.getChild(0)
    val flipRotate: EditorElement = view.getChild(0)
    val imageRoot: EditorElement = flipRotate.getChild(0)
    val overlay: EditorElement = flipRotate.getChild(1)
    val imageCrop: EditorElement = overlay.getChild(0)
    val cropEditorElement: EditorElement = imageCrop.getChild(0)

    /** First (and only) child of [imageRoot], if any. */
    fun mainImage(): EditorElement? =
        if (imageRoot.childCount > 0) imageRoot.getChild(0) else null

    /**
     * The matrix that maps the canonical crop bounds [Bounds.FULL_BOUNDS] onto
     * the visible crop rect (in `view`-space).
     *
     * Equals `flipRotate.local * imageCrop.local * cropEditorElement.local`.
     */
    fun getCropFinalMatrix(): Matrix2D {
        val m = Matrix2D(flipRotate.localMatrix)
        m.preConcat(imageCrop.localMatrix)
        m.preConcat(cropEditorElement.localMatrix)
        return m
    }

    /** Axis-aligned bounding rect of the current crop in `view`-space. */
    fun getCropRect(): RectF {
        val dst = RectF()
        getCropFinalMatrix().mapRect(dst, Bounds.fullBounds())
        return dst
    }

    /**
     * Matrix mapping a point in the crop-rect coord space to the visible image.
     *
     * Used to decide whether the crop is fully contained in the image: a corner
     * of [Bounds.FULL_BOUNDS] mapped through this matrix lands inside the image
     * iff it remains within [Bounds.FULL_BOUNDS].
     */
    fun imageMatrixRelativeToCrop(): Matrix2D? {
        val main = mainImage() ?: return null

        val cropChain = Matrix2D(imageCrop.localMatrix)
        cropChain.preConcat(cropEditorElement.localMatrix)
        cropChain.preConcat(cropEditorElement.editorMatrix)

        val imageChain = Matrix2D(main.localMatrix)
        imageChain.preConcat(main.editorMatrix)
        imageChain.preConcat(imageCrop.localMatrix)

        val inverse = Matrix2D()
        if (!imageChain.invert(inverse)) return null
        inverse.preConcat(cropChain)
        return inverse
    }

    /**
     * Re-frames `view` so the current crop fits centered inside `visibleViewPort`.
     *
     * @param scaleIn shrink factor in [0, 1] applied to the viewport before
     *   fitting. Signal uses 0.8 so the image (and its corner thumbs) sit
     *   inside the screen edges, away from system swipe-gesture zones.
     */
    fun updateViewToCrop(visibleViewPort: RectF, scaleIn: Float = 1f) {
        val dst = RectF()
        getCropFinalMatrix().mapRect(dst, Bounds.fullBounds())
        val target = if (scaleIn >= 1f) {
            visibleViewPort
        } else {
            val cx = visibleViewPort.centerX()
            val cy = visibleViewPort.centerY()
            val halfW = visibleViewPort.width() * 0.5f * scaleIn
            val halfH = visibleViewPort.height() * 0.5f * scaleIn
            RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        }
        val temp = Matrix2D()
        temp.setRectToRect(dst, target, Matrix2D.ScaleToFit.CENTER)
        view.localMatrix.set(temp)
    }

    /** Apply a snap rotation (90/180/270) and/or flip to `flipRotate`. */
    fun flipRotate(degrees: Float, scaleX: Int, scaleY: Int, visibleViewPort: RectF, scaleIn: Float = 1f) {
        val newLocal = Matrix2D(flipRotate.localMatrix)
        if (degrees != 0f) newLocal.postRotate(degrees)
        newLocal.postScale(scaleX.toFloat(), scaleY.toFloat())
        flipRotate.localMatrix.set(newLocal)
        updateViewToCrop(visibleViewPort, scaleIn)
    }

    /**
     * Aggregate matrix from `flipRotate` down to `mainImage`.
     */
    fun getMainImageFullMatrixFromFlipRotate(): Matrix2D {
        val m = Matrix2D()
        m.preConcat(flipRotate.localMatrix)
        m.preConcat(imageRoot.localMatrix)
        mainImage()?.let { m.preConcat(it.localMatrix) }
        return m
    }

    /**
     * Output size in natural-pixel space, derived from the current crop /
     * rotate / scale state.
     *
     * The view-space crop rect's view-space dimensions are converted to
     * natural-pixel dimensions by dividing by the natural→view scale that
     * lives in `mainImage.localMatrix * mainImage.editorMatrix`.
     */
    fun getOutputSize(): SizeF {
        val cropRect = getCropRect()
        val main = mainImage() ?: return SizeF(cropRect.width(), cropRect.height())
        val xs = xScale(main.localMatrix) * xScale(main.editorMatrix)
        val viewToNatural = if (xs == 0f) 1f else 1f / xs
        return SizeF(abs(cropRect.width() * viewToNatural), abs(cropRect.height() * viewToNatural))
    }

    companion object {
        fun create(): EditorElementHierarchy {
            val root = createTree()
            return EditorElementHierarchy(root)
        }

        private fun createTree(): EditorElement {
            val root = EditorElement()

            val view = EditorElement()
            root.addElement(view)

            val flipRotate = EditorElement()
            view.addElement(flipRotate)

            val imageRoot = EditorElement()
            flipRotate.addElement(imageRoot)

            val overlay = EditorElement()
            flipRotate.addElement(overlay)

            val imageCrop = EditorElement()
            overlay.addElement(imageCrop)

            val cropEditorElement = EditorElement().also {
                it.flags.rotateLocked = true
                it.flags.aspectLocked = true
                it.flags.selectable = false
                it.flags.visible = false
            }
            imageCrop.addElement(cropEditorElement)

            return root
        }

        /** Magnitude of the X axis under [matrix]. */
        fun xScale(matrix: Matrix2D): Float {
            val v = matrix.values
            return sqrt(v[Matrix2D.MSCALE_X] * v[Matrix2D.MSCALE_X] + v[Matrix2D.MSKEW_Y] * v[Matrix2D.MSKEW_Y])
        }
    }
}

data class Size(val width: Int, val height: Int) {
    val pixelCount: Long get() = width.toLong() * height.toLong()
}

data class SizeF(val width: Float, val height: Float)
