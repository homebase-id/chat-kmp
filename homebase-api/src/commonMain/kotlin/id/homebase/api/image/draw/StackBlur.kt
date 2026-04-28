package id.homebase.api.image.draw

/**
 * In-place Gaussian-approximating blur on an ARGB pixel buffer.
 *
 * Implementation: Mario Klingemann's stack-blur algorithm, ported to
 * Kotlin. Roughly 7× faster than a true Gaussian, visually
 * indistinguishable for typical UI radii (8..50). No platform deps so the
 * same blur lands identically on jvm, android, and ios.
 *
 * The buffer must hold `width * height` integer pixels in row-major
 * `0xAARRGGBB` form (`Bitmap.getPixels` / Skia Pixmap layout).
 */
internal fun stackBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    if (radius < 1) return
    require(pixels.size >= width * height) { "pixel buffer too small" }

    val w = width
    val h = height
    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = radius + radius + 1
    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    val a = IntArray(wh)
    val vmin = IntArray(maxOf(w, h))

    val divsum = (div + 1) shr 1
    val divsumSq = divsum * divsum
    val dv = IntArray(256 * divsumSq)
    for (i in 0 until 256 * divsumSq) dv[i] = i / divsumSq

    val stack = Array(div) { IntArray(4) }

    var yi = 0
    var yw = 0

    for (y in 0 until h) {
        var rsum = 0; var gsum = 0; var bsum = 0; var asum = 0
        var routSum = 0; var goutSum = 0; var boutSum = 0; var aoutSum = 0
        var rinSum = 0; var ginSum = 0; var binSum = 0; var ainSum = 0
        for (i in -radius..radius) {
            val p = pixels[yi + minOf(wm, maxOf(i, 0))]
            val side = stack[i + radius]
            side[0] = (p shr 16) and 0xFF
            side[1] = (p shr 8) and 0xFF
            side[2] = p and 0xFF
            side[3] = (p ushr 24) and 0xFF
            val rbs = radius + 1 - kotlin.math.abs(i)
            rsum += side[0] * rbs
            gsum += side[1] * rbs
            bsum += side[2] * rbs
            asum += side[3] * rbs
            if (i > 0) {
                rinSum += side[0]; ginSum += side[1]; binSum += side[2]; ainSum += side[3]
            } else {
                routSum += side[0]; goutSum += side[1]; boutSum += side[2]; aoutSum += side[3]
            }
        }
        var stackpointer = radius
        for (x in 0 until w) {
            r[yi] = dv[rsum]
            g[yi] = dv[gsum]
            b[yi] = dv[bsum]
            a[yi] = dv[asum]

            rsum -= routSum; gsum -= goutSum; bsum -= boutSum; asum -= aoutSum
            var stackstart = stackpointer - radius + div
            val sir = stack[stackstart % div]
            routSum -= sir[0]; goutSum -= sir[1]; boutSum -= sir[2]; aoutSum -= sir[3]

            if (y == 0) vmin[x] = minOf(x + radius + 1, wm)
            val p = pixels[yw + vmin[x]]
            sir[0] = (p shr 16) and 0xFF
            sir[1] = (p shr 8) and 0xFF
            sir[2] = p and 0xFF
            sir[3] = (p ushr 24) and 0xFF
            rinSum += sir[0]; ginSum += sir[1]; binSum += sir[2]; ainSum += sir[3]

            rsum += rinSum; gsum += ginSum; bsum += binSum; asum += ainSum
            stackpointer = (stackpointer + 1) % div
            val nextSir = stack[stackpointer]
            routSum += nextSir[0]; goutSum += nextSir[1]; boutSum += nextSir[2]; aoutSum += nextSir[3]
            rinSum -= nextSir[0]; ginSum -= nextSir[1]; binSum -= nextSir[2]; ainSum -= nextSir[3]
            yi++
        }
        yw += w
    }

    for (x in 0 until w) {
        var rsum = 0; var gsum = 0; var bsum = 0; var asum = 0
        var routSum = 0; var goutSum = 0; var boutSum = 0; var aoutSum = 0
        var rinSum = 0; var ginSum = 0; var binSum = 0; var ainSum = 0
        var yp = -radius * w
        for (i in -radius..radius) {
            val yiClamped = maxOf(0, yp) + x
            val side = stack[i + radius]
            side[0] = r[yiClamped]; side[1] = g[yiClamped]; side[2] = b[yiClamped]; side[3] = a[yiClamped]
            val rbs = radius + 1 - kotlin.math.abs(i)
            rsum += side[0] * rbs
            gsum += side[1] * rbs
            bsum += side[2] * rbs
            asum += side[3] * rbs
            if (i > 0) {
                rinSum += side[0]; ginSum += side[1]; binSum += side[2]; ainSum += side[3]
            } else {
                routSum += side[0]; goutSum += side[1]; boutSum += side[2]; aoutSum += side[3]
            }
            if (i < hm) yp += w
        }
        var yiOut = x
        var stackpointer = radius
        for (y in 0 until h) {
            pixels[yiOut] =
                (dv[asum] shl 24) or
                    (dv[rsum] shl 16) or
                    (dv[gsum] shl 8) or
                    dv[bsum]
            rsum -= routSum; gsum -= goutSum; bsum -= boutSum; asum -= aoutSum
            var stackstart = stackpointer - radius + div
            val sir = stack[stackstart % div]
            routSum -= sir[0]; goutSum -= sir[1]; boutSum -= sir[2]; aoutSum -= sir[3]

            if (x == 0) vmin[y] = minOf(y + radius + 1, hm) * w
            val p = x + vmin[y]
            sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]; sir[3] = a[p]
            rinSum += sir[0]; ginSum += sir[1]; binSum += sir[2]; ainSum += sir[3]

            rsum += rinSum; gsum += ginSum; bsum += binSum; asum += ainSum
            stackpointer = (stackpointer + 1) % div
            val nextSir = stack[stackpointer]
            routSum += nextSir[0]; goutSum += nextSir[1]; boutSum += nextSir[2]; aoutSum += nextSir[3]
            rinSum -= nextSir[0]; ginSum -= nextSir[1]; binSum -= nextSir[2]; ainSum -= nextSir[3]
            yiOut += w
        }
    }
}
