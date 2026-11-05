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

// The preset accent each page draws (odin-js cards/presets.ts); Poster's is its ink.
private fun thumbnailAccentArgb(design: String): Long = when (design) {
    CardDesign.POSTER -> 0xFFF4F0EA
    CardDesign.COLLAGE -> 0xFF425985
    CardDesign.DOSSIER -> 0xFF8FAEEA
    CardDesign.LINKS -> 0xFF1A66D6
    else -> 0xFFB6C5FA
}

/** A schematic of the design's layout, so picking reads as picking a look rather than a word. */
@Composable
internal fun CardDesignThumbnail(
    design: String,
    modifier: Modifier = Modifier,
    groundOverride: Color? = null,
    inkOverride: Color? = null,
    accentOverride: Color? = null,
    portrait: String? = null,
    blocks: String? = null,
    socials: String? = null,
) {
    val base = groundOverride ?: Color(CardDesign.baseArgb(design))
    val ink = inkOverride ?: Color(CardDesign.inkArgb(design))
    val accent = accentOverride ?: Color(thumbnailAccentArgb(design))
    Canvas(modifier = modifier) {
        drawRect(base)
        when (design) {
            CardDesign.POSTER -> poster(ink, accent, blocks, socials)
            CardDesign.COLLAGE -> collage(ink, accent, portrait, socials)
            CardDesign.DOSSIER -> dossier(ink, accent, portrait, blocks, socials)
            CardDesign.LINKS -> links(ink, accent, portrait, socials)
            else -> board(ink, accent, portrait, blocks, socials)
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

/** A portrait of [shape] centred on ([cx], [cy]) in fractions, [w] and [h] fractions of the width. */
private fun DrawScope.portraitMark(shape: String, cx: Float, cy: Float, w: Float, h: Float, color: Color) {
    val size = Size(size.width * w, size.width * h)
    val topLeft = Offset(this.size.width * cx - size.width / 2, this.size.height * cy - size.height / 2)
    when (shape) {
        "circle" -> drawCircle(color, radius = size.minDimension / 2, center = Offset(this.size.width * cx, this.size.height * cy))
        "ellipse" -> drawOval(color, topLeft = Offset(topLeft.x + size.width * 0.12f, topLeft.y), size = Size(size.width * 0.76f, size.height))
        "rounded" -> drawRoundRect(color, topLeft = topLeft, size = size, cornerRadius = CornerRadius(size.minDimension * 0.25f))
        else -> drawRect(color, topLeft = topLeft, size = size)
    }
}

private fun DrawScope.blockRow(style: String, x: Float, y: Float, w: Float, h: Float, ink: Color, accent: Color) {
    when (style) {
        "bare" -> bar(x, y + h * 0.35f, w * 0.7f, h * 0.3f, ink.copy(alpha = 0.45f))
        "row" -> {
            bar(x, y, w, h, ink.copy(alpha = 0.18f))
            drawRoundRect(
                color = ink.copy(alpha = 0.6f),
                topLeft = Offset(size.width * x + size.height * h * 0.2f, size.height * (y + h * 0.2f)),
                size = Size(size.height * h * 0.6f, size.height * h * 0.6f),
                cornerRadius = CornerRadius(size.height * h * 0.12f),
            )
        }
        "button" -> bar(x, y, w, h, accent)
        else -> bar(x, y, w, h, ink.copy(alpha = 0.35f))
    }
}

/** A row of socials centred on [cx] at [y], each a fraction of the size. */
private fun DrawScope.socialsRow(style: String, cx: Float, y: Float, ink: Color) {
    val mark = ink.copy(alpha = 0.6f)
    when (style) {
        "bar" -> bar(cx - 0.24f, y - 0.02f, 0.48f, 0.04f, mark)
        "wordmark" -> listOf(-1, 0, 1).forEach { bar(cx - 0.07f + it * 0.17f, y - 0.0125f, 0.14f, 0.025f, mark) }
        "handles" -> {
            bar(cx - 0.2f, y - 0.03f, 0.4f, 0.018f, mark)
            bar(cx - 0.2f, y + 0.012f, 0.28f, 0.018f, mark)
        }
        else -> listOf(-1, 0, 1).forEach { drawCircle(mark, radius = size.width * 0.03f, center = Offset(size.width * (cx + it * 0.12f), size.height * y)) }
    }
}

private fun DrawScope.poster(ink: Color, accent: Color, blocks: String?, socials: String?) {
    bar(0.12f, 0.12f, 0.18f, 0.03f, ink.copy(alpha = 0.5f))
    if (blocks == null && socials == null) {
        bar(0.12f, 0.56f, 0.72f, 0.09f, ink)
        bar(0.12f, 0.68f, 0.52f, 0.09f, ink)
        bar(0.12f, 0.82f, 0.30f, 0.035f, accent)
        return
    }
    bar(0.12f, 0.36f, 0.72f, 0.08f, ink)
    bar(0.12f, 0.47f, 0.52f, 0.08f, ink)
    blocks?.let { listOf(0.62f, 0.73f).forEach { y -> blockRow(blocks, 0.12f, y, 0.76f, 0.07f, ink, accent) } }
    socials?.let { socialsRow(it, 0.5f, 0.88f, ink) }
}

private fun DrawScope.board(ink: Color, accent: Color, portrait: String?, blocks: String?, socials: String?) {
    val r = size.width * 0.17f
    if (portrait == null) {
        drawCircle(ink, radius = r + size.width * 0.03f, center = Offset(size.width / 2, size.height * 0.24f))
        drawCircle(accent, radius = r, center = Offset(size.width / 2, size.height * 0.24f))
    } else {
        portraitMark(portrait, 0.5f, 0.24f, 0.40f, 0.40f, ink)
        portraitMark(portrait, 0.5f, 0.24f, 0.34f, 0.34f, accent)
    }
    bar(0.26f, 0.44f, 0.48f, 0.04f, ink)
    val rows = if (socials == null) listOf(0.56f, 0.68f, 0.80f) else listOf(0.61f, 0.72f, 0.83f)
    val height = if (socials == null) 0.08f else 0.075f
    rows.forEach { y -> if (blocks == null) bar(0.14f, y, 0.72f, height, ink.copy(alpha = 0.35f)) else blockRow(blocks, 0.14f, y, 0.72f, height, ink, accent) }
    socials?.let { socialsRow(it, 0.5f, 0.54f, ink) }
}

private fun DrawScope.collage(ink: Color, accent: Color, portrait: String?, socials: String?) {
    rotate(-8f, pivot = Offset(size.width * 0.36f, size.height * 0.28f)) {
        if (portrait == null) drawRect(ink.copy(alpha = 0.8f), Offset(size.width * 0.12f, size.height * 0.12f), Size(size.width * 0.46f, size.height * 0.32f))
        else portraitMark(portrait, 0.35f, 0.28f, 0.46f, 0.46f, ink.copy(alpha = 0.8f))
    }
    rotate(7f, pivot = Offset(size.width * 0.62f, size.height * 0.42f)) {
        if (portrait == null) drawRect(accent, Offset(size.width * 0.40f, size.height * 0.28f), Size(size.width * 0.46f, size.height * 0.30f))
        else portraitMark(portrait, 0.63f, 0.43f, 0.44f, 0.44f, accent)
    }
    bar(0.14f, 0.70f, 0.62f, 0.07f, ink)
    if (socials == null) {
        bar(0.14f, 0.82f, 0.42f, 0.035f, ink.copy(alpha = 0.55f))
    } else {
        bar(0.14f, 0.80f, 0.42f, 0.03f, ink.copy(alpha = 0.55f))
        socialsRow(socials, 0.5f, 0.90f, ink)
    }
}

private fun DrawScope.dossier(ink: Color, accent: Color, portrait: String?, blocks: String?, socials: String?) {
    if (portrait == null) drawRect(ink.copy(alpha = 0.75f), Offset(size.width * 0.12f, size.height * 0.10f), Size(size.width * 0.30f, size.width * 0.30f))
    else portraitMark(portrait, 0.27f, 0.10f + size.width * 0.15f / size.height, 0.30f, 0.30f, ink.copy(alpha = 0.75f))
    bar(0.50f, 0.12f, 0.36f, 0.035f, accent)
    bar(0.50f, 0.20f, 0.28f, 0.03f, ink.copy(alpha = 0.5f))
    if (blocks == null) {
        listOf(0.40f, 0.48f, 0.56f, 0.64f, 0.72f).forEachIndexed { i, y ->
            bar(0.12f, y, if (i % 2 == 0) 0.76f else 0.58f, 0.025f, ink.copy(alpha = 0.45f))
        }
    } else {
        listOf(0.40f, 0.51f, 0.62f).forEach { y -> blockRow(blocks, 0.12f, y, 0.76f, 0.075f, ink, accent) }
    }
    if (socials == null) bar(0.12f, 0.84f, 0.22f, 0.035f, accent) else socialsRow(socials, 0.5f, 0.85f, ink)
}

private fun DrawScope.links(ink: Color, accent: Color, portrait: String?, socials: String?) {
    if (portrait == null) drawCircle(accent, radius = size.width * 0.12f, center = Offset(size.width / 2, size.height * 0.17f))
    else portraitMark(portrait, 0.5f, 0.17f, 0.24f, 0.24f, accent)
    bar(0.32f, 0.33f, 0.36f, 0.035f, ink)
    listOf(0.44f, 0.56f, 0.68f).forEach { y ->
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.12f, size.height * y),
            size = Size(size.width * 0.76f, size.height * 0.085f),
            cornerRadius = CornerRadius(size.height * 0.03f),
        )
        drawCircle(accent.copy(alpha = 0.8f), radius = size.height * 0.02f, center = Offset(size.width * 0.19f, size.height * (y + 0.0425f)))
        bar(0.26f, y + 0.032f, 0.46f, 0.02f, ink.copy(alpha = 0.55f))
    }
    if (socials == null) listOf(0.38f, 0.5f, 0.62f).forEach { x -> drawCircle(ink.copy(alpha = 0.6f), radius = size.width * 0.03f, center = Offset(size.width * x, size.height * 0.88f)) }
    else socialsRow(socials, 0.5f, 0.88f, ink)
}
