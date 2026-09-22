package id.homebase.auth.login

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import id.homebase.core.ui.theme.HomebaseBrand
import kotlin.math.max

/**
 * Ursa Major plus Polaris, from J2000 RA/Dec through a stereographic projection about
 * ra 12h / dec +68, drawn North-up and **East-left** — the as-seen-from-Earth chart convention.
 * Normalised so Alkaid-to-Merak is 1.0. Stereographic because it is conformal, so the asterism
 * keeps its shape, and because Polaris's projected position barely moves as the centre is varied
 * (0.703-0.704 across dec 56-75, against 0.709-0.711 gnomonic).
 *
 * Every star including Polaris is its own projected position. Extending the Merak-Dubhe pointer
 * arithmetically does not land on Polaris: the three are 4.0 degrees off collinear in the sky and
 * the true separation ratio is 5.34x, so the folklore "five times" is an approximation, not a
 * construction. Getting the East sign wrong yields a mirrored Dipper — bowl and handle swap sides
 * while Polaris stays above, which no rotation of the real sky can produce.
 */
private val PolarisHeadroom = 72.dp
private val PolarisInset = 56.dp

private class Star(val x: Float, val y: Float, val radiusDp: Float, val alpha: Float)

private val Dubhe = Star(0.944f, 0.000f, 3.0f, 0.94f)
private val Merak = Star(1.000f, 0.204f, 2.5f, 0.87f)
private val Phecda = Star(0.722f, 0.345f, 2.4f, 0.86f)
private val Megrez = Star(0.603f, 0.211f, 1.5f, 0.72f)
private val Alioth = Star(0.389f, 0.225f, 3.0f, 0.95f)
private val Mizar = Star(0.217f, 0.220f, 2.6f, 0.88f)
private val Alkaid = Star(0.000f, 0.378f, 2.9f, 0.93f)

private val BigDipper = listOf(Dubhe, Merak, Phecda, Megrez, Alioth, Mizar, Alkaid)
private val DipperLines = listOf(
    Dubhe to Merak, Merak to Phecda, Phecda to Megrez, Megrez to Dubhe,
    Megrez to Alioth, Alioth to Mizar, Mizar to Alkaid,
)
private val Polaris = Star(0.704f, -1.106f, 4.2f, 1f)

// Polaris is the anchor and the asterism hangs below-left of it, so this span sizes it.
private const val PolarisRise = 1.106f

// Holds the asterism to the share of the pane it has at the reference width; without it the sky
// outgrows the brand block, which scales on a separate clamp.
private const val MaxSkySpan = 0.32f

// Only in the bands above and below the brand block, so no star ever sits behind the wordmark at
// any pane size — the block is vertically centred and never taller than these bands leave.
private val BackgroundStars = listOf(
    Star(0.16f, 0.10f, 1.4f, 0.40f), Star(0.39f, 0.21f, 1.0f, 0.28f),
    Star(0.71f, 0.08f, 1.2f, 0.34f), Star(0.88f, 0.19f, 0.9f, 0.24f),
    Star(0.29f, 0.30f, 0.8f, 0.20f), Star(0.62f, 0.28f, 1.1f, 0.30f),
    Star(0.21f, 0.79f, 1.2f, 0.32f), Star(0.49f, 0.88f, 0.9f, 0.22f),
    Star(0.78f, 0.73f, 1.4f, 0.38f), Star(0.93f, 0.85f, 1.0f, 0.26f),
)

/**
 * Curtains over the sky, per p5's "Colors inspired by the Northern Lights". Each is a single
 * radial gradient squashed into a flat ellipse: alpha falls off per pixel in every direction, so
 * there is no edge and no tip anywhere to catch the light. Three earlier attempts failed on
 * technique — stacked strokes and then overlapping dabs both contoured, because summing quantised
 * alpha layers makes its own level sets visible; a pointed lens with a cross-gradient put peak
 * alpha at its tips and read as light beams.
 *
 * Green leads because it is a secondary colour, licensed to "provide variety and visual depth";
 * cyan is an accent, "only used for smaller parts", so it is one dim highlight along the fold.
 */
private class AuroraBand(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val degrees: Float,
    val alpha: Float,
    val accent: Boolean = false,
)

private val Aurora = listOf(
    AuroraBand(cx = 0.20f, cy = 0.16f, rx = 0.42f, ry = 0.115f, degrees = -8f, alpha = 0.34f),
    AuroraBand(cx = 0.34f, cy = 0.26f, rx = 0.30f, ry = 0.075f, degrees = -8f, alpha = 0.20f),
    AuroraBand(cx = 0.18f, cy = 0.135f, rx = 0.24f, ry = 0.028f, degrees = -8f, alpha = 0.12f, accent = true),
)

// 8-bit quantisation, not layer count, is what contours a smooth gradient: one alpha-blended
// gradient over a gradient ground already steps in 1-LSB plateaus 9-14px wide. A tile of neutral
// noise at ~1% breaks the plateaus into dither and costs one draw. Deterministic LCG, built once.
private const val DitherTile = 64
private const val DitherAlpha = 0.012f

