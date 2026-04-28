package id.homebase.imageeditor.ui.widget

import androidx.compose.ui.graphics.Matrix
import id.homebase.imageeditor.core.Matrix2D

/**
 * Convert a [Matrix2D] (3x3 row-major affine) to Compose's [Matrix] (4x4
 * column-major). The Z axis is left unchanged so 2D affine transforms compose
 * cleanly with Compose's draw pipeline.
 */
internal fun Matrix2D.toComposeMatrix(): Matrix {
    val out = Matrix()
    out.reset()
    val v = out.values
    val a = values[Matrix2D.MSCALE_X]
    val b = values[Matrix2D.MSKEW_X]
    val c = values[Matrix2D.MTRANS_X]
    val d = values[Matrix2D.MSKEW_Y]
    val e = values[Matrix2D.MSCALE_Y]
    val f = values[Matrix2D.MTRANS_Y]
    // Compose Matrix is column-major.  values[col*4 + row].
    v[0] = a; v[1] = d
    v[4] = b; v[5] = e
    v[12] = c; v[13] = f
    return out
}
