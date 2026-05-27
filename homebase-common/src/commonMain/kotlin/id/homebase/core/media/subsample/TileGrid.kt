package id.homebase.core.media.subsample

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object TileGrid {

    fun sampleSizeForZoom(zoom: Float): Int = when {
        zoom >= 5f -> 1
        zoom >= 3f -> 2
        zoom >= 1.5f -> 4
        else -> 8
    }

    fun tileRect(col: Int, row: Int, tileSize: Int, imageWidth: Int, imageHeight: Int): IntRect {
        val left = col * tileSize
        val top = row * tileSize
        return IntRect(
            left = left,
            top = top,
            right = min(left + tileSize, imageWidth),
            bottom = min(top + tileSize, imageHeight),
        )
    }

    fun gridSize(imageWidth: Int, imageHeight: Int, tileSize: Int): IntSize {
        return IntSize(
            width = ceil(imageWidth.toFloat() / tileSize).toInt(),
            height = ceil(imageHeight.toFloat() / tileSize).toInt(),
        )
    }

    fun visibleTiles(
        viewport: IntRect,
        imageSize: IntSize,
        tileSize: Int,
        sampleSize: Int,
    ): List<TileKey> {
        val grid = gridSize(imageSize.width, imageSize.height, tileSize)
        val startCol = max(0, viewport.left / tileSize - 1)
        val endCol = min(grid.width - 1, viewport.right / tileSize + 1)
        val startRow = max(0, viewport.top / tileSize - 1)
        val endRow = min(grid.height - 1, viewport.bottom / tileSize + 1)

        val tiles = mutableListOf<TileKey>()
        for (col in startCol..endCol) {
            for (row in startRow..endRow) {
                tiles.add(TileKey(col, row, sampleSize))
            }
        }
        return tiles
    }

    fun calculateViewport(
        scale: Float,
        offset: Offset,
        imageSize: IntSize,
    ): IntRect {
        val viewportWidth = (imageSize.width / scale).toInt()
        val viewportHeight = (imageSize.height / scale).toInt()
        val centerX = imageSize.width / 2 - (offset.x / scale).toInt()
        val centerY = imageSize.height / 2 - (offset.y / scale).toInt()
        return IntRect(
            left = (centerX - viewportWidth / 2).coerceAtLeast(0),
            top = (centerY - viewportHeight / 2).coerceAtLeast(0),
            right = (centerX + viewportWidth / 2).coerceAtMost(imageSize.width),
            bottom = (centerY + viewportHeight / 2).coerceAtMost(imageSize.height),
        )
    }
}
