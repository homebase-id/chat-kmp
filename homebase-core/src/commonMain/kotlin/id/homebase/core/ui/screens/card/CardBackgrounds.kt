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
import org.jetbrains.compose.resources.StringResource

object CardBackgrounds {
    const val NONE = "none"
    private val SHARED = listOf("dots", "waves", "lines")
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
    }
}

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
