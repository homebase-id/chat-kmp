package id.homebase.core.media.subsample

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Composable
fun SubSamplingImage(
    tileState: TileState,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawBaseLayer(tileState)
        drawTiles(tileState)
    }
}

private fun DrawScope.drawBaseLayer(tileState: TileState) {
    val baseLayer = tileState.baseLayer ?: return
    drawImage(
        image = baseLayer,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.Low,
    )
}

private fun DrawScope.drawTiles(tileState: TileState) {
    if (tileState.imageSize == IntSize.Zero) return
    val scaleX = size.width / tileState.imageSize.width
    val scaleY = size.height / tileState.imageSize.height

    for ((_, tile) in tileState.tiles) {
        val dstLeft = (tile.srcRect.left * scaleX).toInt()
        val dstTop = (tile.srcRect.top * scaleY).toInt()
        val dstWidth = (tile.srcRect.width * scaleX).toInt()
        val dstHeight = (tile.srcRect.height * scaleY).toInt()

        drawImage(
            image = tile.bitmap,
            srcSize = IntSize(tile.bitmap.width, tile.bitmap.height),
            dstOffset = IntOffset(dstLeft, dstTop),
            dstSize = IntSize(dstWidth, dstHeight),
            filterQuality = FilterQuality.Medium,
        )
    }
}