private fun buildDitherTile(): ImageBitmap {
    val bitmap = ImageBitmap(DitherTile, DitherTile)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    var state = 0x2545F491
    for (y in 0 until DitherTile) {
        for (x in 0 until DitherTile) {
            state = state * 1664525 + 1013904223
            val level = (state ushr 23) and 0xFF
            paint.color = Color(red = level, green = level, blue = level)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
        }
    }
    return bitmap
}

private fun DrawScope.drawAurora() {
    Aurora.forEach { band ->
        val centre = Offset(band.cx * size.width, band.cy * size.height)
        val radius = band.rx * size.width
        val colour = if (band.accent) HomebaseBrand.Cyan else HomebaseBrand.Green
        rotate(degrees = band.degrees, pivot = centre) {
            scale(scaleX = 1f, scaleY = band.ry / band.rx, pivot = centre) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colour.copy(alpha = band.alpha),
                            colour.copy(alpha = 0f),
                        ),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            }
        }
    }
}

@Composable
internal fun LoginBrandArtwork(
    modifier: Modifier = Modifier,
    blockHalfHeight: Dp = BrandBlockHalfHeight,
    blockRight: Dp = BrandPanelPadding + BrandContentMaxWidth,
) {
    val dither = remember { buildDitherTile() }
    Canvas(modifier = modifier.clipToBounds()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(HomebaseBrand.Purple, HomebaseBrand.Blue),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        )
        drawAurora()
        drawRect(
            brush = ShaderBrush(ImageShader(dither, TileMode.Repeated, TileMode.Repeated)),
            alpha = DitherAlpha,
        )

        // Everything the composition must not paint over: the mark, wordmark and identity text.
        val half = blockHalfHeight.toPx()
        val keepOut = Rect(0f, size.height / 2f - half, blockRight.toPx(), size.height / 2f + half)
        // Polaris is placed first — it is the beacon — and the asterism hangs below-left of it.
        val beacon = Offset(
            x = size.width - max(size.width * 0.18f, PolarisInset.toPx()),
            y = max(size.height * 0.10f, PolarisHeadroom.toPx()),
        )
        // The asterism clears the brand block by starting to its right.
        val span = ((beacon.x - keepOut.right) / Polaris.x)
            .coerceIn(64.dp.toPx(), size.width * MaxSkySpan)
        val originX = beacon.x - Polaris.x * span
        val baseline = beacon.y + PolarisRise * span
        fun place(star: Star) = Offset(originX + star.x * span, baseline + star.y * span)

        val polaris = place(Polaris)
        drawLine(
            color = HomebaseBrand.White.copy(alpha = 0.07f),
            start = place(Dubhe),
            end = polaris,
            strokeWidth = 1.dp.toPx(),
        )
        DipperLines.forEach { (from, to) ->
            drawLine(
                color = HomebaseBrand.White.copy(alpha = 0.16f),
                start = place(from),
                end = place(to),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        val margin = 12.dp.toPx()
        BackgroundStars.forEach {
            val at = Offset(it.x * size.width, it.y * size.height)
            // Enforced, not eyeballed: a discrete point beside a line of text reads as a stray
            // marker, however faint. Ambient geometry may cross text; points may not.
            if (!keepOut.inflate(margin).contains(at)) {
                drawCircle(HomebaseBrand.White.copy(alpha = it.alpha), it.radiusDp.dp.toPx(), at)
            }
        }
        BigDipper.forEach { drawStar(place(it), it.radiusDp.dp.toPx(), it.alpha, glow = 3.5f) }
        drawBeacon(polaris, Polaris.radiusDp.dp.toPx())
    }
}

private fun DrawScope.drawStar(center: Offset, radius: Float, alpha: Float, glow: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                HomebaseBrand.White.copy(alpha = alpha * 0.30f),
                HomebaseBrand.White.copy(alpha = 0f),
            ),
            center = center,
            radius = radius * glow,
        ),
        radius = radius * glow,
        center = center,
    )
    drawCircle(HomebaseBrand.White.copy(alpha = alpha), radius, center)
}

/** The beacon: the one thing in the pane allowed to be this bright. */
private fun DrawScope.drawBeacon(center: Offset, radius: Float) {
    drawStar(center, radius, alpha = 1f, glow = 9f)
    val reach = radius * 7f
    listOf(
        Offset(reach, 0f) to Offset(-reach, 0f),
        Offset(0f, reach) to Offset(0f, -reach),
    ).forEach { (a, b) ->
        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    HomebaseBrand.White.copy(alpha = 0f),
                    HomebaseBrand.White.copy(alpha = 0.55f),
                    HomebaseBrand.White.copy(alpha = 0f),
                ),
                start = center + a,
                end = center + b,
            ),
            start = center + a,
            end = center + b,
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
