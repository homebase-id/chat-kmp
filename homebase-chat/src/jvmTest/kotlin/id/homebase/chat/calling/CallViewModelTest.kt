package id.homebase.chat.calling

import androidx.lifecycle.viewModelScope
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallController
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallEngineFactory
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeCallEngine
import id.homebase.chat.services.calling.FakeCallScript
import id.homebase.chat.services.calling.LoopbackSignaling
import id.homebase.chat.services.calling.RING_TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {
    private val frodo = "frodo.baggins.demo.rocks"
    private val sam = "samwise.gamgee.demo.rocks"

    private class Rig(scope: TestScope, myScripts: List<FakeCallScript>) {
        val net = LoopbackSignaling(now = { scope.testScheduler.currentTime })
        val myEngines = mutableListOf<FakeCallEngine>()
        val me = CallController(
            selfOdinId = "frodo.baggins.demo.rocks",
            signaling = net.endpoint("frodo.baggins.demo.rocks"),
            engineFactory = CallEngineFactory { _ ->
                val script = myScripts.getOrElse(myEngines.size) { myScripts.last() }
                FakeCallEngine(scope.backgroundScope, script, "peer", "me").also { myEngines += it }
            },
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
        val vm = CallViewModel(me, audioRoutes = { listOf(AudioRoute.Earpiece, AudioRoute.Speaker) })
        val ui get() = vm.uiState.value
    }

    private fun rigTest(vararg scripts: FakeCallScript, block: suspend TestScope.(Rig) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this, scripts.toList().ifEmpty { listOf(FakeCallScript()) })
        try {
            block(rig)
        } finally {
            rig.vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.settle(ms: Long = 0) {
        runCurrent()
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun acceptAnswersTheRingAndConnects() = rigTest { rig ->
        rig.peer.startCall(frodo, video = false)
        settle()
        assertEquals(CallPhase.Incoming, rig.ui.phase)
        assertEquals(sam, rig.ui.peer)
        assertFalse(rig.ui.isVideoCall)
        assertTrue(rig.ui.visible)

        rig.vm.accept(withVideo = false)
        settle(1_001)
        assertEquals(CallPhase.Connected, rig.ui.phase)
        assertEquals(CallPhase.Connected, rig.peer.state.value.phase)
        assertTrue(rig.ui.connectedAtMs != null)
    }

    @Test
    fun acceptingAVideoCallAsVoiceDropsLocalVideoAndAcceptingWithVideoKeepsIt() = rigTest { rig ->
        rig.peer.startCall(frodo, video = true)
        settle()
        assertTrue(rig.ui.isVideoCall)
        rig.vm.accept(withVideo = false)
        settle(1_001)
        assertEquals(CallPhase.Connected, rig.ui.phase)
        assertFalse(rig.ui.localVideo)
        assertEquals(null, rig.myEngines.single().localVideo.value)
    }

    @Test
    fun acceptWithVideoPublishesTheCameraHandle() = rigTest { rig ->
        rig.peer.startCall(frodo, video = true)
        settle()
        rig.vm.accept(withVideo = true)
        settle(1_001)
        assertTrue(rig.ui.localVideo)
        assertTrue(rig.ui.videos.local != null)
    }

    @Test
    fun declineEndsBothSidesThenTheSummaryDismissesItself() = rigTest { rig ->
        rig.peer.startCall(frodo, video = false)
        settle()
        rig.vm.decline()
        settle()
        assertEquals(CallPhase.Ended(CallEndReason.Declined), rig.ui.phase)
        assertEquals(CallPhase.Ended(CallEndReason.Declined), rig.peer.state.value.phase)
        assertTrue(rig.ui.visible)

        settle(ENDED_LINGER_MS + 1)
        assertFalse(rig.ui.visible)
    }

    @Test
    fun muteReachesTheEngineAndTheUiState() = rigTest { rig ->
        rig.me.startCall(sam, video = false)
        settle()
        rig.peer.accept()
        settle(1_001)
        assertEquals(CallPhase.Connected, rig.ui.phase)

        rig.vm.toggleMute()
        settle()
        assertTrue(rig.ui.muted)
        assertTrue(rig.myEngines.single().muted)

        rig.vm.toggleMute()
        settle()
        assertFalse(rig.ui.muted)
        assertFalse(rig.myEngines.single().muted)
    }

    @Test
    fun videoToggleFlipsLocalVideoInTheUiState() = rigTest { rig ->
        rig.me.startCall(sam, video = true)
        settle()
        rig.peer.accept()
        settle(1_001)
        assertTrue(rig.ui.localVideo)

        rig.vm.toggleVideo()
        settle()
        assertFalse(rig.ui.localVideo)

        rig.vm.toggleVideo()
        settle()
        assertTrue(rig.ui.localVideo)
        assertTrue(rig.ui.videos.local != null)
    }

    @Test
    fun unansweredOutgoingCallTimesOutAsMissedAndDismisses() = rigTest { rig ->
        rig.me.startCall(sam, video = false)
        settle()
        assertEquals(CallPhase.Outgoing, rig.ui.phase)
        assertTrue(rig.ui.peerRinging)

        settle(RING_TIMEOUT_MS + 1)
        assertEquals(CallPhase.Ended(CallEndReason.Missed), rig.ui.phase)
        assertTrue(rig.ui.visible)
        settle(ENDED_LINGER_MS + 1)
        assertFalse(rig.ui.visible)
    }

    @Test
    fun failedCallStaysUpAndRetryRestartsTheCallToTheSamePeer() = rigTest(
        FakeCallScript(failConnect = true),
        FakeCallScript(),
    ) { rig ->
        rig.me.startCall(sam, video = false)
        settle()
        val firstCall = rig.ui.callId
        rig.peer.accept()
        settle(1_001)
        assertEquals(CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), rig.ui.phase)
        assertTrue(rig.ui.failed)

        settle(ENDED_LINGER_MS * 10)
        assertTrue(rig.ui.visible, "a failed call waits for Retry or Close")

        rig.vm.retry(withVideo = false)
        settle()
        assertEquals(CallPhase.Outgoing, rig.ui.phase)
        assertEquals(sam, rig.ui.peer)
        assertNotEquals(firstCall, rig.ui.callId)
        assertEquals(CallPhase.Incoming, rig.peer.state.value.phase)

        rig.peer.accept()
        settle(1_001)
        assertEquals(CallPhase.Connected, rig.ui.phase)
        assertEquals(2, rig.myEngines.size)
    }

    @Test
    fun closeDismissesAFailedCall() = rigTest(FakeCallScript(failConnect = true)) { rig ->
        rig.me.startCall(sam, video = false)
        settle()
        rig.peer.accept()
        settle(1_001)
        assertTrue(rig.ui.failed)
        rig.vm.close()
        assertFalse(rig.ui.visible)
    }

    @Test
    fun keyboardEndAsksOnlyWhenConnected() = rigTest { rig ->
        val events = mutableListOf<CallUiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rig.vm.events.toList(events) }

        rig.me.startCall(sam, video = false)
        settle()
        rig.peer.accept()
        settle(200)
        assertEquals(CallPhase.Connecting, rig.ui.phase)
        rig.vm.endFromKeyboard()
        settle()
        assertEquals(emptyList(), events)
        assertEquals(CallPhase.Ended(CallEndReason.Hangup(remote = false)), rig.ui.phase)

        rig.me.startCall(sam, video = false)
        settle()
        rig.peer.accept()
        settle(1_001)
        assertEquals(CallPhase.Connected, rig.ui.phase)
        rig.vm.endFromKeyboard()
        settle()
        assertEquals(listOf<CallUiEvent>(CallUiEvent.ConfirmEnd), events)
        assertEquals(CallPhase.Connected, rig.ui.phase)
    }

    @Test
    fun minimizedCallShowsThePillAndANewCallRestoresTheScreen() = rigTest { rig ->
        rig.me.startCall(sam, video = false)
        settle()
        rig.vm.minimize()
        assertFalse(rig.ui.minimized, "only an established call can be minimized")

        rig.peer.accept()
        settle(1_001)
        rig.vm.minimize()
        assertTrue(rig.ui.pillVisible)
        assertFalse(rig.ui.tileVisible)
        rig.vm.restore()
        assertFalse(rig.ui.pillVisible)
    }

    @Test
    fun minimizedVideoCallFloatsATileInsteadOfThePill() = rigTest { rig ->
        rig.me.startCall(sam, video = true)
        settle()
        rig.peer.accept()
        settle(1_001)
        rig.vm.minimize()
        assertTrue(rig.ui.tileVisible)
        assertFalse(rig.ui.pillVisible)
        rig.vm.restore()
        assertFalse(rig.ui.tileVisible)
    }
}
