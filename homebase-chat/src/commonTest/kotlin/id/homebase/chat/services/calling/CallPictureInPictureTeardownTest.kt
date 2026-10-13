package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallPictureInPictureBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallPictureInPictureTeardownTest {
    private class Bridge : CallPictureInPictureBridge {
        var stops = 0
        override fun start(remoteTrack: Any, log: (String) -> Unit) = Unit
        override fun stop() { stops++ }
    }

    @Test
    fun stopsPipWhenTheCallEndsWithoutAnyComposition() = runTest {
        val states = MutableStateFlow(CallState(phase = CallPhase.Connected, callId = "c1"))
        val bridge = Bridge()
        backgroundScope.launch { CallPictureInPictureTeardown(states) { bridge }.run() }
        runCurrent()
        states.value = states.value.copy(phase = CallPhase.Reconnecting)
        runCurrent()
        states.value = states.value.copy(phase = CallPhase.Connected)
        runCurrent()
        assertEquals(0, bridge.stops)

        states.value = states.value.copy(phase = CallPhase.Ended(CallEndReason.Hangup(remote = true)))
        runCurrent()
        assertEquals(1, bridge.stops)

        states.value = CallState()
        runCurrent()
        assertEquals(1, bridge.stops)
    }
}
