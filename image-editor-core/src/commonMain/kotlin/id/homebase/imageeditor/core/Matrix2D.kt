package id.homebase.imageeditor.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A 3x3 affine transformation matrix used by the image editor.
 *
 * Mirrors the subset of `android.graphics.Matrix` used by Signal-Android's
 * editor (https://github.com/signalapp/Signal-Android — AGPL-3.0). Storage
 * is row-major in a 9-element float array, where indices match Android:
 *
 *   [ MSCALE_X  MSKEW_X   MTRANS_X ]
 *   [ MSKEW_Y   MSCALE_Y  MTRANS_Y ]
 *   [ MPERSP_0  MPERSP_1  MPERSP_2 ]
 *
 * For pure affine operations the bottom row stays [0, 0, 1].
 */
class Matrix2D() {

    val values: FloatArray = FloatArray(9)

    init {
        reset()
    }

    constructor(other: Matrix2D) : this() {
        set(other)
    }

    fun reset() {
        values[MSCALE_X] = 1f; values[MSKEW_X] = 0f;  values[MTRANS_X] = 0f
        values[MSKEW_Y] = 0f;  values[MSCALE_Y] = 1f; values[MTRANS_Y] = 0f
        values[MPERSP_0] = 0f; values[MPERSP_1] = 0f; values[MPERSP_2] = 1f
    }

    fun set(other: Matrix2D) {
        other.values.copyInto(values)
    }

    fun setValues(src: FloatArray) {
        require(src.size >= 9)
        src.copyInto(values, endIndex = 9)
    }

    fun getValues(dst: FloatArray) {
        require(dst.size >= 9)
        values.copyInto(dst, endIndex = 9)
    }

    fun isIdentity(): Boolean {
        return values[MSCALE_X] == 1f && values[MSKEW_X] == 0f && values[MTRANS_X] == 0f &&
            values[MSKEW_Y] == 0f && values[MSCALE_Y] == 1f && values[MTRANS_Y] == 0f &&
            values[MPERSP_0] == 0f && values[MPERSP_1] == 0f && values[MPERSP_2] == 1f
    }

    /** Result <- this * other (applied AFTER existing transforms). */
    fun postConcat(other: Matrix2D) {
        val r = multiply(other.values, this.values)
        r.copyInto(values)
    }

    /** Result <- other * this (applied BEFORE existing transforms). */
    fun preConcat(other: Matrix2D) {
        val r = multiply(this.values, other.values)
        r.copyInto(values)
    }

    fun preTranslate(dx: Float, dy: Float) {
        val t = scratch9.also { translate(it, dx, dy) }
        val r = multiply(this.values, t)
        r.copyInto(values)
    }

    fun postTranslate(dx: Float, dy: Float) {
        val t = scratch9.also { translate(it, dx, dy) }
        val r = multiply(t, this.values)
        r.copyInto(values)
    }

    fun preScale(sx: Float, sy: Float) {
        val s = scratch9.also { scale(it, sx, sy) }
        val r = multiply(this.values, s)
        r.copyInto(values)
    }

    fun postScale(sx: Float, sy: Float) {
        val s = scratch9.also { scale(it, sx, sy) }
        val r = multiply(s, this.values)
        r.copyInto(values)
    }

    fun postScale(sx: Float, sy: Float, px: Float, py: Float) {
        postTranslate(-px, -py)
        postScale(sx, sy)
        postTranslate(px, py)
    }

    fun preRotate(degrees: Float) {
        val r = scratch9.also { rotate(it, degrees) }
        val out = multiply(this.values, r)
        out.copyInto(values)
    }

    fun postRotate(degrees: Float) {
        val r = scratch9.also { rotate(it, degrees) }
        val out = multiply(r, this.values)
        out.copyInto(values)
    }

    /** Sets `dst` to `inverse(this)` and returns true on success. */
    fun invert(dst: Matrix2D): Boolean {
        val a = values[MSCALE_X]; val b = values[MSKEW_X];  val c = values[MTRANS_X]
        val d = values[MSKEW_Y];  val e = values[MSCALE_Y]; val f = values[MTRANS_Y]
        // For affine (last row [0,0,1]) det is just (a*e - b*d).
        val det = a * e - b * d
        if (det == 0f || !det.isFinite()) return false
        val invDet = 1f / det
        val out = dst.values
        out[MSCALE_X] = e * invDet
        out[MSKEW_X] = -b * invDet
        out[MTRANS_X] = (b * f - c * e) * invDet
        out[MSKEW_Y] = -d * invDet
        out[MSCALE_Y] = a * invDet
        out[MTRANS_Y] = (c * d - a * f) * invDet
        out[MPERSP_0] = 0f; out[MPERSP_1] = 0f; out[MPERSP_2] = 1f
        return true
    }

    /**
     * Maps `srcPoints` (interleaved [x0,y0,x1,y1,...]) into `dstPoints`.
     */
    fun mapPoints(dst: FloatArray, src: FloatArray, count: Int = src.size / 2) {
        val a = values[MSCALE_X]; val b = values[MSKEW_X];  val c = values[MTRANS_X]
        val d = values[MSKEW_Y];  val e = values[MSCALE_Y]; val f = values[MTRANS_Y]
        for (i in 0 until count) {
            val x = src[i * 2]
            val y = src[i * 2 + 1]
            dst[i * 2] = a * x + b * y + c
            dst[i * 2 + 1] = d * x + e * y + f
        }
    }

    /** Returns the mapped (x,y) as a length-2 array. Convenience for single-point maps. */
    fun mapPoint(x: Float, y: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val a = values[MSCALE_X]; val b = values[MSKEW_X];  val c = values[MTRANS_X]
        val d = values[MSKEW_Y];  val e = values[MSCALE_Y]; val f = values[MTRANS_Y]
        out[0] = a * x + b * y + c
        out[1] = d * x + e * y + f
        return out
    }

    /**
     * Maps the corners of `src` and stores the axis-aligned bounding box in `dst`.
     */
    fun mapRect(dst: RectF, src: RectF) {
        val pts = floatArrayOf(
            src.left, src.top,
            src.right, src.top,
            src.right, src.bottom,
            src.left, src.bottom,
        )
        val out = FloatArray(8)
        mapPoints(out, pts, 4)
        var minX = out[0]; var maxX = out[0]
        var minY = out[1]; var maxY = out[1]
        for (i in 1 until 4) {
            val x = out[i * 2]; val y = out[i * 2 + 1]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
        }
        dst.set(minX, minY, maxX, maxY)
    }

    /**
     * Sets this matrix to the transform that maps `src` to `dst`.
     *
     * Mirrors `android.graphics.Matrix.setRectToRect(src, dst, scaleToFit)`.
     */
    fun setRectToRect(src: RectF, dst: RectF, scaleToFit: ScaleToFit): Boolean {
        if (src.isEmpty()) {
            reset()
            return false
        }
        val srcW = src.width(); val srcH = src.height()
        val dstW = dst.width(); val dstH = dst.height()

        val sx = dstW / srcW
        val sy = dstH / srcH

        val tx: Float; val ty: Float
        val finalSx: Float; val finalSy: Float
        if (scaleToFit == ScaleToFit.FILL) {
            finalSx = sx; finalSy = sy
            tx = dst.left - src.left * finalSx
            ty = dst.top - src.top * finalSy
        } else {
            val s = min(sx, sy)
            finalSx = s; finalSy = s
            val diffX = dstW - srcW * s
            val diffY = dstH - srcH * s
            val (offX, offY) = when (scaleToFit) {
                ScaleToFit.START -> 0f to 0f
                ScaleToFit.CENTER -> diffX / 2f to diffY / 2f
                ScaleToFit.END -> diffX to diffY
                ScaleToFit.FILL -> 0f to 0f // unreachable, satisfies exhaustiveness
            }
            tx = dst.left - src.left * s + offX
            ty = dst.top - src.top * s + offY
        }
        values[MSCALE_X] = finalSx; values[MSKEW_X] = 0f; values[MTRANS_X] = tx
        values[MSKEW_Y] = 0f; values[MSCALE_Y] = finalSy; values[MTRANS_Y] = ty
        values[MPERSP_0] = 0f; values[MPERSP_1] = 0f; values[MPERSP_2] = 1f
        return true
    }

    enum class ScaleToFit { FILL, START, CENTER, END }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Matrix2D) return false
        return values.contentEquals(other.values)
    }

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String =
        "Matrix2D[${values[0]}, ${values[1]}, ${values[2]} | ${values[3]}, ${values[4]}, ${values[5]}]"

    private val scratch9: FloatArray
        get() = FloatArray(9)

    companion object {
        const val MSCALE_X = 0
        const val MSKEW_X = 1
        const val MTRANS_X = 2
        const val MSKEW_Y = 3
        const val MSCALE_Y = 4
        const val MTRANS_Y = 5
        const val MPERSP_0 = 6
        const val MPERSP_1 = 7
        const val MPERSP_2 = 8

        private fun translate(out: FloatArray, dx: Float, dy: Float) {
            out[MSCALE_X] = 1f; out[MSKEW_X] = 0f;  out[MTRANS_X] = dx
            out[MSKEW_Y] = 0f;  out[MSCALE_Y] = 1f; out[MTRANS_Y] = dy
            out[MPERSP_0] = 0f; out[MPERSP_1] = 0f; out[MPERSP_2] = 1f
        }

        private fun scale(out: FloatArray, sx: Float, sy: Float) {
            out[MSCALE_X] = sx; out[MSKEW_X] = 0f; out[MTRANS_X] = 0f
            out[MSKEW_Y] = 0f;  out[MSCALE_Y] = sy; out[MTRANS_Y] = 0f
            out[MPERSP_0] = 0f; out[MPERSP_1] = 0f; out[MPERSP_2] = 1f
        }

        private fun rotate(out: FloatArray, degrees: Float) {
            val r = degrees * (kotlin.math.PI / 180.0).toFloat()
            val c = cos(r); val s = sin(r)
            out[MSCALE_X] = c;  out[MSKEW_X] = -s; out[MTRANS_X] = 0f
            out[MSKEW_Y] = s;   out[MSCALE_Y] = c; out[MTRANS_Y] = 0f
            out[MPERSP_0] = 0f; out[MPERSP_1] = 0f; out[MPERSP_2] = 1f
        }

        // Returns A * B (3x3 row-major) as a fresh 9-float array.
        private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(9)
            r[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6]
            r[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7]
            r[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8]

            r[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6]
            r[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7]
            r[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8]

            r[6] = a[6] * b[0] + a[7] * b[3] + a[8] * b[6]
            r[7] = a[6] * b[1] + a[7] * b[4] + a[8] * b[7]
            r[8] = a[6] * b[2] + a[7] * b[5] + a[8] * b[8]
            return r
        }
    }
}

