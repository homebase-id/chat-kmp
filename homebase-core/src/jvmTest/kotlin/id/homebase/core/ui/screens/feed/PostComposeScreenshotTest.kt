package id.homebase.core.ui.screens.feed

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostAudience
import id.homebase.core.ui.theme.HomebaseTheme
import io.github.vinceglb.filekit.PlatformFile
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import id.homebase.chat.image.PlatformFileFetcher
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class)
class PostComposeScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private fun photo(seed: Int): AttachmentPendingFile {
        val image = BufferedImage(480, 480, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        val hue = seed * 0.21f
        g.paint = GradientPaint(
            0f, 0f, java.awt.Color.getHSBColor(hue, 0.5f, 0.95f),
            0f, 480f, java.awt.Color.getHSBColor(hue + 0.1f, 0.8f, 0.4f),
        )
        g.fillRect(0, 0, 480, 480)
        g.color = java.awt.Color(255, 236, 190)
        g.fillOval(300, 80, 100, 100)
        g.dispose()
        val file = File.createTempFile("compose-photo-$seed", ".png").apply { deleteOnExit() }
        ImageIO.write(image, "png", file)
        return AttachmentPendingFile.FileImage(Uuid.random(), PlatformFile(file), sourceContentType = "image/png")
    }

    private val base = PostComposeUiState(
        channels = listOf(
            ChannelOption(FeedProtocol.PublicChannelDriveAlias, "Public Posts"),
            ChannelOption(Uuid.random(), "Shire Notes"),
        ),
        circles = listOf(
            CircleOption("fellowship", "The Fellowship"),
            CircleOption("hobbits", "Hobbiton neighbours"),
            CircleOption("family", "Baggins family"),
        ),
        selfOdinId = OdinId("frodo.baggins.demo.rocks"),
        selfName = "Frodo Baggins",
    )

    private val filled by lazy {
        base.copy(
            caption = "Back from the Prancing Pony. Second breakfast was a triumph, and the view over the Brandywine at dusk was worth every step.",
            attachments = listOf(photo(1), photo(2), photo(3)),
            audience = PostAudience.Circles,
            selectedCircleIds = setOf("fellowship", "family"),
        )
    }

    // The author's public avatar is a network fetch; failing it keeps every shot on the same initials.
    private val offline = Interceptor { chain ->
        if (chain.request.data.toString().startsWith("http")) {
            ErrorResult(null, chain.request, UnsupportedOperationException("offline"))
        } else {
            chain.proceed()
        }
    }

    @BeforeTest
    fun startDi() {
        startKoin {
            modules(
                module {
                    single { AvatarResolver(MutableStateFlow(emptyList())) }
                    single {
                        ImageLoader.Builder(PlatformContext.INSTANCE)
                            .components {
                                add(offline)
                                add(PlatformFileFetcher.Factory())
                            }
                            .build()
                    }
                },
            )
        }
    }

    @AfterTest
    fun stopDi() = stopKoin()

    private fun ComposeUiTest.show(state: PostComposeUiState, dark: Boolean) = setContent {
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            Surface {
                PostComposeContent(
                    state = state,
                    snackbarHostState = remember { SnackbarHostState() },
                    onClose = {},
                    onPost = {},
                    onCaptionChange = {},
                    onAddMedia = {},
                    onCamera = {},
                    onRemoveAttachment = {},
                    onSelectChannel = {},
                    onPickPublic = {},
                    onPickCircles = {},
                )
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        // Coil decodes off the main clock.
        repeat(4) {
            Thread.sleep(150)
            mainClock.advanceTimeBy(500)
            waitForIdle()
        }
        val roots = onAllNodes(isRoot())
        val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "compose-$name.png"))
    }

    private val widths = listOf("phone" to (411 to 820), "wide" to (1280 to 820))

    @Test
    fun rendersTheComposerAtPhoneAndWideWidthsInBothThemes() {
        for ((widthName, size) in widths) for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            runDesktopComposeUiTest(size.first, size.second) {
                show(base, dark)
                save("empty-$widthName-$theme")
            }
            runDesktopComposeUiTest(size.first, size.second) {
                show(filled, dark)
                save("filled-$widthName-$theme")
            }
            runDesktopComposeUiTest(size.first, size.second) {
                show(filled.copy(isPosting = true), dark)
                save("posting-$widthName-$theme")
            }
            runDesktopComposeUiTest(size.first, size.second) {
                show(filled, dark)
                onAllNodes(hasText("Circles (2)")).onFirst().performClick()
                waitForIdle()
                onAllNodes(hasText("Circles")).onFirst().performClick()
                waitForIdle()
                save("circles-$widthName-$theme")
            }
        }
    }
}
