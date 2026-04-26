package id.homebase.imageeditor.core

/**
 * Canonical [-1000, 1000] coordinate space used by the editor.
 *
 * Translated from `Bounds.java` in Signal-Android (AGPL-3.0).
 */
object Bounds {
    const val LEFT: Float = -1000f
    const val RIGHT: Float = 1000f
    const val TOP: Float = -1000f
    const val BOTTOM: Float = 1000f

    const val CENTRE_X: Float = (LEFT + RIGHT) / 2f
    const val CENTRE_Y: Float = (TOP + BOTTOM) / 2f

    private val POINTS = floatArrayOf(
        LEFT, TOP,
        RIGHT, TOP,
        RIGHT, BOTTOM,
        LEFT, BOTTOM,
    )

    fun fullBounds(): RectF = RectF(LEFT, TOP, RIGHT, BOTTOM)

    fun contains(x: Float, y: Float): Boolean =
        x >= LEFT && x <= RIGHT && y >= TOP && y <= BOTTOM

    /**
     * Maps the four corners of the canonical bounds rectangle through `matrix`
     * and returns true iff every mapped corner lies inside the bounds rectangle.
     *
     * If `matrix` is null it is treated as identity, and the result is always true.
     */
    fun boundsRemainInBounds(matrix: Matrix2D?): Boolean {
        if (matrix == null) return true
        val out = FloatArray(POINTS.size)
        matrix.mapPoints(out, POINTS, count = POINTS.size / 2)
        var i = 0
        while (i < out.size) {
            if (!contains(out[i], out[i + 1])) return false
            i += 2
        }
        return true
    }
}
