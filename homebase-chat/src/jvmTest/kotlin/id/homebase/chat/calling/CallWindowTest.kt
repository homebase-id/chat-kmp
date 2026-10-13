package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallController
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.CallMedia
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeCallEngine
import id.homebase.chat.services.calling.FakeCallScript
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.chat.services.calling.LoopbackSignaling
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class CallWindowTest {
    private val sam = "samwise.gamgee.demo.rocks"

    @Test
    fun closeActionDependsOnThePhase() {
        val failed = CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute))
        assertEquals(CallWindowCloseAction.Decline, callWindowCloseAction(CallPhase.Incoming))
        assertEquals(CallWindowCloseAction.Hangup, callWindowCloseAction(CallPhase.Outgoing))
        assertEquals(CallWindowCloseAction.Hangup, callWindowCloseAction(CallPhase.Connecting))
        assertEquals(CallWindowCloseAction.ConfirmEnd, callWindowCloseAction(CallPhase.Connected))
        assertEquals(CallWindowCloseAction.ConfirmEnd, callWindowCloseAction(CallPhase.Reconnecting))
        assertEquals(CallWindowCloseAction.Dismiss, callWindowCloseAction(failed))
        assertEquals(CallWindowCloseAction.Dismiss, callWindowCloseAction(CallPhase.Ended(CallEndReason.Cancelled)))
        assertEquals(CallWindowCloseAction.Dismiss, callWindowCloseAction(CallPhase.Idle))
    }

    @Test
    fun titleShowsThePeerAndTheElapsedTimeOnlyWhileTheCallIsLive() {
        val connected = CallUiState(phase = CallPhase.Connected, callId = "c", connectedAtMs = 1_000)
        assertEquals("Sam · 01:23", callWindowTitle(connected, "Sam", 1_000 + 83_000))
        assertEquals("Sam · 1:01:01", callWindowTitle(connected, "Sam", 1_000 + 3_661_000))
        assertEquals("Sam · 00:00", callWindowTitle(connected, "Sam", 0))
        assertEquals("Sam", callWindowTitle(CallUiState(phase = CallPhase.Outgoing, callId = "c"), "Sam", 9_999))
        assertEquals("Sam", callWindowTitle(connected.copy(phase = CallPhase.Ended(CallEndReason.Cancelled)), "Sam", 99_999))
    }

    @Test
    fun windowIsVisibleUnlessIdleOrADismissedEnd() {
        val ended = CallUiState(phase = CallPhase.Ended(CallEndReason.Cancelled), callId = "c")
        val failed = CallUiState(phase = CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), callId = "c")
        assertFalse(callWindowVisible(CallUiState()))
        assertTrue(callWindowVisible(CallUiState(phase = CallPhase.Incoming, callId = "c")))
        assertTrue(callWindowVisible(ended))
        assertFalse(callWindowVisible(ended.copy(dismissedCallId = "c")))
        assertTrue(callWindowVisible(failed))
        assertFalse(callWindowVisible(failed.copy(dismissedCallId = "c")))
    }

    private class Rig(scope: TestScope) {
        val net = LoopbackSignaling(now = { scope.testScheduler.currentTime })
        val me = CallController(
            selfOdinId = "frodo.baggins.demo.rocks",
            signaling = net.endpoint("frodo.baggins.demo.rocks"),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope, FakeCallScript(), "peer", "me") },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val peer = CallController(
            selfOdinId = "samwise.gamgee.demo.rocks",
            signaling = net.endpoint("samwise.gamgee.demo.rocks"),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope, FakeCallScript(), "me", "peer") },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val focused = mutableListOf<String>()
        val model = CallWindowModel(CallViewModel(me, audioRoutes = { listOf(AudioRoute.Earpiece) }), focus = { focused += it })
    }

    private fun TestScope.settle(ms: Long = 0) {
        runCurrent()
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun oneSharedModelOpensTheWindowOnStartAndClosesItAfterTheEndedLinger() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this)
        try {
            assertFalse(rig.model.windowVisible.value)
            rig.me.startCall(sam, video = false)
            settle()
            assertTrue(rig.model.windowVisible.value)
            assertEquals(CallPhase.Outgoing, rig.model.uiState.value.phase)

            rig.peer.accept()
            settle(1_001)
            assertEquals(CallPhase.Connected, rig.model.uiState.value.phase)
            assertTrue(rig.model.uiState.value.inProgress)

            rig.model.requestClose()
            settle()
            assertEquals(CallPhase.Connected, rig.model.uiState.value.phase, "connected asks first")

            rig.me.hangup()
            settle()
            assertTrue(rig.model.windowVisible.value, "ended screen stays up for the linger")
            settle(ENDED_LINGER_MS + 100)
            assertFalse(rig.model.windowVisible.value)
        } finally {
            rig.model.viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun closingAnOutgoingCallHangsUpAndClosingAFailedOneDismisses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this)
        try {
            rig.me.startCall(sam, video = false)
            settle()
            rig.model.requestClose()
            settle()
            assertTrue(rig.model.uiState.value.phase is CallPhase.Ended)
            rig.model.requestClose()
            settle()
            assertFalse(rig.model.windowVisible.value, "closing the ended window dismisses it")
        } finally {
            rig.model.viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class StateSession(initial: CallState) : CallSession {
        override val state = MutableStateFlow(initial)
        override val videos = MutableStateFlow(CallVideos())
        val calls = mutableListOf<String>()
        override fun startCall(peer: String, video: Boolean) { calls += "start" }
        override fun accept() { calls += "accept" }
        override fun decline() { calls += "decline" }
        override fun hangup() { calls += "hangup" }
        override fun setMuted(muted: Boolean) { calls += "mute" }
        override fun setVideo(on: Boolean) { calls += "video" }
        override fun switchCamera() { calls += "flip" }
        override fun setAudioRoute(route: AudioRoute) { calls += "route" }
    }

    private val outDir = File("build/call-shots").apply { mkdirs() }

    private fun connectedState(video: Boolean) = CallState(
        phase = CallPhase.Connected,
        callId = "win",
        peer = sam,
        direction = CallDirection.Outgoing,
        media = CallMedia(localVideo = video, remoteVideo = video),
        connectedAtMs = System.currentTimeMillis() - 83_000,
        verificationCode = "123 456",
    )

    private fun withModel(state: CallState, block: (CallWindowModel, StateSession, MutableList<String>) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val session = StateSession(state)
        if (state.media.localVideo) session.videos.value = CallVideos(FakeVideo(0xFF5C6BC0, "FB"), FakeVideo(0xFF26A69A, "SG"))
        val focused = mutableListOf<String>()
        val model = CallWindowModel(CallViewModel(session, audioRoutes = { emptyList() }), focus = { focused += it })
        try {
            block(model, session, focused)
        } finally {
            model.viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.showWindow(model: CallWindowModel, dark: Boolean) {
        mainClock.autoAdvance = false
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                val ui by model.uiState.collectAsState()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    CallOverlay(
                        viewModel = model.viewModel,
                        ui = ui,
                        peerName = "Samwise Gamgee",
                        avatar = { size ->
                            Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text("SG", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        },
                    )
                }
            }
        }
        mainClock.advanceTimeBy(900)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.shoot(name: String, dark: Boolean) {
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(outDir, "window-$name-${if (dark) "dark" else "light"}.png"))
    }

    @Test
    fun callWindowContentRendersVideoAndVoiceInBothThemes() {
        for (dark in listOf(false, true)) {
            for ((video, w, h) in listOf(Triple(true, 960, 640), Triple(false, 420, 560))) {
                withModel(connectedState(video)) { model, _, _ ->
                    runDesktopComposeUiTest(w, h) {
                        showWindow(model, dark)
                        onNodeWithText("Samwise Gamgee", substring = true).assertExists()
                        shoot(if (video) "video-960x640" else "voice-420x560", dark)
                    }
                }
            }
        }
    }

    @Test
    fun incomingRingWindowOffersAcceptAndDecline() {
        for (dark in listOf(false, true)) {
            val state = connectedState(false).copy(phase = CallPhase.Incoming, direction = CallDirection.Incoming, connectedAtMs = null, verificationCode = null)
            withModel(state) { model, session, _ ->
                runDesktopComposeUiTest(420, 560) {
                    showWindow(model, dark)
                    shoot("incoming-420x560", dark)
                    onNode(hasContentDescription("Decline call from", substring = true)).performClick()
                    assertEquals(listOf("decline"), session.calls)
                }
            }
        }
    }

    @Test
    fun closingAConnectedWindowShowsTheEndConfirmationInsideIt() {
        for (dark in listOf(false, true)) {
            withModel(connectedState(false)) { model, session, _ ->
                runDesktopComposeUiTest(420, 560) {
                    showWindow(model, dark)
                    model.requestClose()
                    mainClock.advanceTimeBy(500)
                    onNodeWithText("End call?").assertExists()
                    val layers = onAllNodes(isRoot()).fetchSemanticsNodes().size
                    assertEquals(2, layers, "window plus the dialog layer")
                    val base = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
                    val dialog = onAllNodes(isRoot())[1].captureToImage().toAwtImage()
                    val composite = java.awt.image.BufferedImage(base.width, base.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    composite.createGraphics().apply { drawImage(base, 0, 0, null); drawImage(dialog, 0, 0, null); dispose() }
                    ImageIO.write(composite, "png", File(outDir, "window-end-confirm-420x560-${if (dark) "dark" else "light"}.png"))
                    assertTrue(session.calls.isEmpty(), "nothing ends until confirmed")
                }
            }
        }
    }

    @Test
    fun mainWindowShowsTheOngoingCallBarNotTheCallScreenAndTheBarFocusesTheCallWindow() {
        for (dark in listOf(false, true)) {
            withModel(connectedState(false)) { model, _, focused ->
                runDesktopComposeUiTest(900, 640) {
                    mainClock.autoAdvance = false
                    setContent {
                        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                                OngoingCallBarHost(model, peerName = { "Samwise Gamgee" })
                                LazyColumn(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    items(12) { i -> ListItem(headlineContent = { Text("Conversation $i") }, supportingContent = { Text("Last message preview") }) }
                                }
                            }
                        }
                    }
                    mainClock.advanceTimeBy(300)
                    onNodeWithTag(OngoingCallBarTag).assertExists()
                    assertTrue(onAllNodes(hasContentDescription("End call"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "call screen must not be composed here")
                    assertTrue(onAllNodes(hasTestTag(RemoteVideoTag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                    shoot("main-with-bar-900x640", dark)
                    onNodeWithTag(OngoingCallBarTag).performClick()
                    assertEquals(listOf("call"), focused)
                }
            }
        }
    }

    @Test
    fun barIsAbsentWhenNoCallIsInProgress() {
        withModel(CallState()) { model, _, _ ->
            runDesktopComposeUiTest(900, 200) {
                setContent { HomebaseTheme(darkTheme = false, followsSystemTheme = false) { OngoingCallBarHost(model, peerName = { "x" }) } }
                onNodeWithTag(OngoingCallBarTag).assertDoesNotExist()
            }
        }
    }
}
