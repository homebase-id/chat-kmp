package id.homebase.imageeditor.core

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Matrix introspection helpers translated from `MatrixUtils.java` in
 * Signal-Android (AGPL-3.0).
 */
object MatrixUtils {

    /** Rotation angle (radians) implied by an affine matrix. */
    fun getRotationAngle(matrix: Matrix2D): Float {
        val v = matrix.values
        return -atan2(v[Matrix2D.MSKEW_X], v[Matrix2D.MSCALE_X])
    }

    /** Magnitude of the X axis after applying the matrix. */
    fun getScaleX(matrix: Matrix2D): Float {
        val v = matrix.values
        val sx = v[Matrix2D.MSCALE_X]
        val skewX = v[Matrix2D.MSKEW_X]
        return sqrt(sx * sx + skewX * skewX)
    }
}
