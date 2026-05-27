package id.homebase.core.media.subsample

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

data class TileKey(val col: Int, val row: Int, val sampleSize: Int)

data class DecodedTile(
    val key: TileKey,
    val bitmap: ImageBitmap,
    val srcRect: IntRect,
)

data class TileState(
    val tiles: Map<TileKey, DecodedTile> = emptyMap(),
    val baseLayer: ImageBitmap? = null,
    val imageSize: IntSize = IntSize.Zero,
)
