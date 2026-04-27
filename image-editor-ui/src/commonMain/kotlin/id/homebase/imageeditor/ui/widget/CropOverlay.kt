package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.homebase.imageeditor.ui.MatrixSnapshot
import kotlin.math.max
import kotlin.math.min

/**
 * Visual elements painted on top of the image:
 *   - dim mask outside the crop frame
 *   - white border around the crop frame
 *   - corner thumb handles
 *   - rule-of-thirds grid (only while gesturing)
 */
@Composable
fun CropOverlay(
    snapshot: MatrixSnapshot,
    showGrid: Boolean,
    modifier: Modifier = Modifier,
    thumbSize: Dp = THUMB_VISIBLE_SIZE,
    cropStrokeWidth: Dp = 2.dp,
    dimColor: Color = Color.Black.copy(alpha = 0.55f),
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // Map the crop rect from view-space to canvas pixels via view.localMatrix.
        val cm = snapshot.viewLocal
        val ptsView = floatArrayOf(
            snapshot.cropRect.left, snapshot.cropRect.top,
            snapshot.cropRect.right, snapshot.cropRect.top,
            snapshot.cropRect.right, snapshot.cropRect.bottom,
            snapshot.cropRect.left, snapshot.cropRect.bottom,
        )
        val ptsCanvas = FloatArray(8)
        cm.mapPoints(ptsCanvas, ptsView, count = 4)
        val left = min(ptsCanvas[0], ptsCanvas[6])
        val right = max(ptsCanvas[2], ptsCanvas[4])
        val top = min(ptsCanvas[1], ptsCanvas[3])
        val bottom = max(ptsCanvas[5], ptsCanvas[7])
        val cropRect = Rect(left, top, right, bottom)

        // 1. Dim mask outside the crop. Even-odd fill rule punches a hole.
        val maskPath = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, this@Canvas.size))
            addRect(cropRect)
        }
        drawPath(maskPath, color = dimColor)

        // 2. Crop frame border.
        drawRect(
            color = Color.White,
            topLeft = cropRect.topLeft,
            size = Size(cropRect.width, cropRect.height),
            style = Stroke(width = cropStrokeWidth.toPx()),
        )

        // 3. Optional rule-of-thirds grid.
        if (showGrid) {
            val gridColor = Color.White.copy(alpha = 0.35f)
            val third = 1f / 3f
            val gw = cropRect.width * third
            val gh = cropRect.height * third
            for (i in 1..2) {
                drawLine(
                    color = gridColor,
                    start = Offset(cropRect.left + gw * i, cropRect.top),
                    end = Offset(cropRect.left + gw * i, cropRect.bottom),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = gridColor,
                    start = Offset(cropRect.left, cropRect.top + gh * i),
                    end = Offset(cropRect.right, cropRect.top + gh * i),
                    strokeWidth = 1f,
                )
            }
        }

        // 4. Corner thumbs.
        val thumb = thumbSize.toPx()
        val thumbStroke = thumbSize.toPx() * 0.18f
        val corners = listOf(
            cropRect.topLeft to Offset(1f, 1f),
            Offset(cropRect.right, cropRect.top) to Offset(-1f, 1f),
            Offset(cropRect.left, cropRect.bottom) to Offset(1f, -1f),
            Offset(cropRect.right, cropRect.bottom) to Offset(-1f, -1f),
        )
        for ((corner, dir) in corners) {
            // L-shape: a horizontal stub and a vertical stub from the corner inward.
            drawLine(
                color = Color.White,
                start = corner,
                end = Offset(corner.x + dir.x * thumb, corner.y),
                strokeWidth = thumbStroke,
            )
            drawLine(
                color = Color.White,
                start = corner,
                end = Offset(corner.x, corner.y + dir.y * thumb),
                strokeWidth = thumbStroke,
            )
        }
    }
}

/** Visible length of the L-shaped corner thumb. */
internal val THUMB_VISIBLE_SIZE: Dp = 22.dp

/** Hit-test radius around each corner, in dp. */
internal val THUMB_HIT_RADIUS: Dp = 36.dp
