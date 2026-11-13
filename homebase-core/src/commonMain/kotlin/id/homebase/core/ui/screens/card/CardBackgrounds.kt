package id.homebase.core.ui.screens.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import id.homebase.resources.MR
import id.homebase.resources.profile_card_background_blobs
import id.homebase.resources.profile_card_background_circles
import id.homebase.resources.profile_card_background_dots
import id.homebase.resources.profile_card_background_lines
import id.homebase.resources.profile_card_background_none
import id.homebase.resources.profile_card_background_waves
import id.homebase.resources.profile_card_background_rings
import id.homebase.resources.profile_card_background_grid
import id.homebase.resources.profile_card_background_checker
import id.homebase.resources.profile_card_background_zigzag
import id.homebase.resources.profile_card_background_confetti
import id.homebase.resources.profile_card_background_contours
import id.homebase.resources.profile_card_background_halftone
import id.homebase.resources.profile_card_background_sparkles
import org.jetbrains.compose.resources.StringResource

object CardBackgrounds {
    const val NONE = "none"
    private val SHARED = listOf("dots", "waves", "lines", "rings", "grid", "checker", "zigzag", "confetti", "contours", "halftone", "sparkles")
    private val EXTRA = mapOf(
        CardDesign.BOARD to "circles",
        CardDesign.COLLAGE to "blobs",
        CardDesign.POSTER to "circles",
        CardDesign.DOSSIER to "lines",
        CardDesign.LINKS to "blobs",
    )

    /** The patterns a design offers; the preset's own pattern is the unset choice and is not listed. */
    fun variantsFor(design: String): List<String> = (listOfNotNull(EXTRA[design]) + SHARED).distinct() + NONE

    /** An unknown value falls back to the preset's pattern. */
    internal fun known(value: String?): String? = value?.takeIf { v -> v == NONE || v in SHARED || v in EXTRA.values }
}

internal fun backgroundLabel(id: String): StringResource = when (id) {
    "circles" -> MR.string.profile_card_background_circles
    "waves" -> MR.string.profile_card_background_waves
    "dots" -> MR.string.profile_card_background_dots
    "blobs" -> MR.string.profile_card_background_blobs
    "lines" -> MR.string.profile_card_background_lines
    "rings" -> MR.string.profile_card_background_rings
    "grid" -> MR.string.profile_card_background_grid
    "checker" -> MR.string.profile_card_background_checker
    "zigzag" -> MR.string.profile_card_background_zigzag
    "confetti" -> MR.string.profile_card_background_confetti
    "contours" -> MR.string.profile_card_background_contours
    "halftone" -> MR.string.profile_card_background_halftone
    "sparkles" -> MR.string.profile_card_background_sparkles
    else -> MR.string.profile_card_background_none
}

