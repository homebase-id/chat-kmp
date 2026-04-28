package id.homebase.imageeditor.core.draw

import id.homebase.api.image.draw.StrokeCap

/**
 * Family of stroke brush. Mirrors Signal's brush categories from
 * `BezierDrawingRenderer.java` minus blur (deferred). Pen and Highlighter
 * differ only in cap shape and alpha — they share a single thickness range
 * exposed through the unified width slider.
 *
 * @property cap stroke end cap style.
 * @property alpha 0..255 multiplier applied to the user-picked color.
 * @property compositesUnderlying when true, the stroke does not paint a flat
 *   color but instead reveals a transformed version of the underlying image
 *   (e.g. a gaussian-blurred copy). Reserved for the future Blur brush.
 */
sealed class BrushType(
    val cap: StrokeCap,
    val alpha: Int,
    val compositesUnderlying: Boolean,
) {
    /** Round, opaque pen. */
    object Pen : BrushType(cap = StrokeCap.Round, alpha = 0xFF, compositesUnderlying = false)

    /** Square, semi-transparent highlighter (alpha 0x60). */
    object Highlighter : BrushType(cap = StrokeCap.Square, alpha = 0x60, compositesUnderlying = false)

    companion object {
        /** [id.homebase.imageeditor.core.Bounds.RIGHT] - [id.homebase.imageeditor.core.Bounds.LEFT]. */
        const val BOUNDS_WIDTH: Float = 2000f

        /**
         * Slider range, expressed as a fraction of bounds width. Both
         * brushes share this — the user mental model is "the slider sets
         * stroke thickness; the brush selector picks the shape." Defaults
         * are tuned for Desktop where 1 bounds-unit ≈ 0.45 px:
         *   0.001 → ~1 px hairline
         *   0.04  → ~36 px fat marker
         */
        const val MIN_THICKNESS_FRACTION: Float = 0.001f
        const val MAX_THICKNESS_FRACTION: Float = 0.04f

        /**
         * Convert a bounds-fraction to bounds-space stroke width units.
         * Caller should clamp to `[MIN_THICKNESS_FRACTION,
         * MAX_THICKNESS_FRACTION]` first.
         */
        fun thicknessBoundsUnitsForFraction(fraction: Float): Float = fraction * BOUNDS_WIDTH
    }
}
