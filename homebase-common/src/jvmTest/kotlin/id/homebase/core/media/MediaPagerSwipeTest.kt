package id.homebase.core.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.panpf.zoomimage.compose.rememberZoomState
import com.github.panpf.zoomimage.compose.zoom.zoom
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals

/** Photos pages its photos with a pager whose every page is a one-photo [MediaPager] holding a zoomable. */
@OptIn(ExperimentalTestApi::class)
class MediaPagerSwipeTest {

    @Composable
    private fun ZoomablePhoto(zoomedTo: Float?) {
        val zoomState = rememberZoomState()
        if (zoomedTo != null) {
            LaunchedEffect(Unit) {
                snapshotFlow { zoomState.zoomable.containerSize }.first { it != IntSize.Zero }
                zoomState.zoomable.scale(zoomedTo)
            }
        }
        Box(Modifier.fillMaxSize().zoom(zoomState.zoomable))
    }

    private fun settledPageAfterSwipe(
        fraction: Float,
        millis: Long,
        zoomedTo: Float? = null,
        pager: @Composable (PagerState, Modifier, @Composable () -> Unit) -> Unit = { state, modifier, photo ->
            HorizontalPager(state, modifier, pageSpacing = 24.dp) { MediaPager(pageCount = 1) { photo() } }
        },
    ): Int {
        lateinit var state: PagerState
        runComposeUiTest {
            setContent {
                state = rememberPagerState { 3 }
                pager(state, Modifier.fillMaxSize().testTag("viewer")) { ZoomablePhoto(zoomedTo) }
            }
            onNodeWithTag("viewer").performTouchInput {
                val start = width * 0.8f
                swipeLeft(startX = start, endX = start - width * fraction, durationMillis = millis)
            }
            waitForIdle()
        }
        return state.settledPage
    }

    @Test
    fun aShortFlickOnAOnePhotoViewerTurnsToTheNextPhoto() {
        assertEquals(1, settledPageAfterSwipe(0.15f, 80))
        assertEquals(1, settledPageAfterSwipe(0.25f, 100))
    }

    @Test
    fun aSmallSlowDragSpringsBack() {
        assertEquals(0, settledPageAfterSwipe(0.10f, 1000))
    }

    @Test
    fun dragsOnAZoomedPhotoPanItAndKeepThePhoto() {
        assertEquals(0, settledPageAfterSwipe(0.25f, 100, zoomedTo = 3f))
        assertEquals(0, settledPageAfterSwipe(0.30f, 1000, zoomedTo = 3f))
    }

    @Test
    fun aShortFlickStillTurnsThePhotosOfOneMessage() {
        assertEquals(1, settledPageAfterSwipe(0.15f, 80) { state, modifier, photo ->
            MediaPager(pageCount = 3, modifier = modifier, state = state) { photo() }
        })
    }
}
