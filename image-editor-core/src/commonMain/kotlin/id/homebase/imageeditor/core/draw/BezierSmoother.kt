package id.homebase.imageeditor.core.draw

import id.homebase.api.image.draw.PathCommand

/**
 * Computes cubic-Bezier control points for a polyline so the resulting curve
 * passes through every input point. Port of Signal-Android's
 * `AutomaticControlPointBezierLine` (AGPL-3.0); algorithm originally from
 * http://www.particleincell.com/2012/bezier-splines/ — solves a tridiagonal
 * system per axis with the Thomas algorithm.
 *
 * Pure-Kotlin, no platform deps. Allocates four working arrays sized to the
 * input — fine for one-shot smoothing at stroke commit time. The crop
 * cropper's bounds-space convention applies: caller passes points in
 * whatever space they want; smoother is coordinate-system-agnostic.
 */
internal object BezierSmoother {

    /**
     * Build a smoothed path through [pointsXY] (interleaved x0,y0,x1,y1,…).
     *
     * Returns: a single [PathCommand.MoveTo] followed by [PathCommand.LineTo]
     * (1 or 2 points) or [PathCommand.CubicTo] segments (3+ points).
     */
    fun smooth(pointsXY: FloatArray): List<PathCommand> {
        val count = pointsXY.size / 2
        if (count == 0) return emptyList()

        val xs = FloatArray(count) { pointsXY[it * 2] }
        val ys = FloatArray(count) { pointsXY[it * 2 + 1] }

        val out = ArrayList<PathCommand>(count)
        out.add(PathCommand.MoveTo(xs[0], ys[0]))

        when (count) {
            1 -> out.add(PathCommand.LineTo(xs[0], ys[0]))
            2 -> out.add(PathCommand.LineTo(xs[1], ys[1]))
            else -> {
                val n = count - 1
                val p1x = FloatArray(n)
                val p1y = FloatArray(n)
                val p2x = FloatArray(n)
                val p2y = FloatArray(n)
                computeControlPoints(xs, p1x, p2x, count)
                computeControlPoints(ys, p1y, p2y, count)
                for (i in 1 until n) {
                    out.add(
                        PathCommand.CubicTo(
                            c1x = p1x[i], c1y = p1y[i],
                            c2x = p2x[i], c2y = p2y[i],
                            x = xs[i + 1], y = ys[i + 1],
                        ),
                    )
                }
            }
        }
        return out
    }

    /**
     * Solve the tridiagonal system for one axis. Mirrors Signal's
     * `computeControlPoints` exactly so the smoothed curve is visually
     * identical.
     */
    private fun computeControlPoints(k: FloatArray, p1: FloatArray, p2: FloatArray, count: Int) {
        val n = count - 1
        val a = FloatArray(n)
        val b = FloatArray(n)
        val c = FloatArray(n)
        val r = FloatArray(n)

        a[0] = 0f; b[0] = 2f; c[0] = 1f
        r[0] = k[0] + 2f * k[1]

        for (i in 1 until n - 1) {
            a[i] = 1f; b[i] = 4f; c[i] = 1f
            r[i] = 4f * k[i] + 2f * k[i + 1]
        }

        a[n - 1] = 2f; b[n - 1] = 7f; c[n - 1] = 0f
        r[n - 1] = 8f * k[n - 1] + k[n]

        // Forward sweep (Thomas).
        for (i in 1 until n) {
            val m = a[i] / b[i - 1]
            b[i] -= m * c[i - 1]
            r[i] -= m * r[i - 1]
        }

        // Back substitution for p1.
        p1[n - 1] = r[n - 1] / b[n - 1]
        for (i in n - 2 downTo 0) {
            p1[i] = (r[i] - c[i] * p1[i + 1]) / b[i]
        }

        // Derive p2 from p1.
        for (i in 0 until n - 1) {
            p2[i] = 2f * k[i + 1] - p1[i + 1]
        }
        p2[n - 1] = 0.5f * (k[n] + p1[n - 1])
    }
}
