package id.homebase.core.ui.screens.card

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// Compose scrolls a horizontal row only by a wheel's horizontal delta, which a plain mouse wheel never sends.
// Touch sends no scroll events, so phones are unaffected; at the row's end the wheel falls through to the page.
internal fun Modifier.wheelScrollsRow(state: ScrollableState): Modifier = pointerInput(state) {
    val step = WHEEL_STEP.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
            if (abs(delta.y) <= abs(delta.x)) continue
            if (state.dispatchRawDelta(delta.y * step) != 0f) event.changes.forEach { it.consume() }
        }
    }
}

internal fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

// What desktop Compose scrolls per wheel unit, so a row moves as far per notch as a list does.
private val WHEEL_STEP = 10.dp

// Compose marks keyboard focus only with a faint state layer, which a thumbnail or a filled button hides.
internal fun Modifier.focusRing(focused: Boolean, shape: Shape, color: Color, outset: Dp = 0.dp): Modifier =
    if (!focused) this else drawWithContent {
        drawContent()
        val width = FOCUS_RING_WIDTH.toPx()
        val shift = width / 2 - outset.toPx()
        val outline = shape.createOutline(Size(size.width - 2 * shift, size.height - 2 * shift), layoutDirection, this)
        translate(shift, shift) { drawOutline(outline, color, style = Stroke(width)) }
    }

internal val FOCUS_RING_WIDTH = 3.dp
