package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/**
 * Compose pointer modifier driving the draw editor.
 *
 * State machine:
 *   - 1 finger down → start a stroke; subsequent moves extend it.
 *   - 2nd finger down during a stroke → commit the in-flight stroke and
 *     switch to viewport pan/zoom for the rest of the gesture.
 *   - All fingers up → end the gesture. Multitouch never resumes drawing
 *     within the same gesture; user must lift fully to start a new stroke.
 */
fun Modifier.drawGestures(
    callbacks: DrawGestureCallbacks,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        var phase: Phase = Phase.Stroking(firstDown.id)
        callbacks.onStrokeStart(firstDown.position.x, firstDown.position.y)

        val tracked = mutableMapOf<PointerId, Offset>()
        tracked[firstDown.id] = firstDown.position

        try {
            while (true) {
                val event = awaitPointerEvent()
                val active = event.changes.filter { it.pressed }
                if (active.isEmpty()) break

                when (val cur = phase) {
                    is Phase.Stroking -> {
                        if (active.size >= 2) {
                            // Promote to pan/zoom; commit the stroke as-is so it
                            // becomes part of the rendered image.
                            callbacks.onStrokeCommit()
                            phase = Phase.Transforming
                            tracked.clear()
                            for (c in active) tracked[c.id] = c.position
                            for (c in active) if (c.positionChanged()) c.consume()
                        } else {
                            val change = active.firstOrNull { it.id == cur.pointerId }
                                ?: active.first().also { phase = Phase.Stroking(it.id) }
                            callbacks.onStrokeExtend(change.position.x, change.position.y)
                            tracked[change.id] = change.position
                            if (change.positionChanged()) change.consume()
                        }
                    }
                    Phase.Transforming -> handleTransformFrame(active, tracked, callbacks)
                }
            }
        } finally {
            when (phase) {
                is Phase.Stroking -> callbacks.onStrokeCommit()
                Phase.Transforming -> Unit // viewport edits aren't undoable, no commit
            }
        }
    }
}

private fun AwaitPointerEventScope.handleTransformFrame(
    active: List<PointerInputChange>,
    tracked: MutableMap<PointerId, Offset>,
    callbacks: DrawGestureCallbacks,
) {
    if (active.size == 1) {
        val c = active[0]
        val prev = tracked[c.id]
        if (prev != null) {
            val dx = c.position.x - prev.x
            val dy = c.position.y - prev.y
            if (dx != 0f || dy != 0f) callbacks.onPan(dx, dy)
        }
        tracked[c.id] = c.position
        if (c.positionChanged()) c.consume()
    } else {
        val a = active[0]
        val b = active[1]
        val prevA = tracked[a.id]
        val prevB = tracked[b.id]
        if (prevA != null && prevB != null) {
            val prevDist = hypot(prevA.x - prevB.x, prevA.y - prevB.y)
            val newDist = hypot(a.position.x - b.position.x, a.position.y - b.position.y)
            val zoom = if (prevDist > 0f) newDist / prevDist else 1f
            val centroid = Offset(
                (a.position.x + b.position.x) * 0.5f,
                (a.position.y + b.position.y) * 0.5f,
            )
            val prevCentroid = Offset(
                (prevA.x + prevB.x) * 0.5f,
                (prevA.y + prevB.y) * 0.5f,
            )
            val dx = centroid.x - prevCentroid.x
            val dy = centroid.y - prevCentroid.y
            if (dx != 0f || dy != 0f) callbacks.onPan(dx, dy)
            if (zoom != 1f && zoom > 0f) callbacks.onZoom(zoom, centroid.x, centroid.y)
        }
        tracked[a.id] = a.position
        tracked[b.id] = b.position
        if (a.positionChanged()) a.consume()
        if (b.positionChanged()) b.consume()
    }
    val activeIds = active.map { it.id }.toSet()
    tracked.keys.retainAll(activeIds)
}

private sealed interface Phase {
    data class Stroking(val pointerId: PointerId) : Phase
    data object Transforming : Phase
}

data class DrawGestureCallbacks(
    val onStrokeStart: (screenX: Float, screenY: Float) -> Unit,
    val onStrokeExtend: (screenX: Float, screenY: Float) -> Unit,
    val onStrokeCommit: () -> Unit,
    val onPan: (dx: Float, dy: Float) -> Unit,
    val onZoom: (scale: Float, centroidX: Float, centroidY: Float) -> Unit,
)

private fun PointerInputChange.positionChanged(): Boolean =
    position != previousPosition
