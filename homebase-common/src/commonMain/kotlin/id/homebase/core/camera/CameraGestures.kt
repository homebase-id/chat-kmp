package id.homebase.core.camera

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

internal interface PreviewGestureHandler {
    fun onPinch(zoom: Float)
    fun onPinchEnd()
    fun canDragMode(): Boolean
    fun onModeDragStart()
    fun onModeDrag(deltaX: Float, slotPx: Float)
    fun onModeDragEnd(velocityX: Float, slotPx: Float)
    fun canDragExposure(): Boolean
    fun onExposureDrag(deltaY: Float)
}

private enum class PreviewDrag { Undecided, Pinch, Mode, Exposure }

/**
 * Two pointers pinch-zoom. One pointer travelling mostly sideways drags the mode carousel; mostly vertically, the
 * exposure while a focus point is up. Runs on the Initial pass and consumes only once it has decided, so the
 * preview's own tap and long-press focus still see plain taps.
 */
internal suspend fun PointerInputScope.detectPreviewGestures(handler: PreviewGestureHandler) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slotPx = size.width * PREVIEW_SLOT_FRACTION
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var kind = PreviewDrag.Undecided
        var last = down.position
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    if (kind == PreviewDrag.Mode) handler.onModeDragEnd(0f, slotPx)
                    kind = PreviewDrag.Pinch
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) handler.onPinch(zoom)
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (kind == PreviewDrag.Pinch) {
                    event.changes.forEach { it.consume() }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                tracker.addPosition(change.uptimeMillis, change.position)
                val total = change.position - down.position
                when (kind) {
                    PreviewDrag.Undecided -> when {
                        abs(total.x) > slop && abs(total.x) > abs(total.y) * DOMINANCE && handler.canDragMode() -> {
                            kind = PreviewDrag.Mode
                            handler.onModeDragStart()
                            last = change.position
                            change.consume()
                        }
                        abs(total.y) > slop && abs(total.y) > abs(total.x) * DOMINANCE && handler.canDragExposure() -> {
                            kind = PreviewDrag.Exposure
                            last = change.position
                            change.consume()
                        }
                    }
                    PreviewDrag.Mode -> {
                        handler.onModeDrag(change.position.x - last.x, slotPx)
                        last = change.position
                        change.consume()
                    }
                    PreviewDrag.Exposure -> {
                        handler.onExposureDrag(change.position.y - last.y)
                        last = change.position
                        change.consume()
                    }
                    PreviewDrag.Pinch -> Unit
                }
            }
        } finally {
            when (kind) {
                PreviewDrag.Mode -> handler.onModeDragEnd(tracker.calculateVelocity().x, slotPx)
                PreviewDrag.Pinch -> handler.onPinchEnd()
                else -> Unit
            }
        }
    }
}

private const val DOMINANCE = 2f
private const val PREVIEW_SLOT_FRACTION = 0.4f

/** Observes without consuming, so the preview's own single-tap focus still runs underneath. */
internal suspend fun PointerInputScope.detectDoubleTapObserving(onDoubleTap: () -> Unit) {
    var lastUpMs = 0L
    var lastUp = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var multiTouch = false
        var up: Offset? = null
        var upMs = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) multiTouch = true
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                up = change.position
                upMs = change.uptimeMillis
                break
            }
        }
        val released = up ?: return@awaitEachGesture
        val tapped = !multiTouch && upMs - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
            (released - down.position).getDistance() < viewConfiguration.touchSlop
        if (!tapped) {
            lastUpMs = 0L
            return@awaitEachGesture
        }
        val isSecond = lastUpMs != 0L &&
            down.uptimeMillis - lastUpMs < viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastUp).getDistance() < viewConfiguration.touchSlop * 4
        if (isSecond) {
            lastUpMs = 0L
            onDoubleTap()
        } else {
            lastUpMs = upMs
            lastUp = released
        }
    }
}
