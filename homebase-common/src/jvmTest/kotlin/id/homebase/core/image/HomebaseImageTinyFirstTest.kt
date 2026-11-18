package id.homebase.core.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.Fetcher
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.Base64Bytes
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// THUMBNAILS.md, "Showing the images": a view draws the tiny on its first frame, before any download.
@OptIn(ExperimentalTestApi::class)
class HomebaseImageTinyFirstTest {

    private val neverLoads = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components { add(Fetcher.Factory<HomebaseImageData> { _, _, _ -> Fetcher { awaitCancellation() } }) }
        .build()

    private val koin = koinApplication { modules(module { single<ImageLoader> { neverLoads } }) }

    private val tinyColor = Color(0xFF3366CC)

    private fun tiny(): EmbeddedThumb {
        val surface = Surface.makeRasterN32Premul(20, 15)
        surface.canvas.clear(0xFF3366CC.toInt())
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        return EmbeddedThumb(pixelWidth = 20, pixelHeight = 15, contentType = "image/png", content = Base64Bytes(png))
    }

    private fun photo(tiny: EmbeddedThumb?) = HomebaseImageData(
        driveId = Uuid.random(),
        fileId = Uuid.random(),
        payloadKey = "k",
        previewThumbnail = tiny,
        availableThumbSizes = listOf(ImageSize(320, 240)),
        keyHeader = KeyHeader.newRandom16(),
    )

    @Test
    fun theTinyIsOnTheFirstFrameWhileTheThumbnailLoads() = runSkikoComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    HomebaseImage(
                        imageData = photo(tiny()),
                        modifier = Modifier.size(100.dp).testTag("photo"),
                        placeholder = { Box(Modifier.testTag("empty")) },
                    )
                }
            }
        }
        onNodeWithTag("empty").assertDoesNotExist()
        val centre = onNodeWithTag("photo").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
        assertTrue(centre.isNear(tinyColor), "expected the tiny's colour, got $centre")
    }

    @Test
    fun withoutATinyThePlaceholderShows() = runSkikoComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    HomebaseImage(
                        imageData = photo(tiny = null),
                        modifier = Modifier.size(100.dp),
                        placeholder = { Box(Modifier.testTag("empty")) },
                    )
                }
            }
        }
        onNodeWithTag("empty").assertExists()
    }

    private fun Color.isNear(other: Color) =
        abs(red - other.red) < 0.05f && abs(green - other.green) < 0.05f && abs(blue - other.blue) < 0.05f
}
