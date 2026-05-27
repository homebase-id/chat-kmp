package id.homebase.core.media.subsample

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TileManagerTest {

    private fun createTestDecoder(): ImageRegionDecoder {
        return createImageRegionDecoder(createTestJpeg(2048, 2048))
    }

    @Test
    fun `loads base layer on init`() = runTest {
        val decoder = createTestDecoder()
        val manager = TileManager(decoder, tileSize = 512, scope = this)
        manager.loadBaseLayer()
        advanceUntilIdle()
        assertNotNull(manager.state.value.baseLayer)
        manager.close()
    }

    @Test
    fun `loads visible tiles on viewport change`() = runTest {
        val decoder = createTestDecoder()
        val manager = TileManager(decoder, tileSize = 512, scope = this)
        manager.onViewportChanged(IntRect(0, 0, 512, 512), zoom = 5f)
        advanceUntilIdle()
        assertTrue(manager.state.value.tiles.isNotEmpty())
        manager.close()
    }

    @Test
    fun `evicts tiles when viewport moves`() = runTest {
        val decoder = createTestDecoder()
        val manager = TileManager(decoder, tileSize = 512, scope = this)
        manager.onViewportChanged(IntRect(0, 0, 512, 512), zoom = 5f)
        advanceUntilIdle()
        val firstTiles = manager.state.value.tiles.keys.toSet()
        manager.onViewportChanged(IntRect(1536, 1536, 2048, 2048), zoom = 5f)
        advanceUntilIdle()
        val secondTiles = manager.state.value.tiles.keys.toSet()
        assertTrue((firstTiles - secondTiles).isNotEmpty(), "Old tiles should be evicted")
        manager.close()
    }

    @Test
    fun `reports correct image size`() = runTest {
        val decoder = createTestDecoder()
        val manager = TileManager(decoder, tileSize = 512, scope = this)
        assertEquals(IntSize(2048, 2048), manager.state.value.imageSize)
        manager.close()
    }
}
