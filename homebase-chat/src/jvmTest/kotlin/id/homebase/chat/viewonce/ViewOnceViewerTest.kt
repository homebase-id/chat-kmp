package id.homebase.chat.viewonce

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import kotlin.test.assertTrue

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

    private fun SkikoComposeUiTest.assertNoSaveShareForward() {
        for (word in listOf("Save", "Share", "Forward", "Download")) {
            assertEquals(
                0, onAllNodes(hasText(word, substring = true, ignoreCase = true) or hasContentDescription(word, substring = true, ignoreCase = true))
                    .fetchSemanticsNodes().size,
                word,
            )
        }
    }

    @Test
    fun aPhotoViewerOffersOnlyBackInfoReactAndReply_andNeverSaveShareOrForward() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()

        assertEquals(
            setOf(
                VIEW_ONCE_VIEWER_CLOSE_TAG, VIEW_ONCE_VIEWER_INFO_TAG, VIEW_ONCE_VIEWER_REACT_TAG, VIEW_ONCE_VIEWER_REPLY_TAG,
            ),
            tagsOfClickables(),
        )
        onNode(hasTestTag(VIEW_ONCE_VIEWER_REPLY_TAG) and hasContentDescription("Reply")).assertExists()
        val react = onNodeWithTag(VIEW_ONCE_VIEWER_REACT_TAG).getBoundsInRoot()
        val reply = onNodeWithTag(VIEW_ONCE_VIEWER_REPLY_TAG).getBoundsInRoot()
        assertEquals(react.right - react.left, reply.right - reply.left, "reply is the same circle as react")
        assertEquals(react.bottom - react.top, reply.bottom - reply.top, "reply is the same circle as react")
        assertEquals(react.top, reply.top, "reply sits beside react")
        assertEquals(1, server.requests)
        assertEquals(0L, runBlocking { server.cachedBytes() })
        assertNoSaveShareForward()
    }

    private fun SkikoComposeUiTest.tagsOfClickables(): Set<String> =
        onAllNodes(hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .toSet()

    @Test
    fun theTopRightOneOpensTheExplainerAndClosingItIsNotAView() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()

        onNodeWithTag(VIEW_ONCE_VIEWER_INFO_TAG).performClick()
        waitForIdle()
        onNodeWithTag(VIEW_ONCE_INTRO_TITLE_TAG).assertExists()
        assertEquals(0, closed)
        assertEquals(0, dismissed)

        onNodeWithTag(VIEW_ONCE_INTRO_OK_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(VIEW_ONCE_INTRO_TITLE_TAG)).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag(VIEW_ONCE_VIEWER_IMAGE_TAG).assertExists()
        assertEquals(0, closed, "the sheet closing is not a second view")
        assertEquals(0, dismissed)
        assertEquals(1, server.requests, "the explainer never reloads the media")
    }

    @Test
    fun reactAndReplyAreThereFromTheFirstFrameBeforeTheMediaHasLoaded() = runSkikoComposeUiTest {
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(isVideo = true, mediaShown = false, failed = false, onClose = {}) { fill -> Box(fill) }
            }
        }
        waitForIdle()
        onNodeWithTag(VIEW_ONCE_VIEWER_REACT_TAG).assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_REPLY_TAG).assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_INFO_TAG).assertExists()
    }

    @Test
    fun backConsumesTheItemExactlyOnce() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()

        onNodeWithTag(VIEW_ONCE_VIEWER_CLOSE_TAG).performClick()
        waitForIdle()
        present = false
        waitForIdle()

        assertEquals(1, closed)
        assertEquals(1, dismissed)
    }

    @Test
    fun reactingNeverClosesOrConsumesTheViewer_andReplyConsumesItOnce() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        val reacted = mutableListOf<String>()
        var replied = 0
        setContent {
            MaterialTheme {
                ViewOnceViewer(
                    data = server.viewer(),
                    onViewerClosed = { closed++ },
                    onDismiss = { dismissed++ },
                    onReact = { reacted += it },
                    onReply = { replied++ },
                    reactions = listOf("A", "B"),
                    loader = server.loader,
                )
            }
        }
        awaitShown()

        onNodeWithTag(VIEW_ONCE_VIEWER_REACT_TAG).performClick()
        waitForIdle()
        onNodeWithTag("${VIEW_ONCE_VIEWER_REACTION_TAG_PREFIX}1").performClick()
        waitForIdle()

        assertEquals(listOf("B"), reacted)
        assertEquals(0, closed, "a reaction is not a close")
        assertEquals(0, dismissed)
        onNodeWithTag(VIEW_ONCE_VIEWER_IMAGE_TAG).assertExists()
        assertEquals(1, server.requests, "a reaction never fetches the payload again")

        onNodeWithTag(VIEW_ONCE_VIEWER_REPLY_TAG).performClick()
        waitForIdle()
        present = false
        waitForIdle()

        assertEquals(1, replied)
        assertEquals(1, closed, "replying consumes the item once, however many ways it can end")
        assertEquals(1, dismissed)
    }

    @Test
    fun aTapHidesOnlyTheTopRow_andBackReactAndReplyNeverGoAway() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        show(server)
        awaitShown()
        val alwaysThere = listOf(
            VIEW_ONCE_VIEWER_CLOSE_TAG, VIEW_ONCE_VIEWER_REACT_TAG, VIEW_ONCE_VIEWER_REPLY_TAG,
        )

        onNodeWithTag(VIEW_ONCE_VIEWER_IMAGE_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { !present(VIEW_ONCE_VIEWER_INFO_TAG) }
        for (tag in alwaysThere) assertTrue(present(tag), tag)

        onNodeWithTag(VIEW_ONCE_VIEWER_IMAGE_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { present(VIEW_ONCE_VIEWER_INFO_TAG) }
        for (tag in alwaysThere) assertTrue(present(tag), tag)
    }

    @Test
    fun theLivePathShowsTheSenderTheTimeAndTheCaptionOnItsOwnContainer() = runSkikoComposeUiTest {
        val server = runBlocking { ViewOnceFakeServer().start() }
        setContent {
            MaterialTheme {
                ViewOnceViewer(
                    data = server.viewer(
                        caption = "Remember this place",
                        senderName = "Alice",
                        sentAt = kotlin.time.Clock.System.now(),
                    ),
                    onViewerClosed = {},
                    onDismiss = {},
                    loader = server.loader,
                )
            }
        }
        awaitShown()
        onNodeWithText("Alice").assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_TITLE_TAG).assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_CAPTION_TAG).assertExists()
        onNodeWithText("Remember this place").assertExists()
    }

    private fun SkikoComposeUiTest.present(tag: String) = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theCaptionShowsInsideTheViewerAboveTheControlsAndTheViewerSaysNothingAboutViewingOnce() = runSkikoComposeUiTest {
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
        val captionBottom = onNodeWithTag(VIEW_ONCE_VIEWER_CAPTION_TAG).getBoundsInRoot().bottom
        val controlsTop = onNodeWithTag(VIEW_ONCE_VIEWER_REPLY_TAG).getBoundsInRoot().top
        assertTrue(captionBottom <= controlsTop, "the caption sits above the bottom controls")
        for (word in listOf("once", "screenshot", "disappears")) {
            assertEquals(
                0, onAllNodesWithText(word, substring = true, ignoreCase = true).fetchSemanticsNodes().size, word,
            )
        }
    }

    @Test
    fun aVideoViewerSaysNothingExplanatoryEither() = runSkikoComposeUiTest {
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(isVideo = true, mediaShown = true, failed = false, onClose = {}) { fill ->
                    Box(fill.testTag("player"))
                }
            }
        }
        for (word in listOf("once", "disappears")) {
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

    @Test
    fun aVideoFrameShowsPlayPauseAndElapsedAndRemainingTimeAndATapNeverHidesBack() = runSkikoComposeUiTest {
        var playing by mutableStateOf(true)
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(
                    isVideo = true,
                    mediaShown = true,
                    failed = false,
                    onClose = { closed++ },
                    positionMs = { 5_000L },
                    durationMs = 20_000L,
                    playing = playing,
                    onTogglePlay = { playing = !playing },
                    onMutedChange = {},
                ) { fill -> Box(fill.testTag("player")) }
            }
        }
        assertTrue(present(VIEW_ONCE_VIEWER_CLOSE_TAG))
        assertTrue(present(VIEW_ONCE_VIEWER_PROGRESS_TAG))
        onNodeWithTag(VIEW_ONCE_VIEWER_ELAPSED_TAG).assertTextEquals("0:05")
        onNodeWithTag(VIEW_ONCE_VIEWER_REMAINING_TAG).assertTextEquals("-0:15")
        onNode(hasTestTag(VIEW_ONCE_VIEWER_PLAY_TAG) and hasContentDescription("Pause")).assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_PLAY_TAG).performClick()
        waitForIdle()
        assertTrue(!playing)
        onNode(hasTestTag(VIEW_ONCE_VIEWER_PLAY_TAG) and hasContentDescription("Play")).assertExists()

        onNodeWithTag(VIEW_ONCE_VIEWER_PLAY_TAG).performClick()
        waitForIdle()
        assertTrue(playing)

        repeat(3) {
            onNodeWithTag("player").performClick()
            waitUntil(timeoutMillis = 5_000) { !present(VIEW_ONCE_VIEWER_INFO_TAG) }
            assertTrue(present(VIEW_ONCE_VIEWER_CLOSE_TAG), "back survives a tap")
            assertTrue(present(VIEW_ONCE_VIEWER_PROGRESS_TAG), "the transport stays")
            assertTrue(present(VIEW_ONCE_VIEWER_REPLY_TAG), "Reply stays")
            onNodeWithTag("player").performClick()
            waitUntil(timeoutMillis = 5_000) { present(VIEW_ONCE_VIEWER_INFO_TAG) }
        }

        onNodeWithTag("player").performClick()
        waitUntil(timeoutMillis = 5_000) { !present(VIEW_ONCE_VIEWER_INFO_TAG) }
        onNodeWithTag(VIEW_ONCE_VIEWER_PLAY_TAG).performClick()
        waitUntil(timeoutMillis = 5_000) { present(VIEW_ONCE_VIEWER_INFO_TAG) }
        assertTrue(!playing, "pausing brings the top row back")

        val transportBottom = onNodeWithTag(VIEW_ONCE_VIEWER_PLAY_TAG).getBoundsInRoot().bottom
        val replyTop = onNodeWithTag(VIEW_ONCE_VIEWER_REPLY_TAG).getBoundsInRoot().top
        assertTrue(transportBottom <= replyTop, "the transport row sits above the Reply row")
        onNodeWithTag(VIEW_ONCE_VIEWER_CLOSE_TAG).performClick()
        assertEquals(1, closed)
    }

    @Test
    fun playbackTimesNeverGroupIntoHoursBelowAnHourAndNeverGoNegative() {
        assertEquals("0:00", formatPlaybackTime(-5L))
        assertEquals("0:05", formatPlaybackTime(4_600L))
        assertEquals("1:05", formatPlaybackTime(65_000L))
        assertEquals("1:01:05", formatPlaybackTime(3_665_000L))
    }

    @Test
    fun theViewersVideoIsDecryptedToTheViewOnceTempAndSweptAndNothingGoesToTheCaches() = runBlocking {
        val server = ViewOnceFakeServer().start()
        val stub = id.homebase.api.serialization.OdinSystemSerializer.serialize(
            id.homebase.api.video.VideoMetadata(mimeType = "video/mp4", isDescriptorContentComplete = true, isSegmented = false, fileSize = 1L),
        )
        val overlay = server.viewer(kind = ViewOnceDescriptor.KIND_VIDEO)
        val overlayData = overlay.copy(payload = overlay.payload.copy(descriptorContent = stub)).videoPlayerData()
        val video = id.homebase.api.video.VideoPlayerData(
            overlayData.fileId, overlayData.driveId, overlayData.payloadKey, overlayData.keyHeader,
            overlayData.payload.descriptorContent, overlayData.remoteOdinId, overlayData.globalTransitId, overlayData.scratchSub,
        )
        val fileOps = object : id.homebase.api.file.FileOperationsProvider {
            override fun getCacheDirectory() = server.tempDir
            override fun openFileInput(path: String): io.ktor.client.request.forms.InputProvider = error("disk read")
            override suspend fun readFileBytes(path: String): ByteArray = error("disk read")
            override fun deleteTempFile(path: String) = false
            override fun getFileSize(path: String) = java.io.File(path).length()
            override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("unused")
            override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("unused")
            override suspend fun writeStream(path: String, data: kotlinx.coroutines.flow.Flow<ByteArray>) = error("unused")
        }

        server.loader.begin(server.fileId)
        val content = id.homebase.api.video.resolveVideoContent(video, server.provider, fileOps = fileOps)

        val mp4 = kotlin.test.assertIs<id.homebase.api.video.VideoContent.Mp4File>(content)
        val viewOnceDir = java.io.File(server.tempDir, "hb-scratch/view-once")
        assertEquals(viewOnceDir.canonicalPath, java.io.File(mp4.filePath).parentFile.canonicalPath)
        kotlin.test.assertContentEquals(server.plainImage, java.io.File(mp4.filePath).readBytes())
        assertEquals(0L, server.cachedBytes(), "the encrypted payload must not enter the disk caches")

        assertTrue(id.homebase.api.file.sweepViewOnceTemps(server.tempDir))
        assertTrue(!java.io.File(mp4.filePath).exists(), "the startup sweep reaps a temp a dead viewer left")
    }

    @Test
    fun aVideoViewerMutesFromAButtonBesidePauseThatSaysWhichStateItIsIn() = runSkikoComposeUiTest {
        var muted by mutableStateOf(false)
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(
                    isVideo = true, mediaShown = true, failed = false, onClose = {}, muted = muted,
                    onMutedChange = { muted = it }, onTogglePlay = {},
                ) { fill -> Box(fill.testTag("player")) }
            }
        }
        waitForIdle()
        onNode(hasTestTag(VIEW_ONCE_VIEWER_MUTE_TAG) and hasContentDescription("Mute")).assertExists()
        onNodeWithTag(VIEW_ONCE_VIEWER_MUTE_TAG).performClick()
        waitForIdle()
        assertTrue(muted)
        onNode(hasTestTag(VIEW_ONCE_VIEWER_MUTE_TAG) and hasContentDescription("Unmute")).assertExists()
    }

    @Test
    fun aPhotoFrameHasTheSameBottomRowAsAVideoButNoMute() = runSkikoComposeUiTest {
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(
                    isVideo = false, mediaShown = true, failed = false, onClose = {}, onMutedChange = null,
                ) { fill -> Box(fill.testTag("player")) }
            }
        }
        waitForIdle()
        assertTrue(present("player"))
        assertTrue(present(VIEW_ONCE_VIEWER_REACT_TAG))
        assertTrue(present(VIEW_ONCE_VIEWER_REPLY_TAG))
        assertTrue(!present(VIEW_ONCE_VIEWER_MUTE_TAG))
    }

    @Test
    fun theTopBarSaysWhoSentItAndWhen() = runSkikoComposeUiTest {
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(
                    isVideo = false, mediaShown = true, failed = false, onClose = {},
                    senderName = "Alice", sentAt = "10:53",
                ) { fill -> Box(fill) }
            }
        }
        waitForIdle()
        onNodeWithText("Alice").assertExists()
        onNodeWithText("10:53").assertExists()
    }

    @Test
    fun whileLoadingOrFailedTheBackButtonStaysAndATapCannotHideIt() = runSkikoComposeUiTest {
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(isVideo = true, mediaShown = false, failed = false, onClose = {}) { fill ->
                    Box(fill.testTag("player"))
                }
            }
        }
        onNodeWithTag("player").performClick()
        waitForIdle()
        assertTrue(present(VIEW_ONCE_VIEWER_CLOSE_TAG))
        assertTrue(present(VIEW_ONCE_VIEWER_REPLY_TAG), "replying never waits for the media")
        assertTrue(present(VIEW_ONCE_VIEWER_REACT_TAG), "reacting never waits for the media")
    }

    @Test
    fun aFailedLoadStillLetsTheRecipientReplyAndReact() = runSkikoComposeUiTest {
        setContent {
            MaterialTheme {
                ViewOnceViewerFrame(isVideo = false, mediaShown = false, failed = true, onClose = {}) { fill -> Box(fill) }
            }
        }
        waitForIdle()
        assertTrue(present(VIEW_ONCE_VIEWER_CLOSE_TAG))
        assertTrue(present(VIEW_ONCE_VIEWER_REPLY_TAG))
        assertTrue(present(VIEW_ONCE_VIEWER_REACT_TAG))
    }
}
