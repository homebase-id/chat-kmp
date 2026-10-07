package id.homebase.chat.viewonce

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import id.homebase.chat.conversationlist.FullScreenOverlay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.backhandler.LocalCompatNavigationEventDispatcherOwner
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.navigationevent.NavigationEventInput
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class ViewOnceViewerTest {

    private val back = object : NavigationEventInput() {
        fun press() = dispatchOnBackCompleted()
    }

    private var closed = 0
    private var dismissed = 0
    private var present by mutableStateOf(true)
    private var generation by mutableStateOf(0)
    private var secondPresent by mutableStateOf(false)

    private fun SkikoComposeUiTest.show(server: ViewOnceFakeServer) {
        setContent {
            @Suppress("DEPRECATION")
            val dispatcher = LocalCompatNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(back)
                onDispose { dispatcher.removeInput(back) }
            }
            MaterialTheme {
                if (present) {
                    ViewOnceViewer(
                        data = server.viewer(),
                        onViewerClosed = { closed++ },
                        onDismiss = { dismissed++ },
                        loader = server.loader,
                    )
                }
            }
        }
    }

    private fun SkikoComposeUiTest.showWithCapture(server: ViewOnceFakeServer, captured: androidx.compose.runtime.State<Boolean>) {
        setContent {
            MaterialTheme {
                ViewOnceViewer(
                    data = server.viewer(),
                    onViewerClosed = { closed++ },
                    onDismiss = { dismissed++ },
                    loader = server.loader,
                    captureObserver = { captured },
                )
            }
        }
    }

    private fun SkikoComposeUiTest.showRemountable(server: ViewOnceFakeServer, data: FullScreenOverlay.ViewOnceViewer) {
        setContent {
            MaterialTheme {
                if (present) {
                    key(generation) {
                        ViewOnceViewer(
                            data = data,
                            onViewerClosed = { closed++ },
                            onDismiss = { dismissed++ },
                            loader = server.loader,
                        )
                    }
                }
            }
        }
    }

    private fun SkikoComposeUiTest.awaitShown() {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag(VIEW_ONCE_VIEWER_IMAGE_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theOnlyThingToTapIsClose_andItConsumesTheItemOnce() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()

        assertEquals(
            1, onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
            "no save, share, forward or options affordance may exist",
        )
        assertEquals(1, server.requests)
        assertEquals(0L, runBlocking { server.cachedBytes() })

        onNodeWithTag(VIEW_ONCE_VIEWER_CLOSE_TAG).performClick()
        waitForIdle()
        present = false
        waitForIdle()

        assertEquals(1, closed)
        assertEquals(1, dismissed)
    }

    @Test
    fun theCaptionShowsInsideTheViewerAndTheViewerSaysNothingAboutViewingOnce() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        setContent {
            MaterialTheme {
                ViewOnceViewer(
                    data = server.viewer(caption = "Remember this place"),
                    onViewerClosed = { closed++ },
                    onDismiss = { dismissed++ },
                    loader = server.loader,
                )
            }
        }
        awaitShown()

        onNodeWithTag(VIEW_ONCE_VIEWER_CAPTION_TAG).assertTextEquals("Remember this place")
        assertEquals(
            1, onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
            "a caption adds no affordance beyond close",
        )
        for (word in listOf("once", "screenshot", "disappears")) {
            assertEquals(
                0, onAllNodesWithText(word, substring = true, ignoreCase = true).fetchSemanticsNodes().size, word,
            )
        }
    }

    @Test
    fun noCaptionMeansNoCaptionNode() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()
        onNodeWithTag(VIEW_ONCE_VIEWER_CAPTION_TAG).assertDoesNotExist()
    }

    @Test
    fun whileTheScreenIsCapturedTheBlockedPanelReplacesTheMedia() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        val captured = mutableStateOf(false)
        showWithCapture(server, captured)
        awaitShown()
        onNodeWithTag(VIEW_ONCE_VIEWER_BLOCKED_TAG).assertDoesNotExist()

        captured.value = true
        waitForIdle()
        onNodeWithTag(VIEW_ONCE_VIEWER_BLOCKED_TAG).assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_IMAGE_TAG).assertDoesNotExist()

        captured.value = false
        waitForIdle()
        onNodeWithTag(VIEW_ONCE_VIEWER_IMAGE_TAG).assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_BLOCKED_TAG).assertDoesNotExist()
    }

    @Test
    fun theBackGestureCountsAsClosing_andLeavingCompositionDoesNotCloseTwice() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()

        runOnIdle { back.press() }
        waitForIdle()
        present = false
        waitForIdle()

        assertEquals(1, closed)
        assertEquals(1, dismissed)
    }

    @Test
    fun leavingCompositionAfterTheMediaWasShownConsumesTheItemEvenWithoutAClick() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()

        present = false
        waitForIdle()

        assertEquals(1, closed)
        assertEquals(0, dismissed)
        waitUntil(timeoutMillis = 5_000) { server.evictedImages.isNotEmpty() && !server.cached.isEphemeral(server.fileId) }
    }

    @Test
    fun anItemThatNeverLoadedIsNotUsedUp() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        server.status = HttpStatusCode.InternalServerError
        show(server)
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag(VIEW_ONCE_VIEWER_RETRY_TAG)).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag(VIEW_ONCE_VIEWER_CLOSE_TAG).performClick()
        waitForIdle()
        present = false
        waitForIdle()

        assertEquals(0, closed, "closing a viewer that never showed anything must not consume the item")
        assertEquals(1, dismissed)
    }

    @Test
    fun remountingTheViewerOnTheSameOverlayDoesNotShowTheItemASecondTime() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        val overlay = server.viewer()
        showRemountable(server, overlay)
        awaitShown()
        assertEquals(1, server.requests)

        generation++
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { dismissed == 1 }

        assertEquals(1, server.requests, "the remounted viewer must not fetch the payload again")
        assertEquals(1, closed, "the first viewer consumed the item once")
        assertEquals(
            0, onAllNodes(hasTestTag(VIEW_ONCE_VIEWER_IMAGE_TAG)).fetchSemanticsNodes().size,
            "the second viewer shows nothing",
        )
        waitUntil(timeoutMillis = 5_000) { !server.cached.isEphemeral(server.fileId) }
    }

    @Test
    fun aDisposedViewerCannotUnmarkAFileALiveViewerStillHolds() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        val first = server.viewer()
        val second = server.viewer()
        secondPresent = true
        setContent {
            MaterialTheme {
                if (present) ViewOnceViewer(first, onViewerClosed = {}, onDismiss = {}, loader = server.loader)
                if (secondPresent) ViewOnceViewer(second, onViewerClosed = {}, onDismiss = {}, loader = server.loader)
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasTestTag(VIEW_ONCE_VIEWER_IMAGE_TAG)).fetchSemanticsNodes().size == 2
        }

        present = false
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { server.evictedImages.isNotEmpty() }

        assertEquals(true, server.cached.isEphemeral(server.fileId), "the second viewer still holds the file")
        assertEquals(0L, runBlocking { server.cachedBytes() })

        secondPresent = false
        waitUntil(timeoutMillis = 5_000) { !server.cached.isEphemeral(server.fileId) }
    }
}
