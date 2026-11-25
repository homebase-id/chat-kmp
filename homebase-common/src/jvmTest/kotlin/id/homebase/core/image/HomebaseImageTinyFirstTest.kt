package id.homebase.core.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.ImageRequest
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.Base64Bytes
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.util.Collections
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

// THUMBNAILS.md, "Showing the images": a view draws the tiny on its first frame, before any download, then replaces it.
@OptIn(ExperimentalTestApi::class)
class HomebaseImageTinyFirstTest {

    private val tinyColor = Color(0xFF3366CC)
    private val thumbColor = Color(0xFFCC3333)
    private val fullColor = Color(0xFF33CC33)
    private val placeholderColor = Color(0xFF000000)

    private val fullFetches: MutableList<HomebaseImageData> = Collections.synchronizedList(mutableListOf())

    private fun loader(fetch: suspend (HomebaseImageData) -> ImageFetchResult) = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            add(HomebaseImageKeyer())
            add(Fetcher.Factory<HomebaseImageData> { data, _, _ -> Fetcher { fetch(data) } })
        }
        .build()

    private val neverLoads = loader { awaitCancellation() }

    private fun solid(color: Color): ImageFetchResult {
        val bitmap = Bitmap().apply { allocN32Pixels(20, 15) }
        bitmap.erase(color.toArgb())
        return ImageFetchResult(bitmap.asImage(), isSampled = false, dataSource = DataSource.NETWORK)
    }

    private fun png(color: Color): ByteArray {
        val surface = Surface.makeRasterN32Premul(20, 15)
        surface.canvas.clear(color.toArgb())
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun tiny() =
        EmbeddedThumb(pixelWidth = 20, pixelHeight = 15, contentType = "image/png", content = Base64Bytes(png(tinyColor)))

    private fun photo(tiny: EmbeddedThumb?) = HomebaseImageData(
        driveId = Uuid.random(),
        fileId = Uuid.random(),
        payloadKey = "k",
        previewThumbnail = tiny,
        availableThumbSizes = listOf(ImageSize(320, 240)),
        keyHeader = KeyHeader.newRandom16(),
    )

    // A 20 px thumbnail can't cover a 100 px view, so the view steps up to the recorded 2000 px final image.
    private fun steppingUpPhoto() = photo(tiny()).copy(
        availableThumbSizes = listOf(ImageSize(20, 15)),
        finalImageSize = ImageSize(2000, 1500),
        payloadBytes = 1000,
        payloadContentType = "image/png",
    )

    private fun ComposeUiTest.show(
        loader: ImageLoader,
        data: HomebaseImageData,
        deferLoad: Boolean = false,
        standIn: String? = null,
        onComposed: () -> Unit = {},
    ) {
        val koin = koinApplication { modules(module { single<ImageLoader> { loader } }) }
        setContent {
            KoinIsolatedContext(koin) {
                MaterialTheme {
                    SideEffect(onComposed)
                    HomebaseImage(
                        imageData = data,
                        modifier = Modifier.size(100.dp).testTag("photo"),
                        placeholder = { Box(Modifier.fillMaxSize().background(placeholderColor).testTag("empty")) },
                        error = { Box(Modifier.testTag("error")) },
                        deferLoad = deferLoad,
                        standIn = standIn,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.awaitCentre(color: Color) = waitUntil(timeoutMillis = 5_000) { centre().isNear(color) }

    private fun ComposeUiTest.centre(): Color =
        onNodeWithTag("photo").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }

    @Test
    fun theTinyIsOnTheFirstFrame() = runSkikoComposeUiTest {
        mainClock.autoAdvance = false
        show(neverLoads, photo(tiny()))
        assertTrue(centre().isNear(tinyColor), "expected the tiny's colour, got ${centre()}")
        onNodeWithTag("empty").assertDoesNotExist()
    }

    @Test
    fun theThumbnailReplacesTheTiny() = runSkikoComposeUiTest {
        show(loader { solid(thumbColor) }, photo(tiny()))
        awaitCentre(thumbColor)
        onNodeWithTag("error").assertDoesNotExist()
    }

    @Test
    fun aFailedThumbnailKeepsTheTinyUnderTheError() = runSkikoComposeUiTest {
        show(loader { error("offline") }, photo(tiny()))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag("error")).fetchSemanticsNodes().isNotEmpty() }
        awaitCentre(tinyColor)
    }

    @Test
    fun theFinalImageReplacesTheThumbnailAfterTheStepUp() = runSkikoComposeUiTest {
        show(loader { if (it.loadFullPayload) solid(fullColor) else solid(thumbColor) }, steppingUpPhoto())
        awaitCentre(fullColor)
    }

    @Test
    fun aFailedStepUpKeepsTheThumbnailWithoutAnError() = runSkikoComposeUiTest {
        show(
            loader {
                if (it.loadFullPayload) {
                    fullFetches += it
                    error("offline")
                }
                solid(thumbColor)
            },
            steppingUpPhoto(),
        )
        waitUntil(timeoutMillis = 5_000) { fullFetches.isNotEmpty() }
        waitForIdle()
        assertTrue(centre().isNear(thumbColor), "expected the thumbnail's colour, got ${centre()}")
        onNodeWithTag("error").assertDoesNotExist()
        assertEquals(1, fullFetches.size, "a failed step-up should not retry")
    }

    @Test
    fun aThumbnailInMemoryIsOnTheFirstFrame() = runSkikoComposeUiTest {
        var warm = true
        val loader = loader { if (warm) solid(thumbColor) else awaitCancellation() }
        val data = photo(tiny())
        runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(data).build()) }
        warm = false
        mainClock.autoAdvance = false
        show(loader, data)
        assertTrue(centre().isNear(thumbColor), "expected the thumbnail's colour, got ${centre()}")
    }

    @Test
    fun aDeferredViewShowsTheTinyAndFetchesNothing() = runSkikoComposeUiTest {
        var fetches = 0
        show(loader { fetches++; solid(thumbColor) }, photo(tiny()), deferLoad = true)
        awaitCentre(tinyColor)
        waitForIdle()
        assertEquals(0, fetches)
    }

    @Test
    fun theSendersOwnFileStandsInWhileTheSentCopyLoads() = runSkikoComposeUiTest {
        val file = File.createTempFile("stand-in", ".png").apply { deleteOnExit() }
        file.writeBytes(png(fullColor))
        show(neverLoads, photo(tiny()), standIn = file.absolutePath)
        awaitCentre(fullColor)
    }

    @Test
    fun anUnreadableStandInGivesWayToTheTiny() = runSkikoComposeUiTest {
        show(neverLoads, photo(tiny()), standIn = "/nonexistent/stand-in.png")
        awaitCentre(tinyColor)
    }

    @Test
    fun withoutATinyThePlaceholderShows() = runSkikoComposeUiTest {
        mainClock.autoAdvance = false
        show(neverLoads, photo(tiny = null))
        onNodeWithTag("empty").assertExists()
    }

    private fun Color.isNear(other: Color) =
        abs(red - other.red) < 0.05f && abs(green - other.green) < 0.05f && abs(blue - other.blue) < 0.05f
}
