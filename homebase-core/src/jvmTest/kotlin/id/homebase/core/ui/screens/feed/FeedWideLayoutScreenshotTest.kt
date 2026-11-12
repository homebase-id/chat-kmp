package id.homebase.core.ui.screens.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FeedWideLayoutScreenshotTest {
    private val outDir = File("build/feed-wide-shots").apply { mkdirs() }

    private fun ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 5) for (y in 0 until image.height step 5) colors += image.getRGB(x, y)
        assertTrue(colors.size > 3, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

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
