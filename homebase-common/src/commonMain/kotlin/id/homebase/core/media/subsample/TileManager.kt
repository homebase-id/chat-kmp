package id.homebase.core.media.subsample

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TileManager(
    private val decoder: ImageRegionDecoder,
    private val tileSize: Int = 512,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        TileState(imageSize = IntSize(decoder.imageWidth, decoder.imageHeight))
    )
    val state: StateFlow<TileState> = _state.asStateFlow()

    private val activeJobs = mutableMapOf<TileKey, Job>()

    fun loadBaseLayer() {
        scope.launch {
            val baseSample = TileGrid.sampleSizeForZoom(1f).coerceAtLeast(4)
            val bitmap = decoder.decodeRegion(
                IntRect(0, 0, decoder.imageWidth, decoder.imageHeight),
                baseSample,
            )
            _state.update { it.copy(baseLayer = bitmap) }
        }
    }

    fun onViewportChanged(viewport: IntRect, zoom: Float) {
        val sampleSize = TileGrid.sampleSizeForZoom(zoom)
        val needed = TileGrid.visibleTiles(
            viewport = viewport,
            imageSize = IntSize(decoder.imageWidth, decoder.imageHeight),
            tileSize = tileSize,
            sampleSize = sampleSize,
        ).toSet()

        val toCancel = activeJobs.keys - needed
        for (key in toCancel) {
            activeJobs.remove(key)?.cancel()
        }

        _state.update { current ->
            current.copy(tiles = current.tiles.filterKeys { it in needed })
        }

        for (key in needed) {
            if (key in _state.value.tiles || key in activeJobs) continue
            activeJobs[key] = scope.launch {
                try {
                    val rect = TileGrid.tileRect(key.col, key.row, tileSize, decoder.imageWidth, decoder.imageHeight)
                    val bitmap = decoder.decodeRegion(rect, key.sampleSize)
                    _state.update { it.copy(tiles = it.tiles + (key to DecodedTile(key, bitmap, rect))) }
                } catch (_: Exception) {
                    // Tile decode failed — base layer remains visible
                } finally {
                    activeJobs.remove(key)
                }
            }
        }
    }

    fun close() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        try { decoder.close() } catch (_: Exception) {}
    }
}
