package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import id.homebase.imageeditor.core.ControlPoint
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.PointF
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.core.session.EditSession
import id.homebase.imageeditor.ui.MatrixSnapshot
import kotlin.math.hypot

/**
 * Compose pointer modifier driving the cropper. First-pointer-down decides
 * whether the gesture targets a corner thumb or pans/zooms the main image —
 * the two modes never overlap.
 *
 * The [snapshotState] holder is read fresh at each gesture start so the
 * pointerInput key stays stable across recompositions; otherwise the gesture
 * loop would restart every time `snapshotMatrices()` fires (i.e., on every
 * gesture frame after the live-cropRect change), losing its mode mid-drag.
 */
fun Modifier.cropGestures(
    snapshotState: State<MatrixSnapshot>,
    thumbHitRadius: Dp,
    callbacks: CropGestureCallbacks,
    isShowingGridState: MutableState<Boolean>,
): Modifier = pointerInput(Unit) {
    val hitRadiusPx = thumbHitRadius.toPx()
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val firstPos = firstDown.position
        val snapshotAtStart = snapshotState.value
        val thumb = hitTestThumb(firstPos, snapshotAtStart, hitRadiusPx)

        isShowingGridState.value = true
        try {
            if (thumb != null) {
                handleThumbDrag(snapshotAtStart, thumb, firstDown.id, firstPos, callbacks)
            } else {
                handleImageTransform(firstDown.id, firstPos, callbacks)
            }
        } finally {
            isShowingGridState.value = false
        }
    }
}

@Composable
fun rememberCropGridState(): MutableState<Boolean> = remember { mutableStateOf(false) }

data class CropGestureCallbacks(
    val onThumbDragStart: (ControlPoint, screenPoint: PointF) -> EditSession?,
    val onThumbDragMove: (EditSession, screenPoint: PointF) -> Unit,
    val onThumbDragCommit: (EditSession) -> Unit,
    val onPanImage: (dx: Float, dy: Float) -> Unit,
    val onZoomImage: (scale: Float, centroidX: Float, centroidY: Float) -> Unit,
    val onCommitImage: () -> Unit,
)

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleThumbDrag(
    snapshot: MatrixSnapshot,
    thumb: ControlPoint,
    pointerId: PointerId,
    initialPos: Offset,
    callbacks: CropGestureCallbacks,
) {
    val session = callbacks.onThumbDragStart(thumb, PointF(initialPos.x, initialPos.y)) ?: return
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                break
            }
            callbacks.onThumbDragMove(session, PointF(change.position.x, change.position.y))
            if (change.positionChanged()) change.consume()
        }
    } finally {
        callbacks.onThumbDragCommit(session)
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.handleImageTransform(
    firstId: PointerId,
    firstPos: Offset,
    callbacks: CropGestureCallbacks,
) {
    val tracked = mutableMapOf<PointerId, Offset>()
    tracked[firstId] = firstPos
    var committed = false
    try {
        while (true) {
            val event = awaitPointerEvent()
            val active = event.changes.filter { it.pressed }
            if (active.isEmpty()) break

            if (active.size == 1) {
                val c = active[0]
                val prev = tracked[c.id]
                if (prev != null) {
                    val dx = c.position.x - prev.x
                    val dy = c.position.y - prev.y
                    if (dx != 0f || dy != 0f) callbacks.onPanImage(dx, dy)
                }
                tracked[c.id] = c.position
                if (c.positionChanged()) c.consume()
            } else if (active.size >= 2) {
                val a = active[0]
                val b = active[1]
                val prevA = tracked[a.id]
                val prevB = tracked[b.id]
                if (prevA != null && prevB != null) {
                    val prevDist = distance(prevA, prevB)
                    val newDist = distance(a.position, b.position)
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
                    if (dx != 0f || dy != 0f) callbacks.onPanImage(dx, dy)
                    if (zoom != 1f && zoom > 0f) callbacks.onZoomImage(zoom, centroid.x, centroid.y)
                }
                tracked[a.id] = a.position
                tracked[b.id] = b.position
                if (a.positionChanged()) a.consume()
                if (b.positionChanged()) b.consume()
            }

            val activeIds = active.map { it.id }.toSet()
            tracked.keys.retainAll(activeIds)
        }
    } finally {
        if (!committed) {
            committed = true
            callbacks.onCommitImage()
        }
    }
}

private fun distance(a: Offset, b: Offset): Float =
    hypot(a.x - b.x, a.y - b.y)

/**
 * Hit-test a screen-pixel point against the four corner thumbs of the crop.
 * Returns the matching [ControlPoint] or null.
 */
private fun hitTestThumb(
    screenPoint: Offset,
    snapshot: MatrixSnapshot,
    hitRadiusPx: Float,
): ControlPoint? {
    val canvasCorners = cropRectCornersInCanvas(snapshot.cropRect, snapshot.viewLocal)
    val candidates = listOf(
        ControlPoint.TOP_LEFT to canvasCorners[0],
        ControlPoint.TOP_RIGHT to canvasCorners[1],
        ControlPoint.BOTTOM_RIGHT to canvasCorners[2],
        ControlPoint.BOTTOM_LEFT to canvasCorners[3],
    )
    var best: ControlPoint? = null
    var bestDist = Float.MAX_VALUE
    for ((cp, corner) in candidates) {
        val d = distance(screenPoint, corner)
        if (d <= hitRadiusPx && d < bestDist) {
            best = cp
            bestDist = d
        }
    }
    return best
}

private fun cropRectCornersInCanvas(cropRect: RectF, viewLocal: Matrix2D): List<Offset> {
    val src = floatArrayOf(
        cropRect.left, cropRect.top,
        cropRect.right, cropRect.top,
        cropRect.right, cropRect.bottom,
        cropRect.left, cropRect.bottom,
    )
    val dst = FloatArray(8)
    viewLocal.mapPoints(dst, src, count = 4)
    return listOf(
        Offset(dst[0], dst[1]),
        Offset(dst[2], dst[3]),
        Offset(dst[4], dst[5]),
        Offset(dst[6], dst[7]),
    )
}

private fun PointerInputChange.positionChanged(): Boolean =
    position != previousPosition