/** Mutable axis-aligned rectangle with the small subset of `android.graphics.RectF` we need. */
class RectF(
    var left: Float = 0f,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f,
) {
    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    fun set(other: RectF) {
        set(other.left, other.top, other.right, other.bottom)
    }

    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun isEmpty(): Boolean = left >= right || top >= bottom
    fun copy(): RectF = RectF(left, top, right, bottom)

    /** Centroid X. */
    fun centerX(): Float = (left + right) * 0.5f
    /** Centroid Y. */
    fun centerY(): Float = (top + bottom) * 0.5f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RectF) return false
        return left == other.left && top == other.top && right == other.right && bottom == other.bottom
    }

    override fun hashCode(): Int {
        var r = left.hashCode()
        r = 31 * r + top.hashCode()
        r = 31 * r + right.hashCode()
        r = 31 * r + bottom.hashCode()
        return r
    }

    override fun toString(): String = "RectF($left, $top, $right, $bottom)"
}

/** Simple mutable 2D point. */
data class PointF(var x: Float = 0f, var y: Float = 0f) {
    fun set(x: Float, y: Float) { this.x = x; this.y = y }
    fun set(other: PointF) { this.x = other.x; this.y = other.y }
}

internal fun approxEqual(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps

internal fun rectsEqual(a: RectF, b: RectF, eps: Float = 1e-4f): Boolean =
    approxEqual(a.left, b.left, eps) && approxEqual(a.top, b.top, eps) &&
        approxEqual(a.right, b.right, eps) && approxEqual(a.bottom, b.bottom, eps)

internal fun extentX(values: FloatArray): Float =
    max(abs(values[Matrix2D.MSCALE_X]), abs(values[Matrix2D.MSKEW_X]))
