package id.homebase.imageeditor.core

import kotlin.math.abs

/**
 * Binary-search the largest factor `f` between [outOfBoundsValue] and [atMost]
 * for which [predicate] holds when [modifyElement] is applied to the element's
 * local matrix.
 *
 * Used to:
 *   - shrink an out-of-bounds crop rect until it fits inside the image,
 *   - or scale up the image until it covers the crop rect,
 *   - or auto-shrink the image to stay inside the crop frame after free rotation.
 *
 * Translated from `Bisect.java` in Signal-Android (AGPL-3.0).
 */
internal object Bisect {
    const val ACCURACY: Float = 0.001f
    private const val MAX_ITERATIONS: Int = 16

    fun interface Predicate {
        fun test(): Boolean
    }

    fun interface ModifyElement {
        fun applyFactor(matrix: Matrix2D, factor: Float)
    }

    /**
     * Returns a new local matrix to apply, or null if no in-bounds factor was
     * found. Does NOT mutate the element — the caller decides whether to copy
     * the result onto [element].
     */
    fun bisectToTest(
        element: EditorElement,
        outOfBoundsValue: Float,
        atMost: Float,
        predicate: Predicate,
        modifyElement: ModifyElement,
    ): Matrix2D? {
        val elementMatrix = element.localMatrix
        val original = Matrix2D(elementMatrix)
        val closest = Matrix2D()
        var have = false
        var attempt = 0
        var successValue = 0f
        var oob = outOfBoundsValue
        var inBoundsValue = atMost
        var next = inBoundsValue

        do {
            attempt++
            modifyElement.applyFactor(elementMatrix, next)
            try {
                if (predicate.test()) {
                    inBoundsValue = next
                    if (!have || abs(next - oob) < abs(successValue - oob)) {
                        have = true
                        successValue = next
                        closest.set(elementMatrix)
                    }
                } else {
                    if (attempt == 1) return null
                    oob = next
                }
            } finally {
                elementMatrix.set(original)
            }
            next = (inBoundsValue + oob) / 2f
        } while (attempt < MAX_ITERATIONS && abs(inBoundsValue - oob) > ACCURACY)

        return if (have) closest else null
    }
}
