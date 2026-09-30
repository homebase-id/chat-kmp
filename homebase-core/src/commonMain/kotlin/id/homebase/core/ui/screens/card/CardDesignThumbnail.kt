package id.homebase.core.ui.screens.card

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate

internal const val CARD_THUMB_ASPECT = 0.66f

// The pages' own ink and accent, like baseArgb: a design's thumbnail wears its colours, not the app theme's.
private fun inkArgb(design: String): Long = when (design) {
    CardDesign.POSTER -> 0xFFF2EDE4
    CardDesign.COLLAGE -> 0xFF2B2622
    CardDesign.DOSSIER -> 0xFFC9D1D9
    else -> 0xFFE9F0FA
}

private fun accentArgb(design: String): Long = when (design) {
    CardDesign.POSTER -> 0xFFF26B5B
    CardDesign.COLLAGE -> 0xFFB4553A
    CardDesign.DOSSIER -> 0xFF4FD1C5
    else -> 0xFFF2B84B
}

/** A schematic of the design's layout, so picking reads as picking a look rather than a word. */
@Composable
internal fun CardDesignThumbnail(design: String, modifier: Modifier = Modifier) {
    val base = Color(CardDesign.baseArgb(design))
    val ink = Color(inkArgb(design))
    val accent = Color(accentArgb(design))
    Canvas(modifier = modifier) {
        drawRect(base)
        when (design) {
            CardDesign.POSTER -> poster(ink, accent)
            CardDesign.COLLAGE -> collage(ink, accent)
            CardDesign.DOSSIER -> dossier(ink, accent)
            else -> board(ink, accent)
        }
    }
}

private fun DrawScope.bar(x: Float, y: Float, w: Float, h: Float, color: Color) {
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width * x, size.height * y),
        size = Size(size.width * w, size.height * h),
        cornerRadius = CornerRadius(size.height * h / 2),
    )
}

private fun DrawScope.poster(ink: Color, accent: Color) {
    bar(0.12f, 0.56f, 0.72f, 0.09f, ink)
    bar(0.12f, 0.68f, 0.52f, 0.09f, ink)
    bar(0.12f, 0.82f, 0.30f, 0.035f, accent)
    bar(0.12f, 0.12f, 0.18f, 0.03f, ink.copy(alpha = 0.5f))
}

private fun DrawScope.board(ink: Color, accent: Color) {
    val r = size.width * 0.17f
    drawCircle(ink, radius = r + size.width * 0.03f, center = Offset(size.width / 2, size.height * 0.24f))
    drawCircle(accent, radius = r, center = Offset(size.width / 2, size.height * 0.24f))
    bar(0.26f, 0.44f, 0.48f, 0.04f, ink)
    listOf(0.56f, 0.68f, 0.80f).forEach { y -> bar(0.14f, y, 0.72f, 0.08f, ink.copy(alpha = 0.35f)) }
}

private fun DrawScope.collage(ink: Color, accent: Color) {
    rotate(-8f, pivot = Offset(size.width * 0.36f, size.height * 0.28f)) {
        drawRect(ink.copy(alpha = 0.8f), Offset(size.width * 0.12f, size.height * 0.12f), Size(size.width * 0.46f, size.height * 0.32f))
    }
    rotate(7f, pivot = Offset(size.width * 0.62f, size.height * 0.42f)) {
        drawRect(accent, Offset(size.width * 0.40f, size.height * 0.28f), Size(size.width * 0.46f, size.height * 0.30f))
    }
    bar(0.14f, 0.70f, 0.62f, 0.07f, ink)
    bar(0.14f, 0.82f, 0.42f, 0.035f, ink.copy(alpha = 0.55f))
}

private fun DrawScope.dossier(ink: Color, accent: Color) {
    drawRect(ink.copy(alpha = 0.75f), Offset(size.width * 0.12f, size.height * 0.10f), Size(size.width * 0.30f, size.width * 0.30f))
    bar(0.50f, 0.12f, 0.36f, 0.035f, accent)
    bar(0.50f, 0.20f, 0.28f, 0.03f, ink.copy(alpha = 0.5f))
    listOf(0.40f, 0.48f, 0.56f, 0.64f, 0.72f).forEachIndexed { i, y ->
        bar(0.12f, y, if (i % 2 == 0) 0.76f else 0.58f, 0.025f, ink.copy(alpha = 0.45f))
    }
    bar(0.12f, 0.84f, 0.22f, 0.035f, accent)
}
