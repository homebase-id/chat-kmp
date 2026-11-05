package id.homebase.core.ui.screens.moments

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.runDesktopComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.VideoPrefetchDriveAccess
import id.homebase.api.video.VideoPreloadService
import id.homebase.api.video.VideoPreloader
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.Base64Bytes
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.api.client.eventbus.EventBus
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.moments.MomentsViewMode
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentsVideoSession
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image as SkiaImage
import sun.misc.Unsafe
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
class MomentsWideScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private enum class Fixture(val width: Int, val height: Int, val type: String?, val pages: Int = 1) {
        Portrait(800, 1000, "image/jpeg"),
        Text(0, 0, null),
        Tall(720, 1280, "image/jpeg"),
        Video(800, 1000, "video/mp4"),
        Landscape(1280, 720, "image/jpeg"),
        Album(1000, 1000, "image/jpeg", pages = 3),
    }

    private val scenes = HashMap<String, Bitmap>()

    private fun scene(key: String, width: Int, height: Int, seed: Int): Bitmap = scenes.getOrPut(key) {
        val awt = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = awt.createGraphics()
        val hue = seed * 0.17f % 1f
        g.paint = GradientPaint(
            0f, 0f, java.awt.Color.getHSBColor(hue, 0.55f, 0.95f),
            0f, height.toFloat(), java.awt.Color.getHSBColor(hue + 0.12f, 0.8f, 0.45f),
        )
        g.fillRect(0, 0, width, height)
        g.color = java.awt.Color(255, 240, 200)
        g.fillOval(width * 3 / 5, height / 6, width / 5, width / 5)
        val rng = Random(seed)
        for (layer in 0..2) {
            g.color = java.awt.Color.getHSBColor(hue + 0.5f, 0.45f, 0.42f - layer * 0.12f)
            val base = height * (5 + layer) / 8
            val xs = (0..8).map { width * it / 8 } + listOf(width, 0)
            val ys = (0..8).map { base - rng.nextInt(height / 6) } + listOf(height, height)
            g.fillPolygon(xs.toIntArray(), ys.toIntArray(), xs.size)
        }
        g.dispose()
        val png = ByteArrayOutputStream().also { ImageIO.write(awt, "png", it) }.toByteArray()
        Bitmap.makeFromImage(SkiaImage.makeFromEncoded(png))
    }

    private fun payload(fixture: Fixture, index: Int, page: Int) = PayloadDescriptor(
        key = "m${index}p$page",
        contentType = fixture.type,
        iv = Base64Bytes(ByteArray(16)),
        previewThumbnail = ThumbnailDescriptor(fixture.width, fixture.height, "image/png"),
    ).also { scene(it.key, fixture.width, fixture.height, index + page) }

    private fun moment(index: Int): MomentFeedItem {
        val fixture = Fixture.entries[(index - 1) % Fixture.entries.size]
        val atMs = 1_790_000_000_000L - index * 20 * 3_600_000L
        return MomentFeedItem(
            id = Uuid.random(),
            fileId = Uuid.random(),
            driveId = Uuid.random(),
            keyHeader = KeyHeader.empty(),
            payloads = if (fixture.type == null) emptyList() else List(fixture.pages) { payload(fixture, index, it) },
            description = "Moment $index on the ridge at first light",
            userDateMs = atMs,
            createdMs = atMs,
            previewThumbnail = null,
            reactionPreview = null,
            senderOdinId = null,
            originalAuthor = OdinId("frodo.baggins.demo.rocks"),
            source = null,
            recipients = emptyList(),
            commentsEnabled = true,
            versionTag = null,
            ownReactions = emptyList(),
        )
    }

    private val moments = (1..14).map(::moment)
    private val talkFirst = moments.sortedBy { it.payloads.isNotEmpty() }
    private val tallFirst = moments.sortedByDescending {
        it.payloads.singleOrNull()?.previewThumbnail?.pixelHeight == Fixture.Tall.height
    }

    private fun fixtureImages() = Interceptor { chain ->
        val key = (chain.request.data as? HomebaseImageData)?.payloadKey
        val bitmap = key?.let(scenes::get) ?: return@Interceptor ErrorResult(null, chain.request, IllegalStateException("no scene"))
        // A non-HomebaseImageData request keeps the zoom image off the tile generator.
        SuccessResult(
            image = bitmap.asImage(),
            request = chain.request.newBuilder().data(key).build(),
            dataSource = DataSource.MEMORY,
        )
    }

    @BeforeTest
    fun startDi() {
        startKoin { 
            modules(
                module {
                    single { MomentsVideoSession() }
                    single { UserPreferences(PreferencesSettings(Preferences.userRoot().node("momentsWideScreenshotTest"))) }
                    single<ImageLoader> {
                        ImageLoader.Builder(PlatformContext.INSTANCE).components { add(fixtureImages()) }.build()
                    }
                    single { AvatarResolver(MutableStateFlow(emptyList())) }
                    single { LocalAttachmentContextStore(EventBus(), CoroutineScope(Dispatchers.Unconfined)) }
                    single { unwiredImageLoader() }
                    single { VideoPreloader(unreachable<VideoPrefetchDriveAccess>(), unreachable<FileOperationsProvider>()) }
                    single { VideoPreloadService(get()) }
                },
            )
         }
    }

    @AfterTest
    fun stopDi() = stopKoin()

    private fun ComposeUiTest.show(
        items: List<MomentFeedItem>,
        viewMode: MomentsViewMode,
        dark: Boolean,
        onDetail: (Uuid) -> Unit = {},
    ) = showLive(mutableStateOf(items), viewMode, dark, onDetail)

    private fun ComposeUiTest.showLive(
        source: MutableState<List<MomentFeedItem>>,
        viewMode: MomentsViewMode,
        dark: Boolean,
        onDetail: (Uuid) -> Unit = {},
        width: State<Dp>? = null,
        onFeedPaneWidthChange: (Dp) -> Unit = {},
    ) = setContent {
        val items = source.value
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            Surface(modifier = if (width != null) Modifier.width(width.value) else Modifier) {
                val mode = remember { mutableStateOf(viewMode) }
                MomentsAdaptiveContent(
                    uiState = MomentsFeedUiState(moments = items),
                    viewMode = mode.value,
                    onViewModeChange = { mode.value = it },
                    onAlbumZoomChange = {},
                    onCreateMoment = {},
                    onProfileClick = {},
                    onOpenMoment = { _, _ -> },
                    onAddReaction = { _, _ -> },
                    onDeleteFailedMoment = {},
                    onDismissUpload = {},
                    onFeedPaneWidthChange = onFeedPaneWidthChange,
                    detailPane = { id ->
                        onDetail(id)
                        val moment = items.first { it.id == id }
                        SharedTransitionLayout {
                            AnimatedContent(targetState = Unit) { _ ->
                                DetailContent(
                                    uiState = MomentDetailUiState(moment = moment, isLoading = false),
                                    onAction = {},
                                    onNavigateBack = null,
                                    snackbarHostState = remember { SnackbarHostState() },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this@AnimatedContent,
                                    isActivePage = false,
                                    commentsDocked = true,
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    // The preloader swallows these failures, so the video tile mounts without a drive.
    private inline fun <reified T : Any> unreachable(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, _, _ -> throw UnsupportedOperationException("no drive in this test") } as T

    // Only held by the zoom image; the base image comes through the fixture Coil loader.
    private fun unwiredImageLoader(): HomebaseImageLoader {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return unsafe.allocateInstance(HomebaseImageLoader::class.java) as HomebaseImageLoader
    }

    private fun ComposeUiTest.save(name: String) {
        // Coil resolves off the main clock.
        repeat(4) {
            Thread.sleep(120)
            mainClock.advanceTimeBy(500)
            waitForIdle()
        }
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    private fun shot(
        name: String,
        width: Int,
        height: Int,
        mode: MomentsViewMode,
        items: List<MomentFeedItem> = moments,
    ) {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width, height) {
                show(items, mode, dark)
                save("$name-${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test
    fun rendersEveryWidthInBothThemes() {
        shot("phone-timeline", 411, 800, MomentsViewMode.Timeline)
        shot("phone-album", 411, 800, MomentsViewMode.Album)
        shot("capped-timeline", 900, 800, MomentsViewMode.Timeline)
        shot("capped-tall", 900, 800, MomentsViewMode.Timeline, tallFirst)
        shot("capped-album", 900, 800, MomentsViewMode.Album)
        shot("pane-timeline-1135", 1135, 800, MomentsViewMode.Timeline)
        shot("pane-tall-1135", 1135, 800, MomentsViewMode.Timeline, tallFirst)
        shot("pane-text-1135", 1135, 800, MomentsViewMode.Timeline, talkFirst)
        shot("pane-album-1135", 1135, 800, MomentsViewMode.Album)
        shot("pane-timeline-1855", 1855, 1000, MomentsViewMode.Timeline)
        shot("pane-text-1855", 1855, 1000, MomentsViewMode.Timeline, talkFirst)
    }

    @Test
    fun anEmptyFeedKeepsItsEmptyStateFullWidth() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(1135, 800) {
                var detailShown = false
                show(emptyList(), MomentsViewMode.Timeline, dark, onDetail = { detailShown = true })
                save("pane-empty-${if (dark) "dark" else "light"}")
                assertTrue(!detailShown)
            }
        }
    }

    @Test
    fun clickingACardMovesTheSelectionIntoThePane() = runDesktopComposeUiTest(1135, 800) {
        val shown = mutableListOf<Uuid>()
        show(moments, MomentsViewMode.Timeline, dark = false, onDetail = { if (shown.lastOrNull() != it) shown += it })
        waitForIdle()
        assertEquals(moments.first().id, shown.last())
        onAllNodes(hasClickAction() and hasAnyDescendant(hasText(moments[1].description)))
            .onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(moments[1].id, shown.last())
        save("pane-selected-second-light")
    }

    @Test
    fun clickingASingleVideoCardSelectsItInThePane() = runDesktopComposeUiTest(1135, 800) {
        val video = moments.first { it.payloads.singleOrNull()?.contentType == "video/mp4" }
        val items = listOf(moments.first { it.payloads.singleOrNull()?.contentType == "image/jpeg" }, video)
        val shown = mutableListOf<Uuid>()
        show(items, MomentsViewMode.Timeline, dark = false, onDetail = { if (shown.lastOrNull() != it) shown += it })
        waitForIdle()
        assertEquals(items.first().id, shown.last())
        val cards = SemanticsMatcher("feed card") { it.config.getOrNull(SemanticsActions.OnClick)?.label != null }
        onAllNodes(cards)[1].performTouchInput { click() }
        // A double-tap handler delays the single tap until the window closes.
        mainClock.advanceTimeBy(600)
        waitForIdle()
        assertEquals(video.id, shown.last())
    }

    @Test
    fun theDefaultSelectionStaysWhenANewerMomentArrives() = runDesktopComposeUiTest(1135, 800) {
        val source = mutableStateOf(moments)
        val shown = mutableListOf<Uuid>()
        showLive(source, MomentsViewMode.Timeline, dark = false, onDetail = { shown += it })
        waitForIdle()
        assertEquals(moments.first().id, shown.last())
        source.value = listOf(moment(99)) + moments
        waitForIdle()
        assertEquals(moments.first().id, shown.last())
    }

    @Test
    fun theDividerResizesTheFeedPaneBetweenItsMinimums() = runDesktopComposeUiTest(1440, 800) {
        val stored = mutableListOf<Dp>()
        showLive(mutableStateOf(moments), MomentsViewMode.Timeline, dark = false, onFeedPaneWidthChange = { stored += it })
        waitForIdle()
        val divider = onNode(hasContentDescription("Resize the feed and moment panes"))
        fun dividerLeft() = divider.fetchSemanticsNode().boundsInRoot.left
        val range = MomentsLayoutSpec.feedPaneWidthRange(1440.dp)
        val start = dividerLeft()
        assertEquals(MomentsLayoutSpec.feedPaneWidth(1440.dp).value, start, 1f)

        divider.performTouchInput { swipeRight(startX = centerX, endX = centerX + 2000f) }
        waitForIdle()
        assertEquals(range.endInclusive.value, dividerLeft(), 1f)
        assertEquals(range.endInclusive, stored.single())
        save("pane-resized-widest-light")

        divider.performTouchInput { swipeLeft(startX = centerX, endX = centerX - 2000f) }
        waitForIdle()
        assertEquals(range.start.value, dividerLeft(), 1f)
        assertEquals(range.start, stored.last())
        save("pane-resized-narrowest-light")

        divider.requestFocus()
        divider.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals((range.start + MomentsLayoutSpec.FeedPaneResizeStep).value, dividerLeft(), 1f)
        assertEquals(range.start + MomentsLayoutSpec.FeedPaneResizeStep, stored.last())
    }

    fun crossingTheWideBreakpointKeepsTheScrolledFeed() = runDesktopComposeUiTest(1000, 800) {
        val width = mutableStateOf(411.dp)
        showLive(mutableStateOf(moments), MomentsViewMode.Timeline, dark = false, width = width)
        waitForIdle()
        onNode(hasScrollAction()).performScrollToIndex(6)
        waitForIdle()
        onAllNodes(hasText(moments[0].description)).assertCountEquals(0)
        width.value = 900.dp
        waitForIdle()
        onAllNodes(hasText(moments[0].description)).assertCountEquals(0)
        width.value = 411.dp
        waitForIdle()
        onAllNodes(hasText(moments[0].description)).assertCountEquals(0)
    }
}
