package id.homebase.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.awaitApplication
import id.homebase.api.browser.DesktopAppFocusManager
import id.homebase.api.browser.WindowKeys
import id.homebase.chat.calling.CallViewModel
import id.homebase.chat.calling.CallWindowModel
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallMedia
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.calling.CallState
import id.homebase.chat.services.calling.CallVideos
import id.homebase.core.calling.DesktopCallWindow
import id.homebase.core.calling.ModelDesktopCallWindow
import id.homebase.core.settings.ThemeState
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// Runs the real CallWindow inside a real application{} with the real model, as Main.kt does. The unit tests
// compose inside a window scene, which provides a LifecycleOwner that application{} does not.
class CallWindowAppScopeTest {
    private class StateSession(initial: CallState) : CallSession {
        override val state = MutableStateFlow(initial)
        override val videos = MutableStateFlow(CallVideos())
        override fun startCall(peer: String, video: Boolean) = Unit
        override fun accept() = Unit
        override fun decline() = Unit
        override fun hangup() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideo(on: Boolean) = Unit
        override fun switchCamera() = Unit
        override fun setAudioRoute(route: AudioRoute) = Unit
    }

    // The real bridge except for the body: PeerAvatar needs the production Koin graph, which is not what is under test.
    private class BridgeWithPlainBody(real: ModelDesktopCallWindow) : DesktopCallWindow by real {
        @Composable
        override fun Content(onMinimize: () -> Unit, topInset: Dp, mini: Boolean, onToggleMini: () -> Unit, onMiniDragStart: () -> Unit, onMiniDrag: () -> Unit) = Text("in call")
    }

    private fun callFrames(): List<Frame> = Window.getWindows().filterIsInstance<Frame>().filter { it.isDisplayable && it.title.startsWith("Samwise") }

    @Test
    fun aCallOpensTheWindowTitledWithTheLiveCallAndClosesItWhenTheCallIsGone() {
        if (GraphicsEnvironment.isHeadless()) return
        val session = StateSession(CallState())
        val focused = mutableListOf<String>()
        val model = CallWindowModel(
            viewModel = CallViewModel(session, audioRoutes = { emptyList() }),
            focus = { focused += it },
            displayName = { "Samwise Gamgee" },
        )
        var openTitle: String? = null
        var registered = false
        var framesAfterEnd = -1
        var failure: Throwable? = null

        runBlocking {
            withTimeout(60_000) {
                awaitApplication {
                    LaunchedEffect(Unit) {
                        try {
                            assertEquals(0, callFrames().size)
                            session.state.value = CallState(
                                phase = CallPhase.Connected,
                                callId = "c1",
                                peer = "samwise.gamgee.demo.rocks",
                                direction = CallDirection.Outgoing,
                                media = CallMedia(),
                                connectedAtMs = System.currentTimeMillis() - 83_000,
                            )
                            repeat(100) { if (callFrames().isEmpty()) delay(100) }
                            openTitle = callFrames().singleOrNull()?.title
                            DesktopAppFocusManager.requestFocus("test", WindowKeys.CALL)
                            delay(500)
                            registered = callFrames().isNotEmpty()
                            session.state.value = CallState()
                            repeat(50) { if (callFrames().isNotEmpty()) delay(100) }
                            framesAfterEnd = callFrames().size
                        } catch (t: Throwable) {
                            failure = t
                        }
                        exitApplication()
                    }
                    CallWindow(BridgeWithPlainBody(ModelDesktopCallWindow(model)), DesktopPreferences(InMemorySettings()), ThemeState.Light)
                }
            }
        }

        failure?.let { throw it }
        assertNotNull(openTitle, "the call window must open")
        assertTrue(Regex("Samwise Gamgee · 01:\\d\\d").matches(openTitle!!), "title was $openTitle")
        assertTrue(registered)
        assertEquals(0, framesAfterEnd)
    }
}
