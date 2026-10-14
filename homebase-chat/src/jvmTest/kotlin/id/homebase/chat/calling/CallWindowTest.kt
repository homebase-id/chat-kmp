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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewModelScope
import id.homebase.api.browser.WindowKeys
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.StunUrlStore
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallChannel
import id.homebase.chat.services.calling.CallService
import id.homebase.chat.services.calling.CallSignalCodec
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import coil3.ImageLoader
import coil3.PlatformContext
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module

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
    fun elapsedTimeIsOnlyReportedWhileTheCallIsLive() {
        val connected = CallUiState(phase = CallPhase.Connected, callId = "c", connectedAtMs = 1_000)
        assertEquals("01:23", callWindowElapsed(connected, 1_000 + 83_000))
        assertEquals("1:01:01", callWindowElapsed(connected, 1_000 + 3_661_000))
        assertEquals("00:00", callWindowElapsed(connected, 0))
        assertNull(callWindowElapsed(CallUiState(phase = CallPhase.Outgoing, callId = "c"), 9_999))
        assertNull(callWindowElapsed(connected.copy(phase = CallPhase.Ended(CallEndReason.Cancelled)), 99_999))
    }

    @Test
    fun windowTitleIsThePeerThenThePillTextOnceConnected() {
        withModel(connectedState(false).copy(connectedAtMs = System.currentTimeMillis() - 83_000)) { model, _, _ ->
            runDesktopComposeUiTest(400, 100) {
                setContent { Text(rememberCallWindowTitle(model), Modifier.testTag("title")) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Samwise Gamgee: Voice call · 01:", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
        }
        withModel(connectedState(false).copy(phase = CallPhase.Outgoing, connectedAtMs = null)) { model, _, _ ->
            runDesktopComposeUiTest(400, 100) {
                setContent { Text(rememberCallWindowTitle(model), Modifier.testTag("title")) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Samwise Gamgee: Voice call").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("title").assertTextEquals("Samwise Gamgee: Voice call")
            }
        }
    }

    @Test
    fun windowIsVisibleUnlessIdleOrADismissedEnd() {
        val ended = CallUiState(phase = CallPhase.Ended(CallEndReason.Cancelled), callId = "c")
        val failed = CallUiState(phase = CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), callId = "c")
        assertFalse(CallUiState().visible)
        assertTrue(CallUiState(phase = CallPhase.Incoming, callId = "c").visible)
        assertTrue(ended.visible)
        assertFalse(ended.copy(dismissedCallId = "c").visible)
        assertTrue(failed.visible)
        assertFalse(failed.copy(dismissedCallId = "c").visible)
    }

    private val frodo = "frodo.baggins.demo.rocks"

    private class ServiceRig(scope: TestScope, private val frodo: String, sam: String) {
        val net = LoopbackSignaling(now = { scope.testScheduler.currentTime })
        val bus = EventBus()
        val credentials = CredentialsManager()
        val service = CallService(
            credentialsManager = credentials,
            eventBus = bus,
            relay = { _, recipients, blob, _ ->
                CallSignalCodec.decode(blob)?.let { net.deliver(recipients.single(), frodo, it) }
                true
            },
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope, FakeCallScript(), "peer", "me") },
            stunUrls = StunUrlStore(EventBus(), kotlinx.coroutines.flow.flowOf(null), scope.backgroundScope),
            scope = scope.backgroundScope,
        )
        val peer = CallController(
            selfOdinId = sam,
            signaling = net.endpoint(sam),
            engineFactory = CallEngineFactory { _ -> FakeCallEngine(scope.backgroundScope, FakeCallScript(), "me", "peer") },
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
        val focused = mutableListOf<String>()
        val model = CallWindowModel(CallViewModel(service, audioRoutes = flowOf(listOf(AudioRoute.Earpiece))), focus = { focused += it }, displayName = { "Samwise Gamgee" })

        init {
            scope.backgroundScope.launch {
                net.endpoint(frodo).incoming.collect {
                    bus.emit(BackendEvent.LiveRelayReceived(OdinId(it.from), CallChannel.keyFor(frodo), CallSignalCodec.encode(it.signal), it.receivedAtMs))
                }
            }
        }

        suspend fun signIn() = credentials.setActiveCredentials(
            ApiCredentials.create(OdinId(frodo), "token", SecureByteArray(ByteArray(16))),
        )
    }

    private fun TestScope.settle(ms: Long = 0) {
        runCurrent()
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun oneSharedModelDrivesTheWindowAndTheMainWindowBarFromStartToTheEndedLinger() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = ServiceRig(this, frodo, sam)
        try {
            rig.signIn()
            settle()
            assertFalse(rig.model.windowVisible.value)

            rig.service.startCall(sam, video = false)
            settle()
            assertTrue(rig.model.windowVisible.value)
            assertEquals(CallPhase.Outgoing, rig.model.uiState.value.phase)

            settle(3_001) // no STUN URLs here, so the offer waits out awaitStunUrls before the peer rings
            rig.peer.accept()
            settle(1_001)
            assertEquals(CallPhase.Connected, rig.model.uiState.value.phase)

            runDesktopComposeUiTest(900, 640) {
                var hostedOverlay = false
                setContent {
                    HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                        CallChrome(
                            callWindow = rig.model,
                            topInset = 0.dp,
                            callHost = { content -> hostedOverlay = true; content() },
                        ) { _ -> Text("Conversation list") }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(OngoingCallBarTag).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Conversation list").assertExists()
                assertFalse(hostedOverlay, "the call screen belongs to the call window")
                assertTrue(onAllNodes(hasContentDescription("End call"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                onNodeWithTag(OngoingCallBarTag).performClick()
                assertEquals(listOf(WindowKeys.CALL), rig.focused)
            }

            rig.model.requestClose()
            settle()
            assertEquals(CallPhase.Connected, rig.model.uiState.value.phase, "closing a connected call asks first")

            rig.service.hangup()
            settle()
            assertTrue(rig.model.windowVisible.value, "the ended screen stays up for the linger")
            assertFalse(rig.model.uiState.value.inProgress)
            settle(ENDED_LINGER_MS + 100)
            assertFalse(rig.model.windowVisible.value)
        } finally {
            rig.model.viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun closingAnOutgoingCallHangsUpAndClosingTheEndedWindowDismissesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = ServiceRig(this, frodo, sam)
        try {
            rig.signIn()
            settle()
            rig.service.startCall(sam, video = false)
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

    @Test
    fun aNewCallGetsANewWindowKey() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = ServiceRig(this, frodo, sam)
        try {
            rig.signIn()
            settle()
            rig.service.startCall(sam, video = false)
            settle()
            val first = rig.model.windowKey.value
            rig.service.hangup()
            settle()
            rig.service.startCall(sam, video = true)
            settle()
            assertNotEquals(first, rig.model.windowKey.value)
        } finally {
            rig.model.viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class StateSession(initial: CallState) : CallSession {
        override val state = MutableStateFlow(initial)
        override val transitions get() = state
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
        if (state.media.localVideo) session.videos.value = CallVideos(FakeVideo("FB"), FakeVideo("SG"))
        val focused = mutableListOf<String>()
        val model = CallWindowModel(CallViewModel(session, audioRoutes = flowOf(emptyList())), focus = { focused += it }, displayName = { "Samwise Gamgee" })
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
                CompositionLocalProvider(LocalCallPermissionGate provides rememberCallPermissionGate()) {
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

    // The desktop call window is its own Window, outside the main window's CallHost.
    @Test
    fun callWindowContentProvidesItsOwnPermissionGate() {
        withModel(connectedState(video = false)) { model, _, _ ->
            runDesktopComposeUiTest(420, 560) {
                val koin = koinApplication { modules(module { single { ImageLoader.Builder(PlatformContext.INSTANCE).build() } }) }
                setContent { KoinIsolatedContext(koin) { HomebaseTheme { CallWindowContent(model, onMinimize = {}, topInset = 0.dp) } } }
                onNodeWithText("Samwise Gamgee", substring = true).assertExists()
            }
        }
    }

    // With a call window the main window's content skips CallHost, yet AppNavHost still starts calls.
    @Test
    fun callChromeProvidesThePermissionGateWithoutCallHost() {
        withModel(connectedState(video = false)) { model, _, _ ->
            runDesktopComposeUiTest(420, 560) {
                setContent {
                    HomebaseTheme {
                        CallChrome(callWindow = model, topInset = 0.dp, callHost = { it() }) {
                            LocalCallPermissionGate.current
                            Text("main window")
                        }
                    }
                }
                onNodeWithText("main window").assertExists()
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
    fun mainWindowShowsTheOngoingCallBarOverTheConversationListAndTheBarFocusesTheCallWindow() {
        for (dark in listOf(false, true)) {
            withModel(connectedState(false)) { model, _, focused ->
                runDesktopComposeUiTest(900, 640) {
                    mainClock.autoAdvance = false
                    setContent {
                        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                                CallChrome(callWindow = model, topInset = 0.dp, callHost = { it() }) { _ ->
                                    LazyColumn(Modifier.fillMaxWidth()) {
                                        items(12) { i -> ListItem(headlineContent = { Text("Conversation $i") }, supportingContent = { Text("Last message preview") }) }
                                    }
                                }
                            }
                        }
                    }
                    mainClock.advanceTimeBy(300)
                    onNodeWithTag(OngoingCallBarTag).assertExists()
                    shoot("main-with-bar-900x640", dark)
                    onNodeWithTag(OngoingCallBarTag).performClick()
                    assertEquals(listOf(WindowKeys.CALL), focused)
                }
            }
        }
    }

    @Test
    fun theCallOverlayIsHostedInPlaceOfTheBarOnlyWhenThePlatformHasNoCallWindow() {
        for (hostOverlay in listOf(true, false)) {
            withModel(connectedState(false)) { model, _, _ ->
                runDesktopComposeUiTest(900, 400) {
                    var hosted = false
                    setContent {
                        HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                            CallChrome(callWindow = if (hostOverlay) null else model, topInset = 0.dp, callHost = { content -> hosted = true; content() }) { _ -> Text("content") }
                        }
                    }
                    waitForIdle()
                    assertEquals(hostOverlay, hosted)
                    assertEquals(!hostOverlay, onAllNodesWithTag(OngoingCallBarTag).fetchSemanticsNodes().isNotEmpty())
                }
            }
        }
    }

    @Test
    fun barShowsWhileRingingOutAndItsMuteAndEndControlsActOnTheCallWithoutFocusingTheWindow() {
        withModel(connectedState(false).copy(phase = CallPhase.Outgoing, connectedAtMs = null)) { model, session, focused ->
            runDesktopComposeUiTest(900, 200) {
                setContent { HomebaseTheme(darkTheme = false, followsSystemTheme = false) { OngoingCallBarHost(model) } }
                waitForIdle()
                onNodeWithTag(OngoingCallBarTag).assertExists()
                onAllNodes(hasContentDescription("Mute microphone"), useUnmergedTree = true).onFirst().performClick()
                onAllNodes(hasContentDescription("End call with Samwise Gamgee"), useUnmergedTree = true).onFirst().performClick()
                assertEquals(listOf("mute", "hangup"), session.calls)
                assertTrue(focused.isEmpty(), "the bar's controls act in place")
            }
        }
    }

    @Test
    fun barIsAbsentWhenNoCallIsInProgress() {
        withModel(CallState()) { model, _, _ ->
            runDesktopComposeUiTest(900, 200) {
                setContent { HomebaseTheme(darkTheme = false, followsSystemTheme = false) { OngoingCallBarHost(model) } }
                onNodeWithTag(OngoingCallBarTag).assertDoesNotExist()
            }
        }
    }

    @Test
    fun trayItemsDoNotRestartOrLookTheNameUpAgainWhenOnlyUnrelatedCallStateChanges() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val session = StateSession(connectedState(false))
        var lookups = 0
        val model = CallWindowModel(
            CallViewModel(session, audioRoutes = flowOf(emptyList())),
            focus = {},
            displayName = { lookups++; "Samwise Gamgee" },
        )
        val job = launch { model.trayItems.collect {} }
        try {
            runCurrent()
            assertEquals(1, lookups)
            repeat(5) { n ->
                session.state.value = session.state.value.copy(verificationCode = "code $n", peerRinging = n % 2 == 0)
                runCurrent()
            }
            assertEquals(1, lookups, "verification and ringing changes are not part of the tray")

            session.state.value = session.state.value.copy(media = session.state.value.media.copy(muted = true))
            runCurrent()
            assertEquals(CallTrayItem.Mute(true), model.trayItems.value[1])
        } finally {
            job.cancel()
            model.viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun anUnansweredRingThatEndsLeavesNoWindowBehind() {
        val ended = { reason: CallEndReason -> CallUiState(phase = CallPhase.Ended(reason), callId = "c", direction = CallDirection.Incoming) }
        for (reason in listOf(CallEndReason.Missed, CallEndReason.Cancelled, CallEndReason.Declined)) {
            assertTrue(ended(reason).unansweredRingEnded(), "$reason")
        }
        assertFalse(ended(CallEndReason.Failed(FailReason.NoRoute)).unansweredRingEnded())
        assertFalse(ended(CallEndReason.Missed).copy(connectedAtMs = 5).unansweredRingEnded())
        assertFalse(ended(CallEndReason.Missed).copy(direction = CallDirection.Outgoing).unansweredRingEnded())
    }
}