internal fun DrawScope.backgroundPattern(id: String, color: Color) {
    val w = size.width
    val h = size.height
    when (id) {
        "circles" -> {
            drawCircle(color, radius = w * 0.34f, center = Offset(w * 0.85f, h * 0.15f))
            drawCircle(color, radius = w * 0.22f, center = Offset(w * 0.1f, h * 0.9f))
            drawCircle(color, radius = w * 0.45f, center = Offset(w * 0.85f, h * 0.15f), style = Stroke(w * 0.03f))
        }
        "waves" -> {
            val stroke = Stroke(h * 0.045f)
            for (row in 0 until 3) {
                val y = h * (0.3f + row * 0.22f)
                val path = Path().apply {
                    moveTo(0f, y)
                    cubicTo(w * 0.25f, y - h * 0.12f, w * 0.25f, y - h * 0.12f, w * 0.5f, y)
                    cubicTo(w * 0.75f, y + h * 0.12f, w * 0.75f, y + h * 0.12f, w, y)
                }
                drawPath(path, color, style = stroke)
            }
        }
        "dots" -> for (c in 0 until 5) for (r in 0 until 5) {
            drawCircle(color, radius = w * 0.04f, center = Offset(w * (0.12f + c * 0.19f), h * (0.12f + r * 0.19f)))
        }
        "blobs" -> {
            val path = Path().apply {
                moveTo(w * 0.55f, h * 0.05f)
                cubicTo(w * 1.05f, h * 0.0f, w * 1.0f, h * 0.55f, w * 0.7f, h * 0.55f)
                cubicTo(w * 0.4f, h * 0.55f, w * 0.2f, h * 0.15f, w * 0.55f, h * 0.05f)
                close()
            }
            drawPath(path, color)
            drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.2f, h * 0.82f))
        }
        "lines" -> {
            for (i in 0 until 6) {
                val x = w * (i * 0.25f - 0.2f)
                drawLine(color, Offset(x, h), Offset(x + w * 0.6f, 0f), strokeWidth = h * 0.04f)
            }
        }
        "rings" -> for (k in 1..4) {
            drawCircle(color, radius = w * 0.25f * k, center = Offset(w * 0.15f, h * 0.85f), style = Stroke(w * 0.03f))
        }
        "grid" -> {
            for (i in 1..4) drawLine(color, Offset(w * i / 5f, 0f), Offset(w * i / 5f, h), strokeWidth = w * 0.015f)
            for (j in 1..7) drawLine(color, Offset(0f, h * j / 8f), Offset(w, h * j / 8f), strokeWidth = w * 0.015f)
        }
        "checker" -> {
            val side = w / 5f
            for (c in 0 until 5) for (r in 0 until 8) {
                if ((c + r) % 2 == 0) drawRect(color, Offset(c * side, r * h / 8f), Size(side, h / 8f))
            }
        }
        "zigzag" -> for (k in 0 until 5) {
            val y = h * (0.15f + k * 0.18f)
            val path = Path().apply {
                moveTo(0f, y)
                for (i in 1..4) lineTo(w * i / 4f, if (i % 2 == 0) y else y + h * 0.06f)
            }
            drawPath(path, color, style = Stroke(h * 0.025f))
        }
        "confetti" -> CONFETTI.forEach { (cx, cy) ->
            val t = w * 0.07f
            val path = Path().apply {
                moveTo(w * cx, h * cy - t)
                lineTo(w * cx + t, h * cy + t)
                lineTo(w * cx - t, h * cy + t)
                close()
            }
            drawPath(path, color)
        }
        "contours" -> {
            for (k in 1..4) contour(color, Offset(w * 0.7f, h * 0.35f), Size(w * 0.15f * k, h * 0.08f * k))
            for (k in 1..3) contour(color, Offset(w * 0.2f, h * 0.8f), Size(w * 0.15f * k, h * 0.08f * k))
        }
        "halftone" -> for (c in 0 until 6) for (r in 0 until 10) {
            drawCircle(color, radius = w * (0.01f + r * 0.007f), center = Offset(w * (0.08f + c * 0.168f), h * (0.05f + r * 0.1f)))
        }
        "sparkles" -> SPARKLES.forEach { (cx, cy, size) ->
            val x = w * cx
            val y = h * cy
            val t = w * size
            val path = Path().apply {
                moveTo(x, y - t)
                quadraticTo(x, y, x + t, y)
                quadraticTo(x, y, x, y + t)
                quadraticTo(x, y, x - t, y)
                quadraticTo(x, y, x, y - t)
                close()
            }
            drawPath(path, color)
        }
    }
}

// Mirrored in odin-js cards/parts/Background.tsx.
private val CONFETTI = listOf(0.15f to 0.1f, 0.7f to 0.18f, 0.4f to 0.32f, 0.85f to 0.45f, 0.2f to 0.55f, 0.6f to 0.68f, 0.3f to 0.85f, 0.8f to 0.9f)
private val SPARKLES = listOf(
    Triple(0.2f, 0.15f, 0.08f),
    Triple(0.75f, 0.3f, 0.12f),
    Triple(0.35f, 0.55f, 0.06f),
    Triple(0.8f, 0.7f, 0.09f),
    Triple(0.15f, 0.85f, 0.1f),
)

private fun DrawScope.contour(color: Color, center: Offset, radii: Size) =
    drawOval(color, Offset(center.x - radii.width, center.y - radii.height), Size(radii.width * 2, radii.height * 2), style = Stroke(size.width * 0.02f))

@Composable
internal fun BackgroundTile(
    id: String?,
    selected: Boolean,
    enabled: Boolean,
    ground: Color,
    ink: Color,
    description: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val interactions = remember { MutableInteractionSource() }
    Canvas(
        modifier = Modifier
            .size(width = 56.dp, height = 72.dp)
            .clip(shape)
            .border(if (selected) 2.5.dp else 1.dp, ring, shape)
            .selectable(selected, interactions, LocalIndication.current, enabled, Role.RadioButton, onClick)
            .semantics { contentDescription = description },
    ) {
        drawRect(ground)
        when (id) {
            null -> {
                drawCircle(ink.copy(alpha = 0.12f), radius = size.width * 0.3f, center = Offset(size.width * 0.5f, size.height * 0.5f))
                drawCircle(ink.copy(alpha = 0.5f), radius = size.width * 0.1f, center = Offset(size.width * 0.5f, size.height * 0.5f))
            }
            CardBackgrounds.NONE -> drawLine(ink.copy(alpha = 0.5f), Offset(size.width * 0.2f, size.height * 0.8f), Offset(size.width * 0.8f, size.height * 0.2f), strokeWidth = 3f)
            else -> backgroundPattern(id, ink.copy(alpha = 0.22f))
        }
    }
}
