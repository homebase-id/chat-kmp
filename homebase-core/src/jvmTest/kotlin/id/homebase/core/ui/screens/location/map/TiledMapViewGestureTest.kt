package id.homebase.core.ui.screens.location.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TiledMapViewGestureTest {

    @Test
    fun `a single-finger pan leaves a finite viewport`() = runComposeUiTest {
        val camera = MapCameraState()
        setContent {
            Box(Modifier.size(300.dp).testTag("map")) {
                TiledMapView(
                    bbox = doubleArrayOf(0.2, 0.2, 0.4, 0.4),
                    showMapTiles = false,
                    fetchTile = { _, _, _ -> null },
                    cameraState = camera,
                )
            }
        }
        waitForIdle()
        val before = assertNotNull(camera.centerUnit)

        onNodeWithTag("map").performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(-40f, 0f)) }
            up()
        }
        waitForIdle()

        val (x, y) = assertNotNull(camera.centerUnit)
        assertFalse(x.isNaN() || y.isNaN(), "center went NaN: ($x, $y)")
        assertTrue(x > before.first, "dragging left should move the center east")
    }

    @Test
    fun `a re-fit glide keeps every tile fetched on the way`() = runComposeUiTest {
        var bbox by mutableStateOf(doubleArrayOf(0.2, 0.2, 0.2, 0.2))
        var key by mutableStateOf(1)
        val fetches = mutableMapOf<MapTileKey, Int>()
        val network = CompletableDeferred<Unit>().apply { complete(Unit) }
        var slowNetwork = network
        setContent {
            Box(Modifier.size(300.dp).testTag("map")) {
                TiledMapView(
                    bbox = bbox,
                    showMapTiles = true,
                    fetchTile = { z, x, y ->
                        val k = MapTileKey(z, x, y)
                        fetches[k] = (fetches[k] ?: 0) + 1
                        slowNetwork.await()
                        redTile
                    },
                    resetViewportOn = key,
                )
            }
        }
        settle()
        assertCenterPixel(Color.Red)

        // Tiles stay in flight for the whole glide, as they do on a phone's network.
        slowNetwork = CompletableDeferred()
        bbox = doubleArrayOf(0.200016, 0.2, 0.200016, 0.2)
        key = 2
        settle()
        slowNetwork.complete(Unit)
        settle()

        assertCenterPixel(Color.Red)
        assertEquals(emptyMap(), fetches.filterValues { it > 1 }, "tiles fetched again because their first result was dropped")
    }

    private fun ComposeUiTest.settle() {
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
    }

    private fun ComposeUiTest.assertCenterPixel(expected: Color) {
        val pixels = onNodeWithTag("map").captureToImage().toPixelMap()
        assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2])
    }

    private val redTile: ByteArray by lazy {
        val bitmap = Bitmap().apply { allocN32Pixels(256, 256) }
        Canvas(bitmap).drawPaint(Paint().apply { color = org.jetbrains.skia.Color.RED })
        Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}
