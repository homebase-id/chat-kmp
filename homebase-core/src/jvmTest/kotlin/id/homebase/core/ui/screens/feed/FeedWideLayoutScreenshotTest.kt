package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import id.homebase.api.client.contacts.AvatarResolver
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeedWideLayoutScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private fun ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 3, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @BeforeTest
    fun startDi() {
        startKoin {
            modules(
                module {
                    single { AvatarResolver(MutableStateFlow(emptyList())) }
                    // Avatars fall back to initials instead of reaching the demo identities over the network.
                    single<ImageLoader> {
                        ImageLoader.Builder(PlatformContext.INSTANCE)
                            .components { add(Interceptor { ErrorResult(null, it.request, IllegalStateException("offline")) }) }
                            .build()
                    }
                },
            )
        }
    }

    @AfterTest
    fun stopDi() = stopKoin()

    private val people = listOf(
        SuggestedPerson("samwise.gamgee.demo.rocks", isFollowed = false),
        SuggestedPerson("frodo.baggins.demo.rocks", isFollowed = true),
        SuggestedPerson("merry.brandybuck.demo.rocks", isFollowed = false),
        SuggestedPerson("pippin.took.demo.rocks", isFollowed = false),
    )
    private val names = mapOf(
        "samwise.gamgee.demo.rocks" to "Samwise Gamgee",
        "frodo.baggins.demo.rocks" to "Frodo Baggins",
        "pippin.took.demo.rocks" to "Peregrin Took",
    )

    @Composable
    private fun PolishedFeed(wide: Boolean, people: List<SuggestedPerson>, channels: List<String>) {
        FeedColumns(
            wide = wide,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            suggestions = {
                FeedSuggestionsColumn(
                    people = people,
                    channelNames = channels,
                    displayNameFor = { names[it] ?: it },
                    onFollow = {},
                    onUnfollow = {},
                    onPersonClick = {},
                    onSeeAll = {},
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items((0 until 6).toList()) {
                    Surface(Modifier.fillMaxWidth().height(220.dp)) {
                        Text("Post $it", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }

    private fun polishShot(name: String, width: Int, height: Int, wide: Boolean, dark: Boolean, empty: Boolean = false) =
        runDesktopComposeUiTest(width, height) {
            setContent {
                HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                    Box(Modifier.width(width.dp).height(height.dp)) {
                        PolishedFeed(
                            wide = wide,
                            people = if (empty) emptyList() else people,
                            channels = if (empty) emptyList() else listOf("Hiking", "Public posts", "The Shire"),
                        )
                    }
                }
            }
            waitForIdle()
            onAllNodesWithTag(FEED_SUGGESTIONS_TAG).assertCountEquals(if (wide) 1 else 0)
            save(name)
        }

    @Test fun polishWideLight() = polishShot("feed-wide-light", 1400, 900, wide = true, dark = false)
    @Test fun polishWideDark() = polishShot("feed-wide-dark", 1400, 900, wide = true, dark = true)
    @Test fun polishWideEmptyLight() = polishShot("feed-wide-empty-light", 1000, 700, wide = true, dark = false, empty = true)
    @Test fun polishPhoneLight() = polishShot("feed-phone-light", 412, 900, wide = false, dark = false)
    @Test fun polishPhoneDark() = polishShot("feed-phone-dark", 412, 900, wide = false, dark = true)

    @Composable
    private fun Harness(wide: Boolean, listState: androidx.compose.foundation.lazy.LazyListState) {
        FeedColumns(
            wide = wide,
            suggestions = { Text("suggestions", Modifier.testTag("suggestionsBody")) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("list")) {
                items((0 until 60).toList()) { Text("post $it", Modifier.height(80.dp)) }
            }
        }
    }

    @Test
    fun wideShowsTimelineAtWebWidthBesideSuggestions() = runDesktopComposeUiTest(1400, 900) {
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                Box(Modifier.width(1400.dp).height(900.dp)) {
                    Harness(wide = true, listState = rememberLazyListState())
                }
            }
        }
        val centre = onNodeWithTag(FEED_CENTRE_TAG).fetchSemanticsNode().boundsInRoot
        val side = onNodeWithTag(FEED_SUGGESTIONS_TAG).fetchSemanticsNode().boundsInRoot
        val density = 1f
        assertEquals(FeedContentMaxWidth.value * density, centre.width, 1f)
        assertEquals(FeedSuggestionsWidth.value * density, side.width, 1f)
        assertTrue(side.left >= centre.right, "suggestions sit right of the timeline")
        save("wide-1400")
    }

    @Test
    fun narrowKeepsSingleColumn() = runDesktopComposeUiTest(700, 900) {
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                Box(Modifier.width(700.dp).height(900.dp)) {
                    Harness(wide = false, listState = rememberLazyListState())
                }
            }
        }
        onAllNodesWithTag(FEED_SUGGESTIONS_TAG).assertCountEquals(0)
        onAllNodesWithTag("list").assertCountEquals(1)
        save("narrow-700")
    }

    @Test
    fun resizingAcrossTheGateKeepsScrollPosition() = runDesktopComposeUiTest(1400, 900) {
        var wide by mutableStateOf(true)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                state = rememberLazyListState()
                Box(Modifier.width(1400.dp).height(900.dp)) { Harness(wide = wide, listState = state) }
            }
        }
        runOnIdle { kotlinx.coroutines.runBlocking { state.scrollToItem(25, 10) } }
        waitForIdle()
        wide = false
        waitForIdle()
        assertEquals(25, state.firstVisibleItemIndex)
        assertEquals(10, state.firstVisibleItemScrollOffset)
        wide = true
        waitForIdle()
        assertEquals(25, state.firstVisibleItemIndex)
        assertEquals(10, state.firstVisibleItemScrollOffset)
        onAllNodesWithTag(FEED_SUGGESTIONS_TAG).assertCountEquals(1)
    }
}
