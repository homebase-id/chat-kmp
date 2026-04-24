package id.homebase.core.ui.screens.defragmenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.floor
import kotlin.math.max

val Win98FontSize = 12.sp
val Win98Font = FontFamily.Monospace

private val Win98TextStyle = TextStyle(
    fontFamily = Win98Font,
    fontSize = Win98FontSize,
    color = Win98Palette.Black,
)

/**
 * Outer Win98 window: 2px raised bevel, gray face, title bar with a close "X".
 */
@Composable
fun Win98WindowFrame(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .drawBehind { drawBevel(size = size, raised = true) }
            .padding(2.dp)
            .background(Win98Palette.GrayFace),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            Win98TitleBar(title = title, onClose = onClose)
            content()
        }
    }
}

@Composable
private fun Win98TitleBar(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Win98Palette.Navy, Win98Palette.NavyLight)
                )
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = Win98TextStyle.copy(
                color = Win98Palette.White,
                fontWeight = FontWeight.Bold,
            ),
        )
        Win98CloseButton(onClose)
    }
}

@Composable
private fun Win98CloseButton(onClose: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(width = 20.dp, height = 16.dp)
            .drawBehind {
                drawBevel(size = size, raised = !pressed)
            }
            .background(Win98Palette.GrayFace)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "X",
            style = Win98TextStyle.copy(
                fontWeight = FontWeight.Bold,
                color = Win98Palette.Black,
            ),
            modifier = Modifier.padding(bottom = 1.dp)
        )
    }
}

/**
 * Beveled panel — raised or sunken. Used to frame the grid (sunken) and the
 * stats / progress areas (raised).
 */
@Composable
fun Win98BeveledPanel(
    modifier: Modifier = Modifier,
    sunken: Boolean = false,
    background: Color = Win98Palette.GrayFace,
    content: @Composable androidx.compose.foundation.layout.BoxWithConstraintsScope.() -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .drawBehind { drawBevel(size = size, raised = !sunken) }
            .padding(2.dp)
            .background(background),
    ) {
        content()
    }
}

@Composable
fun Win98Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val raised = !(pressed && enabled)
    val faceColor = if (enabled) Win98Palette.GrayFace else Win98Palette.GrayFace
    val textColor = if (enabled) Win98Palette.Black else Win98Palette.ShadowDark
    Box(
        modifier = modifier
            .height(28.dp)
            .drawBehind { drawBevel(size = size, raised = raised) }
            .padding(2.dp)
            .background(faceColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Win98TextStyle.copy(color = textColor),
        )
    }
}

/**
 * Segmented teal progress bar — the exact aesthetic of defrag.exe.
 */
@Composable
fun Win98ProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Win98BeveledPanel(
        modifier = modifier.height(22.dp),
        sunken = true,
        background = Win98Palette.GrayFace,
    ) {
        val widthDp = maxWidth
        Row(
            modifier = Modifier.fillMaxSize().padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val segmentWidth = 10.dp
            val segmentCount = max(1, floor(widthDp.value / (segmentWidth.value + 2)).toInt())
            val filled = (clamped * segmentCount).toInt()
            for (i in 0 until segmentCount) {
                Box(
                    modifier = Modifier
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .background(
                            if (i < filled) Win98Palette.Teal else Win98Palette.GrayFace
                        ),
                )
            }
        }
    }
}

/**
 * Draws the 2-px raised/sunken bevel border used throughout Win98 chrome.
 * Raised: light on top+left, dark on bottom+right. Sunken: inverted.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBevel(
    size: Size,
    raised: Boolean,
) {
    val outerLight = if (raised) Win98Palette.HighlightLight else Win98Palette.ShadowDarker
    val innerLight = if (raised) Win98Palette.HighlightSoft else Win98Palette.ShadowDark
    val outerDark = if (raised) Win98Palette.ShadowDarker else Win98Palette.HighlightLight
    val innerDark = if (raised) Win98Palette.ShadowDark else Win98Palette.HighlightSoft
    val w = size.width
    val h = size.height
    // Outer edge
    drawLine(outerLight, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1f)
    drawLine(outerLight, Offset(0f, 0f), Offset(0f, h), strokeWidth = 1f)
    drawLine(outerDark, Offset(w - 1, 0f), Offset(w - 1, h), strokeWidth = 1f)
    drawLine(outerDark, Offset(0f, h - 1), Offset(w, h - 1), strokeWidth = 1f)
    // Inner edge
    drawLine(innerLight, Offset(1f, 1f), Offset(w - 1, 1f), strokeWidth = 1f)
    drawLine(innerLight, Offset(1f, 1f), Offset(1f, h - 1), strokeWidth = 1f)
    drawLine(innerDark, Offset(w - 2, 1f), Offset(w - 2, h - 1), strokeWidth = 1f)
    drawLine(innerDark, Offset(1f, h - 2), Offset(w - 1, h - 2), strokeWidth = 1f)
}
